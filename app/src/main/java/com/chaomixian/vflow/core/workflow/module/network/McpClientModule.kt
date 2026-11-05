// 文件: main/java/com/chaomixian/vflow/core/workflow/module/network/McpClientModule.kt
// 描述: 通用 MCP（Model Context Protocol）客户端模块。
//      按 Streamable HTTP 连接任意 MCP 服务器，支持列出工具与调用工具。

package com.chaomixian.vflow.core.workflow.module.network

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.AiModuleMetadata
import com.chaomixian.vflow.core.module.AiModuleRiskLevel
import com.chaomixian.vflow.core.module.AiModuleUsageScope
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.model.ActionStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * MCP 客户端模块。
 *
 * 只支持 HTTP 系传输（Streamable HTTP / 兼容旧 SSE 响应），不支持 stdio：
 * Android 上没有 Python/Node 运行时，`uvx mcp-xxx` 这类本地服务接不了。
 *
 * 调用流程：initialize 握手 → notifications/initialized → tools/list 或 tools/call。
 * 会话 ID 进程内缓存 5 分钟，避免同一工作流反复握手。
 */
class McpClientModule : BaseModule() {

    companion object {
        const val ACTION_CALL = "call_tool"
        const val ACTION_LIST = "list_tools"

        /** 与服务端协商的协议版本，服务端不支持时会在 initialize 结果里回自己的版本。 */
        private const val PROTOCOL_VERSION = "2025-06-18"

        /** 会话缓存有效期。 */
        private const val SESSION_TTL_MS = 5 * 60 * 1000L

        /** 默认超时（秒）。 */
        private const val DEFAULT_TIMEOUT = 30

        /** 工具返回内容过长时截断，避免撑爆执行日志。 */
        private const val MAX_TEXT_LENGTH = 200_000

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val BASE_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        /**
         * MCP 会话缓存。key = 端点 + 鉴权信息，value = Mcp-Session-Id。
         * 服务端不返回会话 ID 时不写缓存，每次调用重新握手。
         */
        private object SessionCache {
            private data class Entry(val sessionId: String, val createdAt: Long)

            private val cache = ConcurrentHashMap<String, Entry>()

            fun get(key: String): String? {
                val entry = cache[key] ?: return null
                if (System.currentTimeMillis() - entry.createdAt > SESSION_TTL_MS) {
                    cache.remove(key)
                    return null
                }
                return entry.sessionId
            }

            fun put(key: String, sessionId: String) {
                cache[key] = Entry(sessionId, System.currentTimeMillis())
            }

            fun invalidate(key: String) {
                cache.remove(key)
            }
        }

        /** 会话失效（服务端返回 404），需要重新握手。 */
        private class SessionExpiredException : Exception("MCP 会话已失效")
    }

    /** 一次 HTTP 调用的原始结果。 */
    private data class RawResponse(
        val code: Int,
        val sessionId: String?,
        val contentType: String,
        val body: String
    )

    override val id = "vflow.network.mcp_client"

    override val metadata = ActionMetadata(
        name = "MCP 工具调用",
        description = "按 Streamable HTTP 连接任意 MCP 服务器，列出工具或调用指定工具",
        iconRes = R.drawable.rounded_extension_24,
        category = "网络",
        categoryId = "network"
    )

    override val aiMetadata = AiModuleMetadata(
        usageScopes = setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW),
        riskLevel = AiModuleRiskLevel.HIGH,
        workflowStepDescription = "Call a tool on an MCP (Model Context Protocol) server over Streamable HTTP, or list its tools.",
        inputHints = mapOf(
            "server_url" to "MCP endpoint URL, e.g. https://mcp.tdx.com.cn:3001/mcp",
            "auth_header" to "Header name for authentication, e.g. Authorization or tdx-api-key. Leave empty for no auth.",
            "auth_value" to "Header value, e.g. 'Bearer xxx' or 'TDX:xxxxxx'.",
            "action" to "call_tool to invoke a tool, list_tools to enumerate available tools.",
            "tool_name" to "Tool name to invoke, from list_tools.",
            "arguments" to "Arguments object for the tool, matching its inputSchema.",
            "timeout" to "Request timeout in seconds.",
        ),
        requiredInputIds = setOf("server_url")
    )

    private val actionOptions = listOf(ACTION_CALL, ACTION_LIST)

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = "server_url",
            name = "服务器地址",
            staticType = ParameterType.STRING,
            defaultValue = "",
            acceptsMagicVariable = true,
            supportsRichText = true,
            hint = "MCP 服务端点地址，仅支持 HTTP 传输"
        ),
        InputDefinition(
            id = "auth_header",
            name = "鉴权头名",
            staticType = ParameterType.STRING,
            defaultValue = "Authorization",
            acceptsMagicVariable = true,
            hint = "留空表示不带鉴权头。多数服务填 Authorization，部分服务用自定义头名"
        ),
        InputDefinition(
            id = "auth_value",
            name = "鉴权头值",
            staticType = ParameterType.STRING,
            defaultValue = "",
            acceptsMagicVariable = true,
            supportsRichText = true,
            hint = "如 Bearer sk-xxx，或服务商要求的密钥原文"
        ),
        InputDefinition(
            id = "action",
            name = "操作",
            staticType = ParameterType.ENUM,
            defaultValue = ACTION_CALL,
            options = actionOptions,
            optionsStringRes = listOf(
                R.string.option_vflow_network_mcp_client_action_call,
                R.string.option_vflow_network_mcp_client_action_list
            ),
            acceptsMagicVariable = false
        ),
        InputDefinition(
            id = "tool_name",
            name = "工具名",
            staticType = ParameterType.STRING,
            defaultValue = "",
            acceptsMagicVariable = true,
            hint = "先用「列出工具」查到工具名"
        ),
        InputDefinition(
            id = "arguments",
            name = "工具参数",
            staticType = ParameterType.ANY,
            defaultValue = emptyMap<String, Any?>(),
            acceptsMagicVariable = true,
            supportsRichText = true,
            hint = "JSON 对象，字段对应工具的 inputSchema"
        ),
        InputDefinition(
            id = "timeout",
            name = "超时(秒)",
            staticType = ParameterType.NUMBER,
            defaultValue = DEFAULT_TIMEOUT.toDouble(),
            acceptsMagicVariable = true,
            acceptedMagicVariableTypes = setOf(VTypeRegistry.NUMBER.id)
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition("result", "返回文本", VTypeRegistry.STRING.id),
        OutputDefinition("data", "返回数据", VTypeRegistry.DICTIONARY.id),
        OutputDefinition("tools", "工具清单", VTypeRegistry.LIST.id),
        OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id),
        OutputDefinition("error", "错误信息", VTypeRegistry.STRING.id)
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val url = (step.parameters["server_url"] as? String ?: "").trim()
        val host = url.removePrefix("https://").removePrefix("http://").substringBefore('/')
        val action = step.parameters["action"] as? String ?: ACTION_CALL
        val tool = (step.parameters["tool_name"] as? String ?: "").trim()

        return when {
            host.isEmpty() -> "MCP · 未配置地址"
            action == ACTION_LIST -> "MCP · $host · 列出工具"
            tool.isEmpty() -> "MCP · $host · 未选工具"
            else -> "MCP · $host · $tool"
        }
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val serverUrl = resolve(context, "server_url").trim()
        if (serverUrl.isEmpty()) {
            return@withContext ExecutionResult.Failure("参数错误", "请填写 MCP 服务器地址")
        }
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            return@withContext ExecutionResult.Failure("参数错误", "服务器地址必须以 http:// 或 https:// 开头")
        }

        val authHeader = resolve(context, "auth_header").trim()
        val authValue = resolve(context, "auth_value").trim()
        val action = context.getVariableAsString("action", ACTION_CALL)
        val toolName = resolve(context, "tool_name").trim()
        val timeout = (context.getVariableAsInt("timeout") ?: DEFAULT_TIMEOUT).coerceIn(1, 300)

        val headers = mutableMapOf<String, String>()
        if (authHeader.isNotEmpty() && authValue.isNotEmpty()) {
            headers[authHeader] = authValue
        }

        val cacheKey = "$serverUrl|$authHeader|${authValue.hashCode()}"

        try {
            if (action == ACTION_LIST) {
                onProgress(ProgressUpdate("正在获取工具列表..."))
                val result = rpcWithHandshake(cacheKey, serverUrl, headers, timeout, "tools/list", JSONObject())
                val tools = extractTools(result)
                val text = if (tools.isEmpty()) {
                    "服务端没有返回任何工具"
                } else {
                    tools.joinToString("\n") { "- ${it["name"]}：${it["description"]}" }
                }
                onProgress(ProgressUpdate("共 ${tools.size} 个工具"))
                ExecutionResult.Success(
                    mapOf(
                        "result" to text,
                        "data" to jsonToKotlin(result),
                        "tools" to tools,
                        "success" to true,
                        "error" to ""
                    )
                )
            } else {
                if (toolName.isEmpty()) {
                    return@withContext ExecutionResult.Failure("参数错误", "请填写要调用的工具名，或先执行「列出工具」")
                }
                onProgress(ProgressUpdate("正在调用 $toolName ..."))

                val arguments = try {
                    buildArguments(context)
                } catch (e: Exception) {
                    return@withContext ExecutionResult.Failure("参数错误", e.message ?: "工具参数不是合法 JSON")
                }

                val params = JSONObject().apply {
                    put("name", toolName)
                    put("arguments", arguments)
                }
                val result = rpcWithHandshake(cacheKey, serverUrl, headers, timeout, "tools/call", params)

                val isError = result.optBoolean("isError", false)
                val text = extractContent(result)
                onProgress(ProgressUpdate(if (isError) "工具返回错误" else "调用完成"))

                ExecutionResult.Success(
                    mapOf(
                        "result" to text,
                        "data" to jsonToKotlin(result),
                        "tools" to emptyList<Map<String, Any?>>(),
                        "success" to !isError,
                        "error" to if (isError) text else ""
                    )
                )
            }
        } catch (e: Exception) {
            val message = when (e) {
                is SessionExpiredException -> "MCP 会话失效且重连失败"
                else -> e.message ?: e.javaClass.simpleName
            }
            ExecutionResult.Failure("MCP 调用失败", message)
        }
    }

    // ------------------------------------------------------------------ 协议

    /**
     * 带握手的 RPC：优先复用缓存的会话，失败（会话失效）时重新握手重试一次。
     */
    private fun rpcWithHandshake(
        cacheKey: String,
        url: String,
        headers: Map<String, String>,
        timeout: Int,
        method: String,
        params: JSONObject
    ): JSONObject {
        val client = BASE_CLIENT.newBuilder()
            .connectTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .callTimeout(timeout.toLong() + 5, TimeUnit.SECONDS)
            .build()

        val cachedSession = SessionCache.get(cacheKey)
        val session = cachedSession ?: handshake(client, url, headers).also {
            if (it != null) SessionCache.put(cacheKey, it)
        }

        return try {
            callRpc(client, url, headers, session, method, params)
        } catch (e: SessionExpiredException) {
            SessionCache.invalidate(cacheKey)
            val fresh = handshake(client, url, headers)
            if (fresh != null) SessionCache.put(cacheKey, fresh)
            callRpc(client, url, headers, fresh, method, params)
        }
    }

    /**
     * initialize 握手，返回服务端下发的会话 ID（无状态服务端返回 null）。
     */
    private fun handshake(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>
    ): String? {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            put("params", JSONObject().apply {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().apply {
                    put("name", "vflow")
                    put("version", "1.0")
                })
            })
        }

        val raw = postJson(client, url, headers, null, payload)
        if (raw.code !in 200..299) {
            throw IllegalStateException("握手失败：HTTP ${raw.code} ${raw.body.take(200)}")
        }

        val json = parseRpcBody(raw)
            ?: throw IllegalStateException("握手失败：响应不是合法的 JSON-RPC")
        json.optJSONObject("error")?.let {
            throw IllegalStateException("握手失败：${it.optString("message")}")
        }

        // 通知服务端初始化完成，服务端通常回 202 且无 body
        val notify = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }
        runCatching { postJson(client, url, headers, raw.sessionId, notify) }

        return raw.sessionId
    }

    private fun callRpc(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        sessionId: String?,
        method: String,
        params: JSONObject
    ): JSONObject {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", method)
            put("params", params)
        }

        val raw = postJson(client, url, headers, sessionId, payload)

        if (raw.code == 404 && sessionId != null) {
            throw SessionExpiredException()
        }
        if (raw.code == 401 || raw.code == 403) {
            throw IllegalStateException("鉴权失败（HTTP ${raw.code}），请检查鉴权头名与值")
        }
        if (raw.code !in 200..299) {
            throw IllegalStateException("请求失败：HTTP ${raw.code} ${raw.body.take(200)}")
        }

        val json = parseRpcBody(raw)
            ?: throw IllegalStateException("服务端返回了空响应")
        json.optJSONObject("error")?.let {
            throw IllegalStateException("MCP 错误 ${it.optInt("code")}：${it.optString("message")}")
        }

        return json.optJSONObject("result") ?: JSONObject()
    }

    private fun postJson(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        sessionId: String?,
        payload: JSONObject
    ): RawResponse {
        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)

        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                builder.header(name, value)
            }
        }
        if (!sessionId.isNullOrBlank()) {
            builder.header("Mcp-Session-Id", sessionId)
        }

        client.newCall(builder.build()).execute().use { response ->
            return RawResponse(
                code = response.code,
                sessionId = response.header("Mcp-Session-Id"),
                contentType = response.header("Content-Type") ?: "",
                body = response.body?.string() ?: ""
            )
        }
    }

    /**
     * 解析响应体。部分服务端会以 text/event-stream 返回，需要拆 SSE 帧。
     */
    private fun parseRpcBody(raw: RawResponse): JSONObject? {
        if (raw.body.isBlank()) return null
        if (raw.contentType.contains("text/event-stream", ignoreCase = true)) {
            return parseSse(raw.body)
        }
        return try {
            JSONObject(raw.body)
        } catch (e: Exception) {
            parseSse(raw.body)
        }
    }

    private fun parseSse(text: String): JSONObject? {
        val frames = mutableListOf<String>()
        val buffer = StringBuilder()

        for (line in text.lineSequence()) {
            when {
                line.startsWith("data:") -> {
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(line.removePrefix("data:").trimStart())
                }
                line.isBlank() -> {
                    if (buffer.isNotEmpty()) {
                        frames.add(buffer.toString())
                        buffer.clear()
                    }
                }
                // event: / id: / retry: 等字段忽略
            }
        }
        if (buffer.isNotEmpty()) frames.add(buffer.toString())

        for (index in frames.indices.reversed()) {
            val json = try {
                JSONObject(frames[index])
            } catch (e: Exception) {
                continue
            }
            if (json.has("result") || json.has("error")) return json
        }
        return null
    }

    // -------------------------------------------------------------- 结果处理

    private fun extractContent(result: JSONObject): String {
        val array = result.optJSONArray("content")
        if (array == null || array.length() == 0) {
            return result.toString().take(MAX_TEXT_LENGTH)
        }

        val text = buildString {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "text" -> append(item.optString("text"))
                    "resource" -> append(item.optJSONObject("resource")?.optString("text").orEmpty())
                    else -> append(item.toString())
                }
                if (index < array.length() - 1) append('\n')
            }
        }
        return text.take(MAX_TEXT_LENGTH)
    }

    private fun extractTools(result: JSONObject): List<Map<String, Any?>> {
        val array = result.optJSONArray("tools") ?: return emptyList()
        val tools = mutableListOf<Map<String, Any?>>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            tools.add(
                mapOf(
                    "name" to item.optString("name"),
                    "description" to item.optString("description"),
                    "input_schema" to jsonToKotlin(item.opt("inputSchema"))
                )
            )
        }
        return tools
    }

    /**
     * 把 JSON 递归转成 Kotlin 原生类型。
     * 必须转换：VObjectFactory 不认 JSONObject/JSONArray，会整段转成字符串。
     */
    private fun jsonToKotlin(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { key -> jsonToKotlin(value.opt(key)) }
        is JSONArray -> (0 until value.length()).map { index -> jsonToKotlin(value.opt(index)) }
        else -> value
    }

    // -------------------------------------------------------------- 参数处理

    private fun resolve(context: ExecutionContext, id: String, default: String = ""): String {
        val raw = context.getVariableAsString(id, default)
        return if (raw.isEmpty()) raw else VariableResolver.resolve(raw, context)
    }

    private fun buildArguments(context: ExecutionContext): JSONObject {
        val variable = context.getVariable("arguments")
        val raw = variable.raw

        return when (raw) {
            null -> JSONObject()
            is JSONObject -> raw
            is Map<*, *> -> mapToJson(raw)
            is String -> {
                val text = VariableResolver.resolve(raw, context).trim()
                if (text.isEmpty()) {
                    JSONObject()
                } else {
                    try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("工具参数不是合法 JSON：${e.message}")
                    }
                }
            }
            else -> {
                val text = variable.asString().trim()
                if (text.isEmpty()) {
                    JSONObject()
                } else {
                    try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("工具参数不是合法 JSON：${e.message}")
                    }
                }
            }
        }
    }

    private fun mapToJson(map: Map<*, *>): JSONObject {
        val obj = JSONObject()
        map.forEach { (key, value) ->
            obj.put(key?.toString() ?: "", toJsonValue(value))
        }
        return obj
    }

    private fun toJsonValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is VObject -> toJsonValue(value.raw)
        is Map<*, *> -> mapToJson(value)
        is Iterable<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        else -> value
    }
}
