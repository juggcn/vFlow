package com.chaomixian.vflow.ui.chat

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 带状态码的 HTTP 失败，便于把错误映射成可读文案而不是把原始 body 甩给用户。 */
internal class ChatHttpException(
    val statusCode: Int,
    val detail: String,
) : IllegalStateException("Status code: $statusCode\nError body:\n$detail")

/**
 * 把底层异常翻译成"原因 + 怎么办"，并标记是否值得重试。
 * 原始信息仍然保留在 [FriendlyChatError.rawDetail] 里，方便排查，只是不再占据正文。
 */
internal data class FriendlyChatError(
    val summary: String,
    val hint: String?,
    val retryable: Boolean,
    val rawDetail: String? = null,
) {
    fun toMessageContent(): String {
        return buildString {
            append("**")
            append(summary)
            append("**")
            if (!hint.isNullOrBlank()) {
                append("\n\n")
                append(hint)
            }
            if (!rawDetail.isNullOrBlank()) {
                append("\n\n")
                append("`")
                append(rawDetail.lineSequence().joinToString(" ").take(160))
                append("`")
            }
        }
    }
}

internal object ChatErrorMapper {

    fun describe(throwable: Throwable, providerLabel: String): FriendlyChatError {
        val raw = throwable.message?.trim().orEmpty().ifBlank { throwable::class.java.simpleName }
        return when (throwable) {
            is ChatHttpException -> describeHttp(throwable, providerLabel, raw)
            is UnknownHostException, is ConnectException -> FriendlyChatError(
                summary = "连不上 $providerLabel 的服务器",
                hint = "检查网络是否可用；如果用的是自建或中转地址，确认设置里的接口地址填写正确、服务确实在运行。",
                retryable = true,
                rawDetail = raw,
            )

            is SocketTimeoutException, is InterruptedIOException -> FriendlyChatError(
                summary = "请求超时了",
                hint = "网络较慢或模型响应时间过长。可以直接重试，或换一个响应更快的模型。",
                retryable = true,
                rawDetail = raw,
            )

            is SSLException -> FriendlyChatError(
                summary = "安全连接建立失败",
                hint = "证书校验没通过。如果是中转或自建服务，确认地址使用 https 且证书有效。",
                retryable = false,
                rawDetail = raw,
            )

            else -> FriendlyChatError(
                summary = "请求失败",
                hint = "可以直接重试；如果反复失败，检查设置里的模型配置。",
                retryable = true,
                rawDetail = raw,
            )
        }
    }

    private fun describeHttp(
        error: ChatHttpException,
        providerLabel: String,
        raw: String,
    ): FriendlyChatError {
        val detail = error.detail.trim()
        val lowerDetail = detail.lowercase()
        return when (error.statusCode) {
            400, 422 -> when {
                lowerDetail.contains("context") ||
                    lowerDetail.contains("too long") ||
                    lowerDetail.contains("maximum") ||
                    lowerDetail.contains("token") -> FriendlyChatError(
                    summary = "对话太长了，超出模型的上下文上限",
                    hint = "新建一个对话继续，或者把当前任务拆成几步来做。",
                    retryable = false,
                    rawDetail = raw,
                )

                lowerDetail.contains("tool") || lowerDetail.contains("function") -> FriendlyChatError(
                    summary = "模型不接受这次的请求格式",
                    hint = "多半是该模型不支持工具调用。可以在设置里换一个支持 function calling 的模型再试。",
                    retryable = false,
                    rawDetail = raw,
                )

                else -> FriendlyChatError(
                    summary = "请求参数被 $providerLabel 拒绝",
                    hint = "通常是模型名写错，或该模型不支持当前参数（例如温度、工具调用）。",
                    retryable = false,
                    rawDetail = raw,
                )
            }

            401, 403 -> FriendlyChatError(
                summary = "$providerLabel 拒绝了这个 API Key",
                hint = "检查设置里的 Key 是否填写正确、是否已过期，以及账户余额是否充足。",
                retryable = false,
                rawDetail = raw,
            )

            404 -> FriendlyChatError(
                summary = "找不到对应的接口或模型",
                hint = "确认模型名称拼写正确；如果是自建服务，检查接口地址是否带了多余的路径。",
                retryable = false,
                rawDetail = raw,
            )

            408, 409, 425 -> FriendlyChatError(
                summary = "服务端暂时没能处理这次请求",
                hint = "稍等几秒重试即可。",
                retryable = true,
                rawDetail = raw,
            )

            413 -> FriendlyChatError(
                summary = "请求内容过大",
                hint = "清空或新建对话后重试。",
                retryable = false,
                rawDetail = raw,
            )

            429 -> FriendlyChatError(
                summary = "请求太频繁或额度用完了",
                hint = "等几十秒再试；如果持续出现，检查账户额度或当前模型的速率限制。",
                retryable = true,
                rawDetail = raw,
            )

            in 500..599 -> FriendlyChatError(
                summary = "$providerLabel 服务端出错了（${error.statusCode}）",
                hint = "这是对方服务的问题，稍后重试通常就能恢复。",
                retryable = true,
                rawDetail = raw,
            )

            else -> FriendlyChatError(
                summary = "请求失败（HTTP ${error.statusCode}）",
                hint = "可以重试；持续失败的话检查模型配置。",
                retryable = error.statusCode >= 500,
                rawDetail = raw,
            )
        }
    }
}
