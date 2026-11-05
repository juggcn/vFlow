package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.logging.DebugLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal const val CHAT_MAX_TOOL_RESULT_INPUT_CHARS = 1_600

/**
 * 流式输出的累计进度。
 * content / reasoningContent 都是"到目前为止的完整文本"，不是增量片段 —— 这样上层可以直接整体替换展示，
 * 中途降级到非流式请求时也不会出现重复拼接。
 */
internal data class ChatStreamProgress(
    val content: String = "",
    val reasoningContent: String = "",
)

internal fun stripInlineToolMarkup(content: String): String {
    return content
        .replace(Regex("(?is)<tool_call\\b[^>]*>.*?</tool_call>"), " ")
        .replace(Regex("(?is)</?function_calls?\\b[^>]*>"), " ")
        .replace(Regex("(?is)</?tool_calls?\\b[^>]*>"), " ")
        .replace(Regex("(?m)^[ \t]+$"), "")
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("""\n\s*\n+"""), "\n")
        .trim()
}

internal object ChatToolResultInputFormatter {
    fun format(
        message: ChatMessage,
        toolResult: ChatToolResult,
    ): String {
        val raw = message.content.ifBlank { toolResult.outputText }.trim()
        if (raw.length <= CHAT_MAX_TOOL_RESULT_INPUT_CHARS) return raw

        val marker = "... truncated"
        val maxArtifactBudget = (CHAT_MAX_TOOL_RESULT_INPUT_CHARS - marker.length - 2).coerceAtLeast(0)
        val artifactSection = buildArtifactSection(toolResult.artifacts, maxArtifactBudget)
        val suffix = listOf(marker, artifactSection.takeIf { it.isNotBlank() })
            .filterNotNull()
            .joinToString(separator = "\n\n")
        val headBudget = (CHAT_MAX_TOOL_RESULT_INPUT_CHARS - suffix.length - 2).coerceAtLeast(0)
        val head = raw.take(headBudget).trimEnd()

        return listOf(head.takeIf { it.isNotBlank() }, suffix)
            .filterNotNull()
            .joinToString(separator = "\n\n")
    }

    private fun buildArtifactSection(
        artifacts: List<ChatArtifactReference>,
        budget: Int,
    ): String {
        if (artifacts.isEmpty() || budget <= 0) return ""

        val lines = mutableListOf<String>()
        var remaining = budget

        fun appendLine(line: String): Boolean {
            val lineCost = if (lines.isEmpty()) line.length else line.length + 1
            if (lineCost > remaining) return false
            lines += line
            remaining -= lineCost
            return true
        }

        if (!appendLine("Artifacts:")) return ""

        artifacts.forEach { artifact ->
            val line = "- ${artifact.key} (${artifact.typeLabel}): ${artifact.handle}"
            if (!appendLine(line)) return@forEach
        }

        return lines.joinToString(separator = "\n")
    }
}

internal class ChatCompletionClient(
    private val httpClient: OkHttpClient = sharedHttpClient,
) {
    suspend fun generateReply(
        preset: ChatPresetConfig,
        history: List<ChatMessage>,
        skillSelection: ChatAgentSkillSelection = ChatAgentSkillSelection.EMPTY,
        onProgress: ((ChatStreamProgress) -> Unit)? = null,
    ): ChatCompletionResult = withContext(Dispatchers.IO) {
        DebugLogger.i(
            LOG_TAG,
            "Generating reply provider=${preset.providerEnum.storageValue} model=${preset.model} history=${history.size} tools=${skillSelection.availableTools.joinToString { it.name }} lastUser=${history.lastOrNull { it.role == ChatMessageRole.USER }?.content.orEmpty().compactForLog()}"
        )
        val request = ChatProviderRequest(
            preset = preset,
            history = history.filter { it.role != ChatMessageRole.ERROR }.let { trimHistoryForContext(it) },
            skillSelection = skillSelection,
        )
        val adapter = when (preset.providerEnum) {
            ChatProvider.OPENAI -> OpenAICompatibleChatAdapter(httpClient, preset.providerEnum)
            ChatProvider.DEEPSEEK -> OpenAICompatibleChatAdapter(httpClient, preset.providerEnum)
            ChatProvider.OPENROUTER -> OpenAICompatibleChatAdapter(httpClient, preset.providerEnum)
            ChatProvider.OLLAMA -> OpenAICompatibleChatAdapter(httpClient, preset.providerEnum)
            ChatProvider.ANTHROPIC -> AnthropicChatAdapter(httpClient)
        }
        // 优先流式；任何环节失败（服务端不支持 stream、SSE 解析异常、代理截断）都静默降级为一次性请求，
        // 保证最差情况和改造前一致，不会因为"优化"把原本能用的对话弄挂。
        val streamed = onProgress?.let { progress ->
            runCatching { adapter.completeStream(request, progress) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    DebugLogger.w(LOG_TAG, "Streaming failed, falling back to one-shot request: ${error.message}")
                    progress(ChatStreamProgress())
                }
                .getOrNull()
        }
        (streamed ?: adapter.complete(request)).also { result ->
            DebugLogger.i(
                LOG_TAG,
                "Reply ready provider=${preset.providerEnum.storageValue} model=${preset.model} tokens=${result.totalTokens ?: -1} reasoningChars=${result.reasoningContent?.length ?: 0} toolCalls=${result.toolCalls.joinToString { it.name }} content=${result.content.compactForLog()}"
            )
        }
    }

    private companion object {
        private const val LOG_TAG = "ChatCompletion"

        /** 单次请求送入模型的历史文本预算（字符数），超出后按策略裁剪。 */
        private const val CHAT_HISTORY_BUDGET_CHARS = 32_000

        /** 最近多少条工具结果保持原文，不做摘要压缩。 */
        private const val KEEP_FULL_TOOL_RESULTS = 6
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        private val sharedHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }

    private data class ChatProviderRequest(
        val preset: ChatPresetConfig,
        val history: List<ChatMessage>,
        val skillSelection: ChatAgentSkillSelection,
    )

    /** 流式下 tool_calls 会按 index 分片下发，这里按 index 累积成完整调用。 */
    private class ChatToolCallBuilder {
        var id: String? = null
        var name: String = ""
        val arguments = StringBuilder()
    }

    private interface ChatProviderAdapter {
        suspend fun complete(request: ChatProviderRequest): ChatCompletionResult

        /** 返回 null 表示该协议/模式下不支持流式，调用方会退回 [complete]。 */
        suspend fun completeStream(
            request: ChatProviderRequest,
            onProgress: (ChatStreamProgress) -> Unit,
        ): ChatCompletionResult? = null
    }

    private inner class OpenAICompatibleChatAdapter(
        private val httpClient: OkHttpClient,
        private val provider: ChatProvider,
    ) : ChatProviderAdapter {

        override suspend fun complete(request: ChatProviderRequest): ChatCompletionResult {
            val mode = if (request.preset.providerEnum == ChatProvider.OPENAI && request.preset.useResponsesApi) {
                ChatEndpointMode.RESPONSES
            } else {
                ChatEndpointMode.CHAT_COMPLETIONS
            }
            val url = ChatEndpointResolver.resolve(
                provider = provider,
                rawBaseUrl = request.preset.baseUrl,
                mode = mode,
            )
            val payload = when (mode) {
                ChatEndpointMode.CHAT_COMPLETIONS -> buildChatCompletionPayload(request)
                ChatEndpointMode.RESPONSES -> buildResponsesPayload(request)
                ChatEndpointMode.ANTHROPIC_MESSAGES,
                ChatEndpointMode.MODELS -> error("Unsupported OpenAI adapter mode")
            }
            val root = executeJsonRequest(
                url = url,
                payload = payload,
                headers = buildHeaders(request.preset),
            )
            return when (mode) {
                ChatEndpointMode.CHAT_COMPLETIONS -> parseChatCompletion(root)
                ChatEndpointMode.RESPONSES -> parseResponsesCompletion(root)
                ChatEndpointMode.ANTHROPIC_MESSAGES,
                ChatEndpointMode.MODELS -> error("Unsupported OpenAI adapter mode")
            }
        }

        private fun buildHeaders(preset: ChatPresetConfig, streaming: Boolean = false): Map<String, String> {
            val headers = linkedMapOf(
                "Content-Type" to "application/json",
                "Accept" to if (streaming) "text/event-stream" else "application/json",
            )
            if (preset.apiKey.isNotBlank() && provider != ChatProvider.OLLAMA) {
                headers["Authorization"] = "Bearer ${preset.apiKey}"
            }
            if (provider == ChatProvider.OPENROUTER) {
                headers["X-Title"] = "vFlow"
            }
            return headers
        }

        override suspend fun completeStream(
            request: ChatProviderRequest,
            onProgress: (ChatStreamProgress) -> Unit,
        ): ChatCompletionResult? {
            // Responses API 的事件流格式与对话补全差异过大，这里保守地不接管，走一次性请求。
            if (request.preset.providerEnum == ChatProvider.OPENAI && request.preset.useResponsesApi) {
                return null
            }
            val url = ChatEndpointResolver.resolve(
                provider = provider,
                rawBaseUrl = request.preset.baseUrl,
                mode = ChatEndpointMode.CHAT_COMPLETIONS,
            )
            val payload = buildChatCompletionPayload(request, stream = true)
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val toolCallBuilders = linkedMapOf<Int, ChatToolCallBuilder>()
            var totalTokens: Int? = null
            var receivedAny = false

            executeStreamingRequest(
                url = url,
                payload = payload,
                headers = buildHeaders(request.preset, streaming = true),
            ) { data ->
                val root = if (data == "[DONE]") null else parseJsonObject(data)
                if (root != null) {
                    extractServiceError(root)?.let { throw IllegalStateException(it) }
                    totalTokens = totalTokens ?: extractTotalTokens(root["usage"])
                    val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                    val delta = (choice?.get("delta") as? JsonObject)
                        ?: (choice?.get("message") as? JsonObject)
                    if (delta != null) {
                        extractTextContent(delta["content"]).takeIf { it.isNotEmpty() }?.let(content::append)
                        firstNonBlank(
                            extractTextContent(delta["reasoning_content"]),
                            extractReasoningValue(delta["reasoning"]),
                        )?.let { reasoning.append(it) }
                        appendToolCallDeltas(delta["tool_calls"], toolCallBuilders)

                        if (content.isNotEmpty() || reasoning.isNotEmpty()) {
                            receivedAny = true
                            onProgress(ChatStreamProgress(content.toString(), reasoning.toString()))
                        }
                    }
                }
            }

            if (!receivedAny && toolCallBuilders.isEmpty()) {
                // 服务端返回 200 但一条有效增量都没有，按异常处理交给上层降级。
                throw IllegalStateException("流式响应没有返回任何内容。")
            }
            val normalized = normalizeAssistantReply(
                content = content.toString(),
                reasoningContent = reasoning.toString().ifBlank { null },
            )
            return ChatCompletionResult(
                content = normalized.content,
                reasoningContent = normalized.reasoningContent,
                totalTokens = totalTokens,
                toolCalls = toolCallBuilders.entries
                    .sortedBy { it.key }
                    .mapNotNull { (_, builder) ->
                        val name = builder.name
                        if (name.isBlank()) {
                            null
                        } else {
                            ChatToolCall(
                                id = builder.id,
                                name = name,
                                argumentsJson = builder.arguments.toString().ifBlank { "{}" },
                            )
                        }
                    },
            )
        }

        private fun appendToolCallDeltas(
            element: JsonElement?,
            builders: MutableMap<Int, ChatToolCallBuilder>,
        ) {
            val array = element as? JsonArray ?: return
            array.forEachIndexed { fallbackIndex, item ->
                val obj = item as? JsonObject ?: return@forEachIndexed
                val index = obj["index"]?.jsonPrimitive?.intOrNull ?: fallbackIndex
                val builder = builders.getOrPut(index) { ChatToolCallBuilder() }
                obj["id"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { builder.id = it }
                val function = obj["function"] as? JsonObject
                function?.get("name")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { builder.name = it }
                function?.get("arguments")?.jsonPrimitive?.contentOrNull
                    ?.let { builder.arguments.append(it) }
            }
        }

        private fun buildChatCompletionPayload(
            request: ChatProviderRequest,
            stream: Boolean = false,
        ): JsonObject {
            return buildJsonObject {
                put("model", request.preset.model)
                put("temperature", request.preset.temperature)
                put("stream", stream)
                if (stream && provider != ChatProvider.OLLAMA) {
                    // 部分服务端默认不在流式响应里带 usage，显式打开；不支持时会走降级路径。
                    put("stream_options", buildJsonObject { put("include_usage", true) })
                }
                if (request.skillSelection.availableTools.isNotEmpty()) {
                    put("parallel_tool_calls", false)
                    put(
                        "tools",
                        buildJsonArray {
                            buildOpenAiChatToolDefinitions(request.skillSelection.availableTools).forEach(::add)
                        }
                    )
                }
                put(
                    "messages",
                    buildJsonArray {
                        buildChatCompletionHistoryMessages(request).forEach(::add)
                    }
                )
            }
        }

        private fun buildResponsesPayload(request: ChatProviderRequest): JsonObject {
            return buildJsonObject {
                put("model", request.preset.model)
                put("temperature", request.preset.temperature)
                put("store", false)
                if (request.skillSelection.availableTools.isNotEmpty()) {
                    put("parallel_tool_calls", false)
                    put(
                        "tools",
                        buildJsonArray {
                            buildOpenAiResponsesToolDefinitions(request.skillSelection.availableTools).forEach(::add)
                        }
                    )
                }
                put(
                    "input",
                    buildJsonArray {
                        buildResponsesInputItems(request).forEach(::add)
                    }
                )
            }
        }

        private fun buildChatCompletionHistoryMessages(request: ChatProviderRequest): List<JsonObject> {
            val items = mutableListOf<JsonObject>()
            val systemPrompt = buildSystemPrompt(request)
            if (systemPrompt.isNotBlank()) {
                items += buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
            }
            request.history.forEach { message ->
                when (message.role) {
                    ChatMessageRole.USER -> {
                        val content = message.content.trim()
                        if (content.isBlank()) return@forEach
                        items += buildJsonObject {
                            put("role", "user")
                            put("content", content)
                        }
                    }

                    ChatMessageRole.ASSISTANT -> {
                        val assistantObject = buildJsonObject {
                            put("role", "assistant")
                            if (message.content.isNotBlank()) {
                                put("content", message.content)
                            } else {
                                put("content", JsonNull)
                            }
                            if (message.toolCalls.isNotEmpty()) {
                                put(
                                    "tool_calls",
                                    buildJsonArray {
                                        message.toolCalls.forEach { toolCall ->
                                            add(
                                                buildJsonObject {
                                                    put("id", toolCall.id ?: "call_${message.id}")
                                                    put("type", "function")
                                                    put(
                                                        "function",
                                                        buildJsonObject {
                                                            put("name", toolCall.name)
                                                            put("arguments", toolCall.argumentsJson)
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        }
                        if (message.content.isNotBlank() || message.toolCalls.isNotEmpty()) {
                            items += assistantObject
                        }
                    }

                    ChatMessageRole.TOOL -> {
                        val toolResult = message.toolResult ?: return@forEach
                        val callId = toolResult.callId ?: return@forEach
                        items += buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", callId)
                            put("content", ChatToolResultInputFormatter.format(message, toolResult))
                        }
                    }

                    ChatMessageRole.ERROR -> Unit
                }
            }
            return items
        }

        private fun buildResponsesInputItems(request: ChatProviderRequest): List<JsonObject> {
            val items = mutableListOf<JsonObject>()
            val systemPrompt = buildSystemPrompt(request)
            if (systemPrompt.isNotBlank()) {
                items += buildMessageInput(role = "system", text = systemPrompt)
            }
            request.history.forEach { message ->
                when (message.role) {
                    ChatMessageRole.USER -> {
                        val content = message.content.trim()
                        if (content.isBlank()) return@forEach
                        items += buildMessageInput(role = "user", text = content)
                    }

                    ChatMessageRole.ASSISTANT -> {
                        val content = message.content.trim()
                        if (content.isNotBlank()) {
                            items += buildMessageInput(role = "assistant", text = content)
                        }
                        message.toolCalls.forEach { toolCall ->
                            items += buildJsonObject {
                                put("type", "function_call")
                                put("call_id", toolCall.id ?: "call_${message.id}")
                                put("name", toolCall.name)
                                put("arguments", toolCall.argumentsJson)
                            }
                        }
                    }

                    ChatMessageRole.TOOL -> {
                        val toolResult = message.toolResult ?: return@forEach
                        val callId = toolResult.callId ?: return@forEach
                        items += buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", callId)
                            put("output", ChatToolResultInputFormatter.format(message, toolResult))
                        }
                    }

                    ChatMessageRole.ERROR -> Unit
                }
            }
            return items
        }

        private fun parseChatCompletion(root: JsonObject): ChatCompletionResult {
            val payload = unwrapPayload(root, expectedKey = "choices")
            extractServiceError(payload)?.let { throw IllegalStateException(it) }
            val choice = payload["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw IllegalStateException("模型没有返回有效的 choices。")
            val message = choice["message"]?.jsonObject
                ?: throw IllegalStateException("模型没有返回有效的 message。")
            val normalized = normalizeAssistantReply(
                content = extractTextContent(message["content"]),
                reasoningContent = firstNonBlank(
                    extractTextContent(message["reasoning_content"]),
                    extractReasoningValue(message["reasoning"]),
                    extractReasoningFromContentArray(message["content"]),
                ),
            )
            return ChatCompletionResult(
                content = normalized.content,
                reasoningContent = normalized.reasoningContent,
                totalTokens = extractTotalTokens(payload["usage"]),
                toolCalls = parseOpenAiToolCalls(message["tool_calls"]),
            )
        }

        private fun parseResponsesCompletion(root: JsonObject): ChatCompletionResult {
            val payload = unwrapPayload(root, expectedKey = "output")
            extractServiceError(payload)?.let { throw IllegalStateException(it) }
            val textChunks = mutableListOf<String>()
            val reasoningChunks = mutableListOf<String>()
            val toolCalls = mutableListOf<ChatToolCall>()

            payload["output_text"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let(textChunks::add)

            payload["output"]?.jsonArray?.forEach { item ->
                val obj = item.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "message" -> {
                        obj["content"]?.jsonArray?.forEach { part ->
                            val partObject = part.jsonObject
                            when (partObject["type"]?.jsonPrimitive?.contentOrNull) {
                                "output_text", "text" -> partObject["text"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(textChunks::add)
                                "reasoning_text" -> partObject["text"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(reasoningChunks::add)
                            }
                        }
                    }

                    "reasoning" -> {
                        obj["summary"]?.jsonArray?.forEach { summary ->
                            summary.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }
                                ?.let(reasoningChunks::add)
                        }
                        obj["content"]?.jsonArray?.forEach { part ->
                            part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }
                                ?.let(reasoningChunks::add)
                        }
                    }

                    "function_call" -> {
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (name.isNotBlank()) {
                            toolCalls += ChatToolCall(
                                id = obj["call_id"]?.jsonPrimitive?.contentOrNull
                                    ?: obj["id"]?.jsonPrimitive?.contentOrNull,
                                name = name,
                                argumentsJson = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                            )
                        }
                    }
                }
            }

            val normalized = normalizeAssistantReply(
                content = textChunks.joinToString(separator = "").trim(),
                reasoningContent = reasoningChunks.joinToString(separator = "\n\n").trim().ifBlank { null },
            )
            return ChatCompletionResult(
                content = normalized.content,
                reasoningContent = normalized.reasoningContent,
                totalTokens = extractResponsesTotalTokens(payload["usage"]),
                toolCalls = toolCalls,
            )
        }

        private fun parseOpenAiToolCalls(element: JsonElement?): List<ChatToolCall> {
            val toolCalls = element as? JsonArray ?: return emptyList()
            return toolCalls.mapNotNull { item ->
                val obj = item.jsonObject
                val function = obj["function"]?.jsonObject ?: return@mapNotNull null
                val name = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (name.isBlank()) return@mapNotNull null
                ChatToolCall(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull,
                    name = name,
                    argumentsJson = function["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                )
            }
        }
    }

    private inner class AnthropicChatAdapter(
        private val httpClient: OkHttpClient,
    ) : ChatProviderAdapter {

        override suspend fun complete(request: ChatProviderRequest): ChatCompletionResult {
            val root = executeJsonRequest(
                url = resolveUrl(request),
                payload = buildPayload(request, stream = false),
                headers = buildHeaders(request, streaming = false),
            )
            extractServiceError(root)?.let { throw IllegalStateException(it) }

            val textChunks = mutableListOf<String>()
            val reasoningChunks = mutableListOf<String>()
            val toolCalls = mutableListOf<ChatToolCall>()
            root["content"]?.jsonArray?.forEach { item ->
                val obj = item.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let(textChunks::add)
                    "thinking" -> obj["thinking"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let(reasoningChunks::add)
                    "tool_use" -> {
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (name.isNotBlank()) {
                            toolCalls += ChatToolCall(
                                id = obj["id"]?.jsonPrimitive?.contentOrNull,
                                name = name,
                                argumentsJson = obj["input"]?.toString() ?: "{}",
                            )
                        }
                    }
                }
            }

            val normalized = normalizeAssistantReply(
                content = textChunks.joinToString(separator = "").trim(),
                reasoningContent = reasoningChunks.joinToString(separator = "\n\n").trim().ifBlank { null },
            )
            return ChatCompletionResult(
                content = normalized.content,
                reasoningContent = normalized.reasoningContent,
                totalTokens = extractAnthropicTotalTokens(root["usage"]),
                toolCalls = toolCalls,
            )
        }

        override suspend fun completeStream(
            request: ChatProviderRequest,
            onProgress: (ChatStreamProgress) -> Unit,
        ): ChatCompletionResult? {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val toolCallBuilders = linkedMapOf<Int, ChatToolCallBuilder>()
            var inputTokens: Int? = null
            var outputTokens: Int? = null
            var receivedAny = false

            executeStreamingRequest(
                url = resolveUrl(request),
                payload = buildPayload(request, stream = true),
                headers = buildHeaders(request, streaming = true),
            ) { data ->
                val root = parseJsonObject(data)
                if (root != null) {
                    when (root["type"]?.jsonPrimitive?.contentOrNull) {
                        "error" -> {
                            val message = (root["error"] as? JsonObject)
                                ?.get("message")?.jsonPrimitive?.contentOrNull
                                ?: "Anthropic 流式响应返回错误。"
                            throw IllegalStateException(message)
                        }

                        "message_start" -> {
                            inputTokens = extractAnthropicTotalTokens(
                                (root["message"] as? JsonObject)?.get("usage")
                            )
                        }

                        "content_block_start" -> {
                            val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                            val block = root["content_block"] as? JsonObject
                            if (block != null && block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                                val builder = toolCallBuilders.getOrPut(index) { ChatToolCallBuilder() }
                                block["id"]?.jsonPrimitive?.contentOrNull?.let { builder.id = it }
                                block["name"]?.jsonPrimitive?.contentOrNull?.let { builder.name = it }
                            }
                        }

                        "content_block_delta" -> {
                            val delta = root["delta"] as? JsonObject
                            when (delta?.get("type")?.jsonPrimitive?.contentOrNull) {
                                "text_delta" -> delta?.get("text")?.jsonPrimitive?.contentOrNull
                                    ?.let(content::append)
                                "thinking_delta" -> delta?.get("thinking")?.jsonPrimitive?.contentOrNull
                                    ?.let(reasoning::append)
                                "input_json_delta" -> {
                                    val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                                    val builder = toolCallBuilders.getOrPut(index) { ChatToolCallBuilder() }
                                    delta?.get("partial_json")?.jsonPrimitive?.contentOrNull
                                        ?.let { builder.arguments.append(it) }
                                }
                            }
                            if (content.isNotEmpty() || reasoning.isNotEmpty()) {
                                receivedAny = true
                                onProgress(ChatStreamProgress(content.toString(), reasoning.toString()))
                            }
                        }

                        "message_delta" -> {
                            (root["usage"] as? JsonObject)
                                ?.get("output_tokens")?.jsonPrimitive?.intOrNull
                                ?.let { outputTokens = it }
                        }
                    }
                }
            }

            if (!receivedAny && toolCallBuilders.isEmpty()) {
                throw IllegalStateException("流式响应没有返回任何内容。")
            }
            val normalized = normalizeAssistantReply(
                content = content.toString(),
                reasoningContent = reasoning.toString().ifBlank { null },
            )
            return ChatCompletionResult(
                content = normalized.content,
                reasoningContent = normalized.reasoningContent,
                totalTokens = listOfNotNull(inputTokens, outputTokens).takeIf { it.isNotEmpty() }?.sum(),
                toolCalls = toolCallBuilders.entries
                    .sortedBy { it.key }
                    .mapNotNull { (_, builder) ->
                        val name = builder.name
                        if (name.isBlank()) {
                            null
                        } else {
                            ChatToolCall(
                                id = builder.id,
                                name = name,
                                argumentsJson = builder.arguments.toString().ifBlank { "{}" },
                            )
                        }
                    },
            )
        }

        private fun resolveUrl(request: ChatProviderRequest): String {
            return ChatEndpointResolver.resolve(
                provider = ChatProvider.ANTHROPIC,
                rawBaseUrl = request.preset.baseUrl,
                mode = ChatEndpointMode.ANTHROPIC_MESSAGES,
            )
        }

        private fun buildHeaders(
            request: ChatProviderRequest,
            streaming: Boolean,
        ): Map<String, String> {
            return mapOf(
                "Content-Type" to "application/json",
                "Accept" to if (streaming) "text/event-stream" else "application/json",
                "x-api-key" to request.preset.apiKey,
                "anthropic-version" to "2023-06-01",
            )
        }

        private fun buildPayload(
            request: ChatProviderRequest,
            stream: Boolean,
        ): JsonObject {
            return buildJsonObject {
                put("model", request.preset.model)
                put("max_tokens", 4096)
                put("temperature", request.preset.temperature)
                put("system", buildSystemPrompt(request))
                if (stream) {
                    put("stream", true)
                }
                if (request.skillSelection.availableTools.isNotEmpty()) {
                    put(
                        "tools",
                        buildJsonArray {
                            buildAnthropicToolDefinitions(request.skillSelection.availableTools).forEach(::add)
                        }
                    )
                }
                put(
                    "messages",
                    buildJsonArray {
                        buildAnthropicHistoryMessages(request).forEach(::add)
                    }
                )
            }
        }
    }

    private fun executeJsonRequest(
        url: String,
        payload: JsonObject,
        headers: Map<String, String>,
    ): JsonObject {
        DebugLogger.d(
            LOG_TAG,
            "HTTP request url=$url payloadChars=${payload.toString().length} headers=${headers.keys.joinToString()}"
        )
        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMediaType))
        headers.forEach { (name, value) ->
            if (value.isNotBlank()) {
                requestBuilder.header(name, value)
            }
        }
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val rawBody = response.body?.string().orEmpty().trim()
            val root = parseJsonObject(rawBody)
            val errorMessage = root?.let(::extractServiceError)
            DebugLogger.d(
                LOG_TAG,
                "HTTP response url=$url code=${response.code} bodyChars=${rawBody.length} error=${errorMessage ?: "none"}"
            )
            if (!response.isSuccessful) {
                val detail = errorMessage ?: rawBody.ifBlank { "HTTP ${response.code}" }
                throw ChatHttpException(response.code, detail)
            }
            if (root == null) {
                throw IllegalStateException("服务返回了无法解析的 JSON 响应。")
            }
            errorMessage?.let { throw IllegalStateException(it) }
            return root
        }
    }

    /**
     * 发起 SSE（text/event-stream）请求，把每个 `data:` 行的内容交给 [onData]。
     * 非 2xx 直接抛异常，由上层决定是否降级为一次性请求。
     */
    private suspend fun executeStreamingRequest(
        url: String,
        payload: JsonObject,
        headers: Map<String, String>,
        onData: (String) -> Unit,
    ) {
        DebugLogger.d(LOG_TAG, "HTTP stream request url=$url payloadChars=${payload.toString().length}")
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
            headers.forEach { (name, value) ->
                if (value.isNotBlank()) {
                    requestBuilder.header(name, value)
                }
            }
            val call = httpClient.newCall(requestBuilder.build())
            // 用户点"停止"时会取消协程；这里主动挂掉 socket，避免 readLine 卡住直到超时。
            val job = coroutineContext[Job]
            val cancellationHandle = job?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val rawBody = response.body?.string().orEmpty().trim()
                        val detail = parseJsonObject(rawBody)?.let(::extractServiceError)
                            ?: rawBody.ifBlank { "HTTP ${response.code}" }
                        throw ChatHttpException(response.code, detail)
                    }
                    val body = response.body ?: throw IllegalStateException("服务返回了空响应。")
                    body.source().use { source ->
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data.isEmpty()) continue
                            onData(data)
                        }
                    }
                }
            } catch (io: java.io.IOException) {
                // 取消导致的 IO 异常要还原成 CancellationException，否则会当成网络错误弹给用户。
                if (job?.isActive == false) {
                    DebugLogger.i(LOG_TAG, "Stream canceled by user: ${io.message}")
                    throw CancellationException("请求已取消")
                }
                throw io
            } finally {
                cancellationHandle?.dispose()
            }
        }
    }

    /**
     * 长对话的上下文预算控制。
     * 1) 先给"较旧的工具结果"瘦身，只保留摘要 —— 这些通常是最占体积、又最不需要原文的部分；
     * 2) 仍然超预算就丢掉最早的消息，并且不让历史以孤立的工具结果开头（多数服务端会因此报错）。
     */
    private fun trimHistoryForContext(
        history: List<ChatMessage>,
        budgetChars: Int = CHAT_HISTORY_BUDGET_CHARS,
    ): List<ChatMessage> {
        if (history.isEmpty()) return history
        var trimmed = history
        var totalChars = history.sumOf { it.estimatedChars() }
        if (totalChars > budgetChars) {
            val protectFrom = (history.size - KEEP_FULL_TOOL_RESULTS).coerceAtLeast(0)
            trimmed = history.mapIndexed { index, message ->
                if (index >= protectFrom || message.role != ChatMessageRole.TOOL) {
                    message
                } else {
                    val summary = message.toolResult?.summary?.takeIf { it.isNotBlank() } ?: return@mapIndexed message
                    message.copy(content = "[较早的工具结果，仅保留摘要] $summary")
                }
            }
            totalChars = trimmed.sumOf { it.estimatedChars() }
        }
        if (totalChars <= budgetChars) {
            return if (trimmed.size == history.size) history else trimmed
        }

        val kept = ArrayDeque<ChatMessage>()
        var used = 0
        for (message in trimmed.asReversed()) {
            val cost = message.estimatedChars()
            if (kept.isNotEmpty() && used + cost > budgetChars) break
            kept.addFirst(message)
            used += cost
        }
        while (kept.isNotEmpty() && kept.first().role == ChatMessageRole.TOOL) {
            kept.removeFirst()
        }
        DebugLogger.i(
            LOG_TAG,
            "History trimmed for context: ${history.size} -> ${kept.size} messages, chars $totalChars -> $used"
        )
        return kept.toList()
    }

    private fun ChatMessage.estimatedChars(): Int {
        return content.length + (reasoningContent?.length ?: 0) + toolCalls.sumOf { it.argumentsJson.length }
    }

    private fun parseJsonObject(rawBody: String): JsonObject? {
        if (rawBody.isBlank()) return null
        val element = runCatching { json.parseToJsonElement(rawBody) }.getOrNull() ?: return null
        return element as? JsonObject
    }

    private fun unwrapPayload(root: JsonObject, expectedKey: String): JsonObject {
        if (root.containsKey(expectedKey)) return root
        val nested = root["data"] as? JsonObject
        if (nested != null && nested.containsKey(expectedKey)) {
            return nested
        }
        return root
    }

    private fun extractServiceError(root: JsonObject): String? {
        val error = root["error"]
        if (error != null && error !is JsonNull) {
            return when (error) {
                is JsonObject -> {
                    val message = error["message"]?.jsonPrimitive?.contentOrNull
                        ?: error["msg"]?.jsonPrimitive?.contentOrNull
                        ?: error["detail"]?.jsonPrimitive?.contentOrNull
                    val type = error["type"]?.jsonPrimitive?.contentOrNull
                    if (message.isNullOrBlank()) {
                        type
                    } else if (type.isNullOrBlank()) {
                        message
                    } else {
                        "$message [$type]"
                    }
                }

                is JsonPrimitive -> error.contentOrNull
                else -> null
            }
        }
        val type = root["type"]?.jsonPrimitive?.contentOrNull
        val topLevelMessage = root["message"]?.jsonPrimitive?.contentOrNull
            ?: root["msg"]?.jsonPrimitive?.contentOrNull
            ?: root["detail"]?.jsonPrimitive?.contentOrNull
        return if (type == "error" && !topLevelMessage.isNullOrBlank()) {
            topLevelMessage
        } else {
            topLevelMessage
        }
    }

    private fun extractTextContent(element: JsonElement?): String {
        return when (element) {
            null, JsonNull -> ""
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            is JsonArray -> element.joinToString(separator = "") { part ->
                val obj = part as? JsonObject ?: return@joinToString ""
                obj["text"]?.jsonPrimitive?.contentOrNull
                    ?: obj["content"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            }
            is JsonObject -> {
                element["text"]?.jsonPrimitive?.contentOrNull
                    ?: element["content"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            }
            else -> ""
        }
    }

    private fun extractReasoningFromContentArray(element: JsonElement?): String? {
        val array = element as? JsonArray ?: return null
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "reasoning_text") {
                obj["text"]?.jsonPrimitive?.contentOrNull
            } else {
                null
            }
        }.joinToString(separator = "\n\n").trim().ifBlank { null }
    }

    private fun extractReasoningValue(element: JsonElement?): String? {
        return when (element) {
            null, JsonNull -> null
            is JsonPrimitive -> element.contentOrNull?.trim()?.ifBlank { null }
            is JsonArray -> element.joinToString(separator = "\n\n") { extractTextContent(it) }
                .trim()
                .ifBlank { null }
            is JsonObject -> {
                val summary = (element["summary"] as? JsonArray)
                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    ?.joinToString(separator = "\n\n")
                    ?.trim()
                    .orEmpty()
                val content = extractTextContent(element["content"])
                firstNonBlank(summary, content)
            }
            else -> null
        }
    }

    private fun extractTotalTokens(element: JsonElement?): Int? {
        val usage = element as? JsonObject ?: return null
        return usage["total_tokens"]?.jsonPrimitive?.intOrNull
            ?: listOf(
                usage["prompt_tokens"]?.jsonPrimitive?.intOrNull,
                usage["completion_tokens"]?.jsonPrimitive?.intOrNull,
            ).filterNotNull().takeIf { it.isNotEmpty() }?.sum()
    }

    private fun extractResponsesTotalTokens(element: JsonElement?): Int? {
        val usage = element as? JsonObject ?: return null
        return usage["total_tokens"]?.jsonPrimitive?.intOrNull
            ?: listOf(
                usage["input_tokens"]?.jsonPrimitive?.intOrNull,
                usage["output_tokens"]?.jsonPrimitive?.intOrNull,
                usage["prompt_tokens"]?.jsonPrimitive?.intOrNull,
                usage["completion_tokens"]?.jsonPrimitive?.intOrNull,
            ).filterNotNull().takeIf { it.isNotEmpty() }?.sum()
    }

    private fun extractAnthropicTotalTokens(element: JsonElement?): Int? {
        val usage = element as? JsonObject ?: return null
        return listOf(
            usage["input_tokens"]?.jsonPrimitive?.intOrNull,
            usage["output_tokens"]?.jsonPrimitive?.intOrNull,
        ).filterNotNull().takeIf { it.isNotEmpty() }?.sum()
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun buildSystemPrompt(request: ChatProviderRequest): String {
        return ChatAgentSkillRouter.buildSystemPrompt(
            basePrompt = request.preset.systemPrompt,
            skillSelection = request.skillSelection,
        )
    }

    private fun buildMessageInput(role: String, text: String): JsonObject {
        return buildJsonObject {
            put("type", "message")
            put("role", role)
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "input_text")
                            put("text", text)
                        }
                    )
                }
            )
        }
    }

    private fun buildOpenAiChatToolDefinitions(
        tools: List<ChatAgentToolDefinition>,
    ): List<JsonObject> {
        return tools.map { tool ->
            buildJsonObject {
                put("type", "function")
                put(
                    "function",
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.inputSchema)
                    }
                )
            }
        }
    }

    private fun buildOpenAiResponsesToolDefinitions(
        tools: List<ChatAgentToolDefinition>,
    ): List<JsonObject> {
        return tools.map { tool ->
            buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.inputSchema)
            }
        }
    }

    private fun buildAnthropicToolDefinitions(
        tools: List<ChatAgentToolDefinition>,
    ): List<JsonObject> {
        return tools.map { tool ->
            buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", tool.inputSchema)
                put("strict", true)
            }
        }
    }

    private fun buildAnthropicHistoryMessages(request: ChatProviderRequest): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        request.history.forEach { message ->
            when (message.role) {
                ChatMessageRole.USER -> {
                    val content = message.content.trim()
                    if (content.isBlank()) return@forEach
                    items += buildJsonObject {
                        put("role", "user")
                        put("content", content)
                    }
                }

                ChatMessageRole.ASSISTANT -> {
                    val contentBlocks = buildJsonArray {
                        message.content.trim().takeIf { it.isNotBlank() }?.let { content ->
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", content)
                                }
                            )
                        }
                        message.toolCalls.forEach { toolCall ->
                            add(
                                buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", toolCall.id ?: "call_${message.id}")
                                    put("name", toolCall.name)
                                    put(
                                        "input",
                                        runCatching { json.parseToJsonElement(toolCall.argumentsJson) }
                                            .getOrNull() ?: buildJsonObject { }
                                    )
                                }
                            )
                        }
                    }
                    if (contentBlocks.isNotEmpty()) {
                        items += buildJsonObject {
                            put("role", "assistant")
                            put("content", contentBlocks)
                        }
                    }
                }

                ChatMessageRole.TOOL -> {
                    val toolResult = message.toolResult ?: return@forEach
                    val callId = toolResult.callId ?: return@forEach
                    items += buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "tool_result")
                                        put("tool_use_id", callId)
                                        put("content", ChatToolResultInputFormatter.format(message, toolResult))
                                        put("is_error", toolResult.status != ChatToolResultStatus.SUCCESS)
                                    }
                                )
                            }
                        )
                    }
                }

                ChatMessageRole.ERROR -> Unit
            }
        }
        return items
    }

    private fun normalizeAssistantReply(
        content: String,
        reasoningContent: String?,
    ): ChatCompletionResult {
        val thinkRegex = Regex("(?is)<(?:think|thinking)>(.*?)</(?:think|thinking)>")
        val matches = thinkRegex.findAll(content).toList()
        val inlineReasoning = matches.joinToString(separator = "\n\n") { it.groupValues[1].trim() }
            .trim()
            .ifBlank { null }
        val visibleContent = if (matches.isEmpty()) {
            stripInlineToolMarkup(content)
        } else {
            stripInlineToolMarkup(thinkRegex.replace(content, ""))
        }
        val mergedReasoning = firstNonBlank(reasoningContent, inlineReasoning)
        val normalizedContent = when {
            visibleContent.isNotBlank() -> visibleContent
            mergedReasoning != null -> ""
            else -> content.trim()
        }
        return ChatCompletionResult(
            content = normalizedContent,
            reasoningContent = mergedReasoning,
            totalTokens = null,
        )
    }

    private fun String.compactForLog(maxLength: Int = 180): String {
        val compact = replace(Regex("""\s+"""), " ").trim()
        return if (compact.length > maxLength) compact.take(maxLength) + "…" else compact
    }
}
