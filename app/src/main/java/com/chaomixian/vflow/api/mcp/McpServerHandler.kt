package com.chaomixian.vflow.api.mcp

import com.chaomixian.vflow.api.auth.AuthManager
import com.chaomixian.vflow.core.execution.WorkflowExecutionSnapshot
import com.chaomixian.vflow.core.execution.WorkflowExecutionStatus
import com.chaomixian.vflow.core.execution.WorkflowExecutor
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * MCP（Model Context Protocol）服务端。
 *
 * 传输方式：Streamable HTTP（2025-03-26 及以后），端点 [ENDPOINT]。
 * 只提供 tools 能力，把 vFlow 的工作流暴露给外部 AI 客户端：
 *  - list_workflows：列出设备上的工作流
 *  - run_workflow：执行工作流并等待结果
 *  - get_execution：查询某次执行的状态与结果
 *
 * 鉴权复用 [AuthManager] 的 Token，支持 `Authorization: Bearer <token>` 与 `?token=<token>`。
 */
class McpServerHandler(
    private val authManager: AuthManager,
    private val workflowManager: WorkflowManager,
    private val gson: Gson,
    private val serverVersion: String
) {

    companion object {
        /** 挂载路径 */
        const val ENDPOINT = "/mcp"

        private const val SERVER_NAME = "vflow"
        private const val DEFAULT_PROTOCOL_VERSION = "2025-06-18"
        private val SUPPORTED_PROTOCOL_VERSIONS = listOf(
            "2025-06-18",
            "2025-03-26",
            "2024-11-05"
        )

        // JSON-RPC 2.0 错误码
        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
        private const val INTERNAL_ERROR = -32603

        /** run_workflow 等待完成的上限（秒） */
        private const val MAX_WAIT_SECONDS = 120
    }

    /**
     * 处理一次 MCP HTTP 请求。
     */
    fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!isAuthorized(session)) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.UNAUTHORIZED,
                "application/json; charset=utf-8",
                gson.toJson(jsonError(null, -32001, "Unauthorized: missing or invalid token"))
            )
        }

        return when (session.method) {
            NanoHTTPD.Method.POST -> handlePost(session)
            // 不提供服务端主动推送流，按规范返回 405
            NanoHTTPD.Method.GET -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
                "application/json; charset=utf-8",
                gson.toJson(jsonError(null, METHOD_NOT_FOUND, "SSE stream is not supported, use POST"))
            )
            // 会话终止请求，本实现无状态，直接接受
            NanoHTTPD.Method.DELETE -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json; charset=utf-8",
                ""
            )
            else -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
                "application/json; charset=utf-8",
                gson.toJson(jsonError(null, METHOD_NOT_FOUND, "Method not allowed"))
            )
        }
    }

    // ---------------------------------------------------------------- HTTP 层

    private fun handlePost(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val body = readBody(session)
        if (body.isNullOrBlank()) {
            return jsonResponse(
                NanoHTTPD.Response.Status.OK,
                gson.toJson(jsonError(null, PARSE_ERROR, "Parse error: empty body"))
            )
        }

        val element = try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            return jsonResponse(
                NanoHTTPD.Response.Status.OK,
                gson.toJson(jsonError(null, PARSE_ERROR, "Parse error: ${e.message}"))
            )
        }

        // 批量请求
        if (element.isJsonArray) {
            val responses = JsonArray()
            element.asJsonArray.forEach { item ->
                if (item.isJsonObject) {
                    handleSingle(item.asJsonObject)?.let { responses.add(it) }
                }
            }
            // 全部是通知时，按规范返回 202 且无 body
            if (responses.size() == 0) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.ACCEPTED,
                    "application/json; charset=utf-8",
                    ""
                )
            }
            return jsonResponse(NanoHTTPD.Response.Status.OK, gson.toJson(responses))
        }

        if (!element.isJsonObject) {
            return jsonResponse(
                NanoHTTPD.Response.Status.OK,
                gson.toJson(jsonError(null, INVALID_REQUEST, "Invalid Request: body must be a JSON object or array"))
            )
        }

        val request = element.asJsonObject
        val response = handleSingle(request)
            ?: return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.ACCEPTED,
                "application/json; charset=utf-8",
                ""
            )

        val httpResponse = jsonResponse(NanoHTTPD.Response.Status.OK, gson.toJson(response))
        // initialize 的响应需要下发会话 ID，客户端后续请求会原样带回
        if (request.stringOrNull("method") == "initialize") {
            httpResponse.addHeader("Mcp-Session-Id", UUID.randomUUID().toString())
        }
        return httpResponse
    }

    /**
     * 读取请求体。优先走 NanoHTTPD 的 parseBody（对 application/json 会放进 postData）。
     */
    private fun readBody(session: NanoHTTPD.IHTTPSession): String? {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"]
        } catch (e: Exception) {
            try {
                val length = session.headers["content-length"]?.toIntOrNull() ?: return null
                if (length <= 0) return null
                val buffer = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = session.inputStream.read(buffer, read, length - read)
                    if (count <= 0) break
                    read += count
                }
                String(buffer, 0, read, Charsets.UTF_8)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun jsonResponse(status: NanoHTTPD.Response.IStatus, body: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body)
    }

    private fun isAuthorized(session: NanoHTTPD.IHTTPSession): Boolean {
        val header = session.headers["authorization"] ?: session.headers["Authorization"]
        val token = when {
            header != null && header.startsWith("Bearer ", ignoreCase = true) ->
                header.substring(7).trim()
            else -> session.parameters["token"]?.firstOrNull()?.trim()
        }
        if (token.isNullOrEmpty()) return false
        return authManager.verifyToken(token) != null
    }

    // ------------------------------------------------------------ JSON-RPC 层

    /**
     * 处理单条 JSON-RPC 消息。
     * @return 响应对象；若为通知（无 id）则返回 null，不产生响应。
     */
    private fun handleSingle(request: JsonObject): JsonObject? {
        val id = request.get("id")
        val method = request.stringOrNull("method")

        if (method == null) {
            return jsonError(id, INVALID_REQUEST, "Invalid Request: missing method")
        }

        // 通知消息（无 id）不需要响应
        if (id == null || id.isJsonNull) {
            return null
        }

        return try {
            when (method) {
                "initialize" -> jsonResult(id, buildInitializeResult(request))
                "ping" -> jsonResult(id, JsonObject())
                "tools/list" -> jsonResult(id, buildToolsList())
                "tools/call" -> handleToolCall(id, request)
                // 客户端可能探测的能力，返回空集合即可
                "resources/list" -> jsonResult(id, JsonObject().apply { add("resources", JsonArray()) })
                "prompts/list" -> jsonResult(id, JsonObject().apply { add("prompts", JsonArray()) })
                else -> jsonError(id, METHOD_NOT_FOUND, "Method not found: $method")
            }
        } catch (e: Exception) {
            jsonError(id, INTERNAL_ERROR, "Internal error: ${e.message}")
        }
    }

    private fun buildInitializeResult(request: JsonObject): JsonObject {
        val paramsElement = request.get("params")
        val requested = if (paramsElement != null && paramsElement.isJsonObject) {
            paramsElement.asJsonObject.stringOrNull("protocolVersion")
        } else {
            null
        }
        val version = if (requested != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requested)) {
            requested
        } else {
            DEFAULT_PROTOCOL_VERSION
        }

        return JsonObject().apply {
            addProperty("protocolVersion", version)
            add("capabilities", JsonObject().apply {
                add("tools", JsonObject().apply { addProperty("listChanged", false) })
            })
            add("serverInfo", JsonObject().apply {
                addProperty("name", SERVER_NAME)
                addProperty("version", serverVersion)
            })
            addProperty(
                "instructions",
                "vFlow 是运行在 Android 设备上的工作流自动化应用。用 list_workflows 查看可用工作流，" +
                    "用 run_workflow 执行（会等待完成并返回结果），用 get_execution 查询历史执行。"
            )
        }
    }

    private fun jsonResult(id: JsonElement?, result: JsonObject): JsonObject {
        return JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id ?: JsonNull.INSTANCE)
            add("result", result)
        }
    }

    private fun jsonError(id: JsonElement?, code: Int, message: String): JsonObject {
        return JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id ?: JsonNull.INSTANCE)
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        }
    }

    // --------------------------------------------------------------- 工具实现

    private fun buildToolsList(): JsonObject {
        val tools = JsonArray()

        tools.add(JsonObject().apply {
            addProperty("name", "list_workflows")
            addProperty("description", "列出本机 vFlow 中已有的工作流，返回 id、名称、描述、步骤数与启用状态。")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("query", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "按名称、描述或标签模糊过滤，可选。")
                    })
                    add("only_enabled", JsonObject().apply {
                        addProperty("type", "boolean")
                        addProperty("description", "只返回已启用的工作流，默认 false。")
                    })
                })
                addProperty("additionalProperties", false)
            })
        })

        tools.add(JsonObject().apply {
            addProperty("name", "run_workflow")
            addProperty("description",
                "执行指定的工作流并等待其结束。返回状态、耗时、「停止并返回」模块的返回值以及命名变量快照。" +
                    "若超过等待时间仍未结束，返回 running 状态与 execution_id，可稍后用 get_execution 再查。"
            )
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("workflow_id", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "工作流 ID，来自 list_workflows 的返回。")
                    })
                    add("wait_seconds", JsonObject().apply {
                        addProperty("type", "number")
                        addProperty("description", "最长等待秒数，默认 30，上限 $MAX_WAIT_SECONDS。")
                    })
                })
                add("required", JsonArray().apply { add("workflow_id") })
                addProperty("additionalProperties", false)
            })
        })

        tools.add(JsonObject().apply {
            addProperty("name", "get_execution")
            addProperty("description", "查询一次工作流执行的状态与结果。")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("execution_id", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "执行 ID，由 run_workflow 返回。")
                    })
                })
                add("required", JsonArray().apply { add("execution_id") })
                addProperty("additionalProperties", false)
            })
        })

        return JsonObject().apply { add("tools", tools) }
    }

    private fun handleToolCall(id: JsonElement?, request: JsonObject): JsonObject {
        val paramsElement = request.get("params")
        val params = if (paramsElement != null && paramsElement.isJsonObject) {
            paramsElement.asJsonObject
        } else {
            return jsonError(id, INVALID_PARAMS, "Invalid params: missing params")
        }

        val toolName = params.stringOrNull("name")
            ?: return jsonError(id, INVALID_PARAMS, "Invalid params: missing tool name")

        val argsElement = params.get("arguments")
        val args = if (argsElement != null && argsElement.isJsonObject) {
            argsElement.asJsonObject
        } else {
            JsonObject()
        }

        return when (toolName) {
            "list_workflows" -> toolResult(id, listWorkflows(args))
            "run_workflow" -> runWorkflow(id, args)
            "get_execution" -> getExecution(id, args)
            else -> jsonError(id, INVALID_PARAMS, "Unknown tool: $toolName")
        }
    }

    /**
     * 工具执行成功/失败都通过 content + isError 返回（MCP 规范约定）。
     */
    private fun toolResult(id: JsonElement?, text: String, isError: Boolean = false): JsonObject {
        return jsonResult(id, JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", text)
                })
            })
            addProperty("isError", isError)
        })
    }

    private fun listWorkflows(args: JsonObject): String {
        val query = args.stringOrNull("query")?.takeIf { it.isNotBlank() }
        val onlyEnabled = args.get("only_enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        val array = JsonArray()
        workflowManager.getAllWorkflows()
            .asSequence()
            .filter { !onlyEnabled || it.isEnabled }
            .filter { workflow ->
                if (query == null) {
                    true
                } else {
                    workflow.name.contains(query, ignoreCase = true) ||
                        workflow.description.contains(query, ignoreCase = true) ||
                        workflow.tags.any { it.contains(query, ignoreCase = true) }
                }
            }
            .sortedBy { it.name }
            .forEach { workflow ->
                array.add(JsonObject().apply {
                    addProperty("id", workflow.id)
                    addProperty("name", workflow.name)
                    addProperty("description", workflow.description)
                    addProperty("enabled", workflow.isEnabled)
                    addProperty("step_count", workflow.steps.size)
                    addProperty("manual_trigger", workflow.hasManualTrigger())
                    add("tags", JsonArray().apply { workflow.tags.forEach { add(it) } })
                })
            }

        return gson.toJson(array)
    }

    private fun runWorkflow(id: JsonElement?, args: JsonObject): JsonObject {
        val workflowId = args.stringOrNull("workflow_id")
            ?: return toolResult(id, "缺少参数 workflow_id", isError = true)

        val workflow = workflowManager.getWorkflow(workflowId)
            ?: return toolResult(id, "找不到工作流: $workflowId", isError = true)

        val waitSeconds = (args.get("wait_seconds")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 30.0)
            .toInt()
            .coerceIn(0, MAX_WAIT_SECONDS)

        val executionId = WorkflowExecutor.execute(
            workflow = workflow,
            context = workflowManager.context,
            triggerData = null,
            triggerStepId = workflow.manualTrigger()?.id
        )

        if (executionId.isEmpty()) {
            return toolResult(
                id,
                "工作流「${workflow.name}」正在运行，且重入策略为 BLOCK_NEW，本次执行被忽略。",
                isError = true
            )
        }

        val snapshot = runBlocking { awaitSnapshot(executionId, waitSeconds) }
            ?: return toolResult(
                id,
                "已启动工作流「${workflow.name}」，但未取到执行快照。execution_id: $executionId",
                isError = true
            )

        return toolResult(id, formatSnapshot(snapshot))
    }

    private suspend fun awaitSnapshot(executionId: String, waitSeconds: Int): WorkflowExecutionSnapshot? {
        val deadline = System.currentTimeMillis() + waitSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val snapshot = WorkflowExecutor.getExecutionSnapshot(executionId)
            if (snapshot != null && snapshot.status != WorkflowExecutionStatus.RUNNING) {
                return snapshot
            }
            delay(200)
        }
        return WorkflowExecutor.getExecutionSnapshot(executionId)
    }

    private fun getExecution(id: JsonElement?, args: JsonObject): JsonObject {
        val executionId = args.stringOrNull("execution_id")
            ?: return toolResult(id, "缺少参数 execution_id", isError = true)

        val snapshot = WorkflowExecutor.getExecutionSnapshot(executionId)
            ?: return toolResult(id, "找不到执行记录: $executionId", isError = true)

        return toolResult(id, formatSnapshot(snapshot))
    }

    private fun formatSnapshot(snapshot: WorkflowExecutionSnapshot): String {
        return gson.toJson(JsonObject().apply {
            addProperty("execution_id", snapshot.executionInstanceId)
            addProperty("workflow_id", snapshot.workflowId)
            addProperty("workflow_name", snapshot.workflowName)
            addProperty("status", snapshot.status.name.lowercase())
            addProperty("started_at", snapshot.startedAt)
            snapshot.finishedAt?.let { addProperty("finished_at", it) }
            snapshot.duration?.let { addProperty("duration_ms", it) }
            snapshot.returnValue?.let { addProperty("return_value", it) }
            snapshot.error?.let { addProperty("error", it) }
            if (snapshot.namedVariables.isNotEmpty()) {
                add("variables", JsonObject().apply {
                    snapshot.namedVariables.forEach { (key, value) -> addProperty(key, value) }
                })
            }
        })
    }

    // ----------------------------------------------------------------- 小工具

    private fun JsonObject.stringOrNull(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }
}
