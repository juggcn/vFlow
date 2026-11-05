package com.chaomixian.vflow.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 从服务商的模型列表接口拉取可用模型 ID。
 *
 * 各家形态：
 *  - OpenAI / DeepSeek / OpenRouter / Ollama（0.2+）：GET {base}/v1/models，Bearer 鉴权，返回 data[].id
 *  - Anthropic：GET {base}/v1/models，x-api-key + anthropic-version，返回 data[].id
 *  - Ollama 旧版 /api/tags：返回 models[].name，这里一并兼容
 */
internal object ChatModelCatalog {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetch(
        provider: ChatProvider,
        apiKey: String,
        baseUrl: String,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = ChatEndpointResolver.resolve(provider, baseUrl, ChatEndpointMode.MODELS)
            val requestBuilder = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")

            when (provider) {
                ChatProvider.ANTHROPIC -> {
                    if (apiKey.isNotBlank()) requestBuilder.header("x-api-key", apiKey)
                    requestBuilder.header("anthropic-version", "2023-06-01")
                }
                else -> {
                    if (apiKey.isNotBlank()) requestBuilder.header("Authorization", "Bearer $apiKey")
                }
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = when (response.code) {
                        401, 403 -> "鉴权失败，请检查 API Key"
                        else -> extractError(body)
                    }
                    throw IllegalStateException("HTTP ${response.code} $detail")
                }
                parseModelIds(body)
            }
        }
    }

    private fun parseModelIds(body: String): List<String> {
        if (body.isBlank()) throw IllegalStateException("服务端返回了空响应")

        val root = runCatching { JSONObject(body) }
            .getOrElse { throw IllegalStateException("响应不是合法 JSON：${body.take(120)}") }

        // OpenAI 系与 Anthropic 都是 data[]，Ollama 的 /api/tags 是 models[]
        val list = root.optJSONArray("data") ?: root.optJSONArray("models")
            ?: throw IllegalStateException("响应里没有模型列表")

        val ids = mutableListOf<String>()
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val id = item.optString("id").ifBlank { item.optString("name") }
            if (id.isNotBlank()) ids.add(id)
        }

        if (ids.isEmpty()) throw IllegalStateException("服务端没有返回任何模型")
        return ids.distinct().sorted()
    }

    private fun extractError(body: String): String {
        val message = runCatching {
            val obj = JSONObject(body)
            obj.optJSONObject("error")?.optString("message")
                ?: obj.optString("message")
        }.getOrNull().orEmpty()
        return message.ifBlank { body.take(160) }
    }
}
