// 文件: main/java/com/chaomixian/vflow/core/workflow/module/network/BotPushModule.kt
// 描述: 机器人推送。一个步骤里可同时启用多条通道，逐条发送后汇总结果：
//      微信（扫码绑定个人号）、QQ 官方机器人（AppID + AppSecret）。
//      凭证按需填写，access_token 在进程内缓存，失效时自动重取一次。

package com.chaomixian.vflow.core.workflow.module.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.InputStyle
import com.chaomixian.vflow.core.module.InputVisibility
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.module.ValidationResult
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 机器人推送。
 *
 * 两条通道，各自独立开关，一次执行可以同时发：
 *   微信个人号      扫码绑定，走官方 ClawBot（ilink）接口，只支持单聊
 *   QQ 官方机器人   AppID + AppSecret，会话 ID 靠编辑器里的「QQ 握手」按钮绑定
 *
 * 微信通道先用「微信扫码绑定」拿 bot_token，之后走 sendmessage；
 * 它必须带 context_token，而 context_token 只在你给机器人发过消息后才有，所以绑定流程会多等一条消息。
 */
class BotPushModule : BaseModule() {

    companion object {
        const val CHANNEL_WECHAT = "微信（扫码）"
        const val CHANNEL_QQ = "QQ 官方机器人"

        private const val FORMAT_TEXT = "text"
        private const val FORMAT_MARKDOWN = "markdown"

        /** QQ 消息目标：群 / 单聊 / 频道。 */
        private const val TARGET_GROUP = "group"
        private const val TARGET_C2C = "c2c"
        private const val TARGET_CHANNEL = "channel"

        private const val DEFAULT_TIMEOUT = 15L

        /** QQ 握手记下的会话 ID 存这里，key 按 AppID 区分。 */
        private const val BOT_PREFS = "vflow_bot_push"

        private const val QQ_TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken"
        private const val QQ_API_BASE = "https://api.sgroup.qq.com"

        /** 微信 ClawBot（ilink）接口，扫码登录 + 长轮询收消息。 */
        private const val WECHAT_API_BASE = "https://ilinkai.weixin.qq.com"
        private const val WECHAT_QR_URL = "$WECHAT_API_BASE/ilink/bot/get_bot_qrcode?bot_type=3"
        private const val WECHAT_QR_STATUS_URL = "$WECHAT_API_BASE/ilink/bot/get_qrcode_status"
        private const val WECHAT_SEND_URL = "$WECHAT_API_BASE/ilink/bot/sendmessage"
        private const val WECHAT_UPDATES_URL = "$WECHAT_API_BASE/ilink/bot/getupdates"
        private const val WECHAT_CHANNEL_VERSION = "1.0.3"

        private const val KEY_WECHAT_TOKEN = "wechat_bot_token"
        private const val KEY_WECHAT_BOT = "wechat_bot_id"
        private const val KEY_WECHAT_USER = "wechat_user_id"
        private const val KEY_WECHAT_CONTEXT = "wechat_context_token"
    }

    override val id = "vflow.network.bot_push"

    /** 通道选择器 + 分组卡片的编辑界面，见 BotPushModuleUIProvider。 */
    override val uiProvider: ModuleUIProvider = BotPushModuleUIProvider(this)

    override val metadata = ActionMetadata(
        name = "机器人推送",
        description = "推送到微信（扫码绑定）或 QQ 官方机器人，通道各自开关，凭证按通道填",
        iconRes = R.drawable.rounded_sms_24,
        category = "网络",
        categoryId = "network"
    )

    // ---------------- 输入 ----------------

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = "title",
            name = "标题",
            staticType = ParameterType.STRING,
            defaultValue = "",
            supportsRichText = true,
            hint = "可留空；填了会拼在正文前面（Markdown 下作为标题行）"
        ),
        InputDefinition(
            id = "content",
            name = "消息内容",
            staticType = ParameterType.STRING,
            defaultValue = "",
            supportsRichText = true
        ),
        InputDefinition(
            id = "format",
            name = "格式",
            staticType = ParameterType.ENUM,
            defaultValue = FORMAT_TEXT,
            options = listOf(FORMAT_TEXT, FORMAT_MARKDOWN),
            acceptsMagicVariable = false,
            legacyValueMap = mapOf("文本" to FORMAT_TEXT, "Markdown" to FORMAT_MARKDOWN)
        ),

        // ---- QQ 官方机器人 ----
        InputDefinition(
            id = "enable_qq",
            name = "QQ 官方机器人",
            staticType = ParameterType.BOOLEAN,
            defaultValue = false,
            acceptsMagicVariable = false,
            inputStyle = InputStyle.SWITCH
        ),
        InputDefinition(
            id = "qq_app_id",
            name = "QQ AppID",
            staticType = ParameterType.STRING,
            defaultValue = "",
            supportsRichText = true,
            visibility = InputVisibility.whenTrue("enable_qq"),
            hint = "q.qq.com 开放平台 → 机器人 → 开发设置"
        ),
        InputDefinition(
            id = "qq_app_secret",
            name = "QQ AppSecret",
            staticType = ParameterType.STRING,
            defaultValue = "",
            supportsRichText = true,
            visibility = InputVisibility.whenTrue("enable_qq"),
            hint = "开放平台的机器人密钥，不是 Token"
        ),
        InputDefinition(
            id = "qq_handshake_timeout",
            name = "握手等待(秒)",
            staticType = ParameterType.NUMBER,
            defaultValue = 60,
            isFolded = true,
            visibility = InputVisibility.whenTrue("enable_qq"),
            hint = "点「QQ 握手」后等第一条消息的时长"
        ),
        InputDefinition(
            id = "qq_msg_id",
            name = "被动回复消息 ID",
            staticType = ParameterType.STRING,
            defaultValue = "",
            supportsRichText = true,
            isFolded = true,
            visibility = InputVisibility.whenTrue("enable_qq"),
            hint = "留空为主动消息，受平台主动推送额度限制；填了则作为被动回复"
        ),

        // ---- 微信个人号（扫码）----
        InputDefinition(
            id = "enable_wechat",
            name = "微信（扫码）",
            staticType = ParameterType.BOOLEAN,
            defaultValue = false,
            acceptsMagicVariable = false,
            inputStyle = InputStyle.SWITCH,
            hint = "点卡片里的「微信扫码绑定」，用手机微信扫一下即可；只能发单聊，服务端约 7 条/5 分钟"
        )
    ) + moduleProxyInputDefinitions() + listOf(
        InputDefinition(
            id = "timeout",
            name = "超时(秒)",
            staticType = ParameterType.NUMBER,
            defaultValue = DEFAULT_TIMEOUT,
            isFolded = true
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition("success", "是否全部成功", VTypeRegistry.BOOLEAN.id),
        OutputDefinition("success_count", "成功通道数", VTypeRegistry.NUMBER.id),
        OutputDefinition("failure_count", "失败通道数", VTypeRegistry.NUMBER.id),
        OutputDefinition("sent_channels", "已发送通道", VTypeRegistry.STRING.id),
        OutputDefinition("failed_channels", "失败通道", VTypeRegistry.STRING.id),
        OutputDefinition("resolved_target", "实际目标（QQ 会话 / 微信号）", VTypeRegistry.STRING.id),
        OutputDefinition("details", "逐通道结果", VTypeRegistry.DICTIONARY.id),
        OutputDefinition("error", "错误信息", VTypeRegistry.STRING.id)
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val inputs = getInputs()
        val enabled = enabledChannels(step.parameters)
        val channelLabel = if (enabled.isEmpty()) "未启用通道" else enabled.joinToString(" + ")
        val contentPill = PillUtil.createPillFromParam(
            step.parameters["content"],
            inputs.firstOrNull { it.id == "content" }
        )
        return PillUtil.buildSpannable(context, "机器人推送 · $channelLabel ", contentPill)
    }

    /**
     * 编辑器里的绑定按钮由 BotPushModuleUIProvider 放进对应通道卡片：
     *   微信 —— 扫码绑定，再等一条消息拿 context_token
     *   QQ   —— 临时连网关等一条消息，记住会话 ID
     */
    internal fun qqBoundLabel(appId: String): String? =
        if (appId.isEmpty()) null else loadQqTarget(appId)?.id

    /** 卡片右上角的状态：QQ 这类必填项为空但有绑定结果的，算「已绑定」。 */
    internal fun channelBoundLabel(params: Map<String, Any?>, enableId: String): String? = when (enableId) {
        "enable_qq" -> qqBoundLabel((params["qq_app_id"] as? String).orEmpty().trim())
        "enable_wechat" -> wechatBoundLabel()
        else -> null
    }

    internal fun isChannelEnabled(params: Map<String, Any?>, id: String): Boolean = isEnabled(params, id)

    // ---------------- QQ 官方机器人 ----------------

    internal fun startQqHandshake(step: ActionStep?) {
        val params = step?.parameters.orEmpty()
        val appId = (params["qq_app_id"] as? String).orEmpty().trim()
        val appSecret = (params["qq_app_secret"] as? String).orEmpty().trim()
        if (appId.isEmpty() || appSecret.isEmpty()) {
            toast("先填 QQ AppID 和 AppSecret，再点握手")
            return
        }
        val waitSeconds = (
            (params["qq_handshake_timeout"] as? Number)?.toLong()
                ?: (params["qq_handshake_timeout"] as? String)?.toLongOrNull()
                ?: 60L
            ).coerceIn(10, 300)
        toast("正在等 QQ 消息：${waitSeconds} 秒内给机器人发一条（群里要 @ 它）")

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            val client = OkHttpClient.Builder()
                .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
                .build()
            val token = qqAccessToken(client, appId, appSecret)
            val result = if (token == null) {
                Result.failure<QqHandshake>(IOException("获取 access_token 失败，检查 AppID / AppSecret"))
            } else {
                qqHandshake(client, token, waitSeconds)
            }
            result.onSuccess { handshake ->
                saveQqTarget(appId, handshake)
                withContext(Dispatchers.Main) {
                    toast("已绑定${typeLabel(handshake.type)}：${handshake.id}")
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    toast("握手失败：${error.message ?: "未知错误"}")
                }
            }
            scope.cancel()
        }
    }

    private fun typeLabel(type: String): String = when (type) {
        TARGET_C2C -> "单聊"
        TARGET_CHANNEL -> "频道"
        else -> "群聊"
    }

    private fun toast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
    }

    override fun validate(step: ActionStep, allSteps: List<ActionStep>): ValidationResult {
        val params = step.parameters
        val enabled = enabledChannels(params)
        if (enabled.isEmpty()) {
            return ValidationResult(false, "至少启用一条推送通道")
        }
        if ((params["content"] as? String).isNullOrBlank()) {
            return ValidationResult(false, "消息内容不能为空")
        }

        val missing = when {
            CHANNEL_WECHAT in enabled && loadWechatBinding()?.contextToken.isNullOrEmpty() ->
                "微信：还没扫码绑定，或绑定后没给机器人发过消息"
            CHANNEL_QQ in enabled && listOf("qq_app_id", "qq_app_secret")
                .any { (params[it] as? String).isNullOrBlank() } -> "QQ 官方机器人：AppID / AppSecret"
            else -> null
        }
        return if (missing == null) ValidationResult(true) else ValidationResult(false, "缺少参数 —— $missing")
    }

    /** 按开关收集本次要发的通道，顺序固定，输出可预期。 */
    private fun enabledChannels(params: Map<String, Any?>): List<String> {
        val result = mutableListOf<String>()
        if (isEnabled(params, "enable_wechat")) result.add(CHANNEL_WECHAT)
        if (isEnabled(params, "enable_qq")) result.add(CHANNEL_QQ)
        return result
    }

    /** 开关参数可能以 Boolean 或字符串落盘，两种都认。 */
    private fun isEnabled(params: Map<String, Any?>, id: String): Boolean = when (val value = params[id]) {
        true -> true
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        is Number -> value.toInt() == 1
        else -> false
    }

    // ---------------- 执行 ----------------

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val content = context.getVariableAsString("content", "").trim()
        if (content.isEmpty()) {
            return@withContext ExecutionResult.Failure("参数缺失", "消息内容不能为空")
        }

        val enabled = enabledChannels(
            getInputs().associate { it.id to context.getVariable(it.id).raw }
        )
        if (enabled.isEmpty()) {
            return@withContext ExecutionResult.Failure("参数缺失", "至少启用一条推送通道")
        }

        val title = context.getVariableAsString("title", "").trim()
        val format = normalizeFormat(context.getVariableAsString("format", FORMAT_TEXT))
        val body = buildBody(title, content, format)
        val timeout = (context.getVariableAsLong("timeout") ?: DEFAULT_TIMEOUT).coerceIn(3, 60)
        val proxyAddress = resolveModuleProxyAddress(
            context.getVariableAsString("proxy_mode", ""),
            context.getVariableAsString("proxy", ""),
            context
        )
        val client = applyProxyIfConfigured(
            OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .callTimeout(timeout, TimeUnit.SECONDS),
            appContext,
            proxyAddress
        ).build()

        val results = mutableListOf<ChannelResult>()
        for (channel in enabled) {
            onProgress(ProgressUpdate("正在推送到$channel"))
            val result = try {
                when (channel) {
                    CHANNEL_WECHAT -> sendWechat(client, body)
                    else -> sendQq(context, client, body)
                }
            } catch (e: IOException) {
                ChannelResult(channel, false, 0, error = "网络错误：${e.message ?: "请求失败"}")
            } catch (e: Exception) {
                ChannelResult(channel, false, 0, error = e.localizedMessage ?: "未知错误")
            }
            results.add(result)
        }

        aggregate(results)
    }

    /** 汇总每条通道的结果：全成功才算成功，部分失败时仍返回逐通道明细。 */
    private fun aggregate(results: List<ChannelResult>): ExecutionResult {
        val succeeded = results.filter { it.success }
        val failed = results.filterNot { it.success }
        val details = VDictionary(
            results.associate { result ->
                result.channel to VDictionary(
                    mapOf(
                        "success" to VBoolean(result.success),
                        "status_code" to VNumber(result.statusCode.toDouble()),
                        "message_id" to VString(result.messageId),
                        "response_body" to VString(result.responseBody),
                        "error" to VString(result.error)
                    )
                )
            }
        )
        val outputs = mapOf(
            "success" to VBoolean(failed.isEmpty()),
            "success_count" to VNumber(succeeded.size.toDouble()),
            "failure_count" to VNumber(failed.size.toDouble()),
            "sent_channels" to VString(succeeded.joinToString("、") { it.channel }),
            "failed_channels" to VString(failed.joinToString("、") { it.channel }),
            "resolved_target" to VString(results.firstOrNull { it.target.isNotEmpty() }?.target.orEmpty()),
            "details" to details,
            "error" to VString(failed.joinToString("；") { "${it.channel}：${it.error}" })
        )

        return if (failed.isEmpty()) {
            ExecutionResult.Success(outputs)
        } else {
            ExecutionResult.Failure(
                if (succeeded.isEmpty()) "推送失败" else "部分通道推送失败",
                failed.joinToString("；") { "${it.channel}：${it.error}" },
                outputs
            )
        }
    }

    /** 标题 + 正文；Markdown 下标题单独成行。 */
    private fun buildBody(title: String, content: String, format: String): String = when {
        title.isEmpty() -> content
        format == FORMAT_MARKDOWN -> "### $title\n\n$content"
        else -> "$title\n$content"
    }

    private fun normalizeFormat(raw: String): String =
        if (raw.trim().equals(FORMAT_MARKDOWN, ignoreCase = true)) FORMAT_MARKDOWN else FORMAT_TEXT

    // ---------------- 微信个人号（扫码） ----------------

    /**
     * 微信 ClawBot（ilink）通道。
     *
     * 协议：扫码拿 bot_token → POST /ilink/bot/sendmessage。
     * 两个硬前提：
     *  1. 先扫码绑定，token 落 prefs；
     *  2. 必须带 context_token —— 它只在你给机器人发过消息后由 getupdates 下发，
     *     所以绑定流程会额外等一条你的消息。
     * 另外这是单聊通道（发不了群），服务端限流约 7 条 / 5 分钟。
     */
    private fun sendWechat(client: OkHttpClient, body: String): ChannelResult {
        val binding = loadWechatBinding()
            ?: return ChannelResult(
                CHANNEL_WECHAT, false, 0,
                error = "还没绑定微信，点通道卡片里的「微信扫码绑定」"
            )
        if (binding.contextToken.isEmpty()) {
            return ChannelResult(
                CHANNEL_WECHAT, false, 0,
                error = "缺少会话凭证：先在微信里给机器人发一条消息，再点一次「微信扫码绑定」"
            )
        }

        val message = JSONObject()
            .put("from_user_id", "")
            .put("to_user_id", binding.userId)
            .put("client_id", "vflow-" + UUID.randomUUID().toString().replace("-", "").take(12))
            .put("message_type", 2)
            .put("message_state", 2)
            .put("context_token", binding.contextToken)
            .put(
                "item_list",
                JSONArray().put(
                    JSONObject().put("type", 1).put("text_item", JSONObject().put("text", body))
                )
            )
        val payload = JSONObject()
            .put("msg", message)
            .put("base_info", JSONObject().put("channel_version", WECHAT_CHANNEL_VERSION))
            .toString()

        val response = postJson(client, WECHAT_SEND_URL, payload, wechatHeaders(binding.token))
        val json = parseJson(response.body)
        val ret = json?.optInt("ret", 0) ?: 0
        return if (response.code == 200 && ret == 0) {
            ChannelResult(CHANNEL_WECHAT, true, response.code, target = binding.userId)
        } else {
            ChannelResult(
                CHANNEL_WECHAT, false, response.code,
                responseBody = response.body,
                error = wechatErrorText(ret, response),
                target = binding.userId
            )
        }
    }

    /** ilink 的鉴权头：AuthorizationType 固定值，X-WECHAT-UIN 每次随机即可。 */
    private fun wechatHeaders(token: String): Map<String, String> {
        val uin = Base64.encodeToString(
            Random().nextInt(Int.MAX_VALUE).toString().toByteArray(),
            Base64.NO_WRAP
        )
        return mapOf(
            "AuthorizationType" to "ilink_bot_token",
            "Authorization" to "Bearer $token",
            "X-WECHAT-UIN" to uin
        )
    }

    private fun wechatErrorText(ret: Int, response: HttpResponse): String = when (ret) {
        -2 -> "服务端限流（约 7 条 / 5 分钟），等一会儿再发"
        -14 -> "会话过期，重新扫码绑定一次"
        else -> "HTTP ${response.code} · ret=$ret ${response.body.take(120).ifBlank { "响应为空" }}"
    }

    /**
     * 编辑器里的「发送测试消息」：微信这条最容易出现「扫码成功但发不出去」（缺 context_token），
     * 先打一发就能确认，不用等整条工作流跑起来才发现。
     */
    internal fun sendTestMessage() {
        toast("正在发送测试消息…")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            val client = OkHttpClient.Builder()
                .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
                .build()
            val result = sendWechat(client, "vFlow 测试消息\n收到这条说明「$CHANNEL_WECHAT」配置可用。")
            withContext(Dispatchers.Main) {
                toast(
                    if (result.success) "已发出，去「$CHANNEL_WECHAT」里看看"
                    else "发送失败：${result.error.ifEmpty { "未知错误" }}"
                )
            }
            scope.cancel()
        }
    }

    /** 已绑定的微信号。contextToken 要等它收到过消息才有，可能暂时为空。 */
    private data class WechatBinding(
        val token: String,
        val botId: String,
        val userId: String,
        val contextToken: String
    )

    private data class WechatQr(val key: String, val imageContent: String)

    private data class WechatCredentials(val token: String, val botId: String, val userId: String)

    private data class WechatQrState(val status: String, val credentials: WechatCredentials? = null)

    private fun wechatPrefs() = appContext.getSharedPreferences(BOT_PREFS, Context.MODE_PRIVATE)

    private fun loadWechatBinding(): WechatBinding? {
        val prefs = wechatPrefs()
        val token = prefs.getString(KEY_WECHAT_TOKEN, null).orEmpty()
        val userId = prefs.getString(KEY_WECHAT_USER, null).orEmpty()
        if (token.isEmpty() || userId.isEmpty()) return null
        return WechatBinding(
            token = token,
            botId = prefs.getString(KEY_WECHAT_BOT, "").orEmpty(),
            userId = userId,
            contextToken = prefs.getString(KEY_WECHAT_CONTEXT, "").orEmpty()
        )
    }

    private fun saveWechatBinding(binding: WechatBinding) {
        wechatPrefs().edit()
            .putString(KEY_WECHAT_TOKEN, binding.token)
            .putString(KEY_WECHAT_BOT, binding.botId)
            .putString(KEY_WECHAT_USER, binding.userId)
            .putString(KEY_WECHAT_CONTEXT, binding.contextToken)
            .apply()
    }

    internal fun wechatBoundLabel(): String? = loadWechatBinding()?.userId?.takeIf { it.isNotEmpty() }

    /**
     * 编辑器里的「微信扫码绑定」：
     * 申请二维码 → 轮询扫码状态（过期自动换一张）→ 登录后再等你发一条消息拿 context_token。
     */
    internal fun startWechatQrLogin(context: Context) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val density = context.resources.displayMetrics.density
        val qrSize = (200 * density).toInt()

        val imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val statusView = TextView(context).apply {
            text = "正在申请二维码…"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), 0)
            addView(imageView)
            addView(statusView)
        }

        var dialog: AlertDialog? = null

        // 扫码状态是长轮询，超时按 45 秒算，别用默认的 15 秒
        val client = OkHttpClient.Builder()
            .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .build()

        scope.launch {
            withContext(Dispatchers.Main) {
                dialog = MaterialAlertDialogBuilder(context)
                    .setTitle("微信扫码绑定")
                    .setView(container)
                    .setNegativeButton("取消") { _, _ -> scope.cancel() }
                    .setOnDismissListener { scope.cancel() }
                    .show()
            }

            val fetched = fetchWechatQr(client)
            if (fetched == null) {
                withContext(Dispatchers.Main) { statusView.text = "申请二维码失败，检查网络后重试" }
                return@launch
            }
            var qr: WechatQr = fetched
            withContext(Dispatchers.Main) {
                imageView.setImageBitmap(qrBitmap(qr.imageContent, qrSize))
                statusView.text = "用手机微信扫一扫"
            }

            var credentials: WechatCredentials? = null
            val scanDeadline = System.currentTimeMillis() + 5 * 60 * 1000L
            while (scope.isActive && System.currentTimeMillis() < scanDeadline) {
                val state = pollWechatQr(client, qr.key)
                if (state == null) {
                    delay(1500)
                    continue
                }
                when (state.status) {
                    "scaned" ->
                        withContext(Dispatchers.Main) { statusView.text = "已扫码，在微信里点一下确认" }
                    "confirmed" -> {
                        credentials = state.credentials
                        break
                    }
                    "expired" -> {
                        val fresh = fetchWechatQr(client)
                        if (fresh != null) {
                            qr = fresh
                            withContext(Dispatchers.Main) {
                                imageView.setImageBitmap(qrBitmap(fresh.imageContent, qrSize))
                                statusView.text = "二维码已过期，换了一张，重新扫一下"
                            }
                        }
                    }
                    else -> delay(1200)
                }
            }

            val cred = credentials
            if (cred == null) {
                withContext(Dispatchers.Main) { statusView.text = "没等到确认，超时了。关掉重来一次" }
                return@launch
            }

            // 先把 token 落盘，后面补上 context_token 再覆盖一次
            saveWechatBinding(WechatBinding(cred.token, cred.botId, cred.userId, ""))
            withContext(Dispatchers.Main) {
                imageView.visibility = View.GONE
                statusView.text = "已登录，请在微信里给这个机器人发一条消息（随便发点什么）"
            }

            val contextToken = waitWechatContext(client, cred, 120_000L)
            if (contextToken == null) {
                withContext(Dispatchers.Main) {
                    statusView.text = "已登录，但还没收到你的消息。发一句给机器人后，再点一次「微信扫码绑定」就能补上"
                }
                return@launch
            }

            saveWechatBinding(WechatBinding(cred.token, cred.botId, cred.userId, contextToken))
            withContext(Dispatchers.Main) {
                statusView.text = "绑定成功"
                Toast.makeText(context, "微信绑定成功，可以推送了", Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
            }
        }
    }

    private fun fetchWechatQr(client: OkHttpClient): WechatQr? {
        val response = try {
            get(client, WECHAT_QR_URL, mapOf("iLink-App-ClientVersion" to "1"))
        } catch (e: IOException) {
            return null
        }
        val json = parseJson(response.body) ?: return null
        val key = json.optString("qrcode")
        val content = json.optString("qrcode_img_content")
        if (key.isEmpty() || content.isEmpty()) return null
        return WechatQr(key, content)
    }

    private fun pollWechatQr(client: OkHttpClient, key: String): WechatQrState? {
        val url = "$WECHAT_QR_STATUS_URL?qrcode=" + URLEncoder.encode(key, "UTF-8")
        val response = try {
            get(client, url, mapOf("iLink-App-ClientVersion" to "1"))
        } catch (e: IOException) {
            return null
        }
        val json = parseJson(response.body) ?: return null
        val status = json.optString("status")
        if (status.isEmpty()) return null
        if (status != "confirmed") return WechatQrState(status)

        // 顶层字段优先，也兼容 data.credentials 的嵌套写法
        val nested = json.optJSONObject("data")?.optJSONObject("credentials")
        val token = json.optString("bot_token").ifEmpty { nested?.optString("bot_token").orEmpty() }
        val botId = json.optString("ilink_bot_id").ifEmpty { nested?.optString("ilink_bot_id").orEmpty() }
        val userId = json.optString("ilink_user_id").ifEmpty { nested?.optString("ilink_user_id").orEmpty() }
        if (token.isEmpty() || userId.isEmpty()) return WechatQrState(status)
        return WechatQrState(status, WechatCredentials(token, botId, userId))
    }

    /** 长轮询 getupdates，等你发来的第一条消息，取它的 context_token。 */
    private suspend fun waitWechatContext(
        client: OkHttpClient,
        cred: WechatCredentials,
        timeoutMs: Long
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var cursor = ""
        while (System.currentTimeMillis() < deadline) {
            val payload = JSONObject()
                .put("get_updates_buf", cursor)
                .put("base_info", JSONObject().put("channel_version", WECHAT_CHANNEL_VERSION))
                .toString()
            val response = try {
                postJson(client, WECHAT_UPDATES_URL, payload, wechatHeaders(cred.token))
            } catch (e: IOException) {
                continue
            }
            val json = parseJson(response.body)
            if (json == null) {
                delay(800)
                continue
            }
            cursor = json.optString("get_updates_buf", cursor)
            val msgs = json.optJSONArray("msgs") ?: continue
            for (i in 0 until msgs.length()) {
                val msg = msgs.optJSONObject(i) ?: continue
                val token = msg.optString("context_token")
                if (token.isNotEmpty()) return token
            }
        }
        return null
    }

    /** 把二维码内容画成位图。正常返回的是待编码的字符串，个别版本会给 base64 图片，一并兼容。 */
    private fun qrBitmap(content: String, sizePx: Int): Bitmap? = try {
        if (content.startsWith("data:image")) {
            val bytes = Base64.decode(content.substringAfter("base64,"), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else {
            val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val offset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            }
        }
    } catch (e: Exception) {
        null
    }

    // ---------------- QQ 官方机器人 ----------------

    private fun sendQq(
        context: ExecutionContext,
        client: OkHttpClient,
        body: String
    ): ChannelResult {
        val channel = CHANNEL_QQ
        val appId = context.getVariableAsString("qq_app_id", "").trim()
        val appSecret = context.getVariableAsString("qq_app_secret", "").trim()
        val msgId = context.getVariableAsString("qq_msg_id", "").trim()

        if (appId.isEmpty() || appSecret.isEmpty()) {
            return ChannelResult(channel, false, 0, error = "AppID 与 AppSecret 都要填")
        }

        // 会话 ID 只能靠握手拿到，没绑过就直接报错，不在这里挂着等
        val bound = loadQqTarget(appId)
            ?: return ChannelResult(
                channel,
                false,
                0,
                error = "还没绑定 QQ 会话：在步骤编辑器点「QQ 握手」，再在 QQ 里给机器人发一条消息"
            )
        val targetType = bound.type
        val targetId = bound.id

        var token = qqAccessToken(client, appId, appSecret)
            ?: return ChannelResult(channel, false, 0, error = "获取 access_token 失败，请检查 AppID / AppSecret")

        val path = when (targetType) {
            TARGET_C2C -> "/v2/users/$targetId/messages"
            TARGET_CHANNEL -> "/channels/$targetId/messages"
            else -> "/v2/groups/$targetId/messages"
        }
        val payload = JSONObject().put("content", body)
        if (targetType == TARGET_CHANNEL) {
            if (msgId.isNotEmpty()) payload.put("msg_id", msgId)
        } else {
            payload.put("msg_type", 0)
            payload.put("msg_seq", 1)
            if (msgId.isNotEmpty()) payload.put("msg_id", msgId)
        }

        var response = postJson(client, "$QQ_API_BASE$path", payload.toString(), mapOf("Authorization" to "QQBot $token"))
        // token 失效时清缓存重取一次
        if (response.code == 401) {
            BotTokenCache.invalidate(qqTokenKey(appId, appSecret))
            token = qqAccessToken(client, appId, appSecret, forceRefresh = true).orEmpty()
            if (token.isNotEmpty()) {
                response = postJson(
                    client,
                    "$QQ_API_BASE$path",
                    payload.toString(),
                    mapOf("Authorization" to "QQBot $token")
                )
            }
        }

        val json = parseJson(response.body)
        val code = json?.optInt("code", 0) ?: 0
        return if (response.code in 200..299 && code == 0) {
            ChannelResult(
                channel = channel,
                success = true,
                statusCode = response.code,
                messageId = json?.optString("id").orEmpty(),
                responseBody = response.body,
                target = targetId
            )
        } else {
            ChannelResult(
                channel = channel,
                success = false,
                statusCode = response.code,
                error = describe(response, json),
                responseBody = response.body,
                target = targetId
            )
        }
    }

    /**
     * 握手：开一条网关 WebSocket，等机器人收到第一条消息，取会话 ID。
     * 群里必须是 @机器人 的消息；私聊直接发即可。拿到就断开，不做常驻。
     */
    private suspend fun qqHandshake(
        client: OkHttpClient,
        token: String,
        waitSeconds: Long
    ): Result<QqHandshake> {
        val gateway = fetchGateway(client, token)
            ?: return Result.failure(IOException("获取 WebSocket 网关失败，请确认机器人已上线"))

        // 握手要一直挂着，不能带 callTimeout
        val wsClient = client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        val received = CompletableDeferred<QqHandshake>()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var heartbeat: Job? = null
        var lastSeq = -1

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = parseJson(text) ?: return
                when (frame.optInt("op", -1)) {
                    // Hello：拿到心跳间隔后立刻鉴权
                    10 -> {
                        val interval = frame.optJSONObject("d")
                            ?.optLong("heartbeat_interval", 30_000L) ?: 30_000L
                        webSocket.send(identifyPayload(token))
                        heartbeat = scope.launch {
                            while (isActive) {
                                delay(interval)
                                val seq = if (lastSeq >= 0) lastSeq.toString() else "null"
                                webSocket.send("{\"op\":1,\"d\":$seq}")
                            }
                        }
                    }
                    // 事件：只认消息类，READY 之类直接跳过
                    0 -> {
                        lastSeq = frame.optInt("s", lastSeq)
                        parseQqEvent(frame)?.let { handshake ->
                            if (!received.isCompleted) received.complete(handshake)
                            webSocket.close(1000, "handshake done")
                        }
                    }
                    9 -> if (!received.isCompleted) {
                        received.completeExceptionally(IOException("会话无效（op 9），请重试"))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!received.isCompleted) received.completeExceptionally(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!received.isCompleted) {
                    received.completeExceptionally(IOException(wsCloseReason(code, reason)))
                }
            }
        }

        val socket = wsClient.newWebSocket(Request.Builder().url(gateway).build(), listener)
        val result = withTimeoutOrNull(waitSeconds * 1000) {
            runCatching { received.await() }
        } ?: Result.failure(
            IOException("${waitSeconds} 秒内没收到消息，请在 QQ 里给机器人发一条（群里要 @ 它）")
        )
        heartbeat?.cancel()
        socket.close(1000, "bye")
        scope.cancel()
        return result
    }

    /** 网关关闭码翻成人话，官方文档里的常见几个。 */
    private fun wsCloseReason(code: Int, reason: String): String = when (code) {
        4013, 4014 -> "事件订阅权限不足（intents），需在开放平台开通群消息事件"
        4914 -> "机器人已下架，只能连接沙箱环境"
        4915 -> "机器人已被封禁"
        4009 -> "会话超时，请重试"
        else -> "连接已关闭：$code ${reason.ifBlank { "" }}".trim()
    }

    private fun fetchGateway(client: OkHttpClient, token: String): String? {        val response = get(client, "$QQ_API_BASE/gateway", mapOf("Authorization" to "QQBot $token"))
        if (response.code !in 200..299) return null
        return parseJson(response.body)?.optString("url")?.takeIf { it.isNotEmpty() }
    }

    /** 只申请群聊/单聊事件（1 << 25），频道事件要另配 intents。 */
    private fun identifyPayload(token: String): String {
        val d = JSONObject()
            .put("token", "QQBot $token")
            .put("intents", 1 shl 25)
            .put("shard", JSONArray().put(0).put(1))
            .put(
                "properties",
                JSONObject()
                    .put("\$os", "Android")
                    .put("\$browser", "vFlow")
                    .put("\$device", "vFlow")
            )
        return JSONObject().put("op", 2).put("d", d).toString()
    }

    private fun parseQqEvent(frame: JSONObject): QqHandshake? {
        val d = frame.optJSONObject("d") ?: return null
        return when (frame.optString("t")) {
            "GROUP_AT_MESSAGE_CREATE" -> {
                val id = d.optString("group_openid")
                if (id.isEmpty()) null else QqHandshake(TARGET_GROUP, id, d.optString("id"))
            }
            "C2C_MESSAGE_CREATE" -> {
                val id = d.optJSONObject("author")?.optString("user_openid").orEmpty()
                if (id.isEmpty()) null else QqHandshake(TARGET_C2C, id, d.optString("id"))
            }
            "AT_MESSAGE_CREATE", "DIRECT_MESSAGE_CREATE" -> {
                val id = d.optString("channel_id")
                if (id.isEmpty()) null else QqHandshake(TARGET_CHANNEL, id, d.optString("id"))
            }
            else -> null
        }
    }

    private fun loadQqTarget(appId: String): QqTarget? {
        val raw = botPrefs().getString("qq_target_$appId", null) ?: return null
        return try {
            val json = JSONObject(raw)
            QqTarget(json.optString("type"), json.optString("id"))
                .takeIf { it.id.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveQqTarget(appId: String, handshake: QqHandshake) {
        val json = JSONObject()
            .put("type", handshake.type)
            .put("id", handshake.id)
            .put("savedAt", System.currentTimeMillis())
        botPrefs().edit().putString("qq_target_$appId", json.toString()).apply()
    }

    private fun botPrefs() = appContext.getSharedPreferences(BOT_PREFS, Context.MODE_PRIVATE)

    private fun qqAccessToken(
        client: OkHttpClient,
        appId: String,
        appSecret: String,
        forceRefresh: Boolean = false
    ): String? {
        val key = qqTokenKey(appId, appSecret)
        if (!forceRefresh) {
            BotTokenCache.get(key)?.let { return it }
        }
        val payload = JSONObject().put("appId", appId).put("clientSecret", appSecret).toString()
        val response = postJson(client, QQ_TOKEN_URL, payload)
        val json = parseJson(response.body) ?: return null
        if (response.code !in 200..299) return null
        val token = json.optString("access_token").takeIf { it.isNotEmpty() } ?: return null
        BotTokenCache.put(key, token, json.optLong("expires_in", 7200))
        return token
    }

    private fun qqTokenKey(appId: String, appSecret: String): String =
        "qq:$appId:${appSecret.hashCode()}"

    // ---------------- 通用 ----------------

    private fun get(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> builder.addHeader(key, value) }
        return client.newCall(builder.build()).execute().use { response ->
            HttpResponse(response.code, response.body?.string().orEmpty())
        }
    }

    private fun postJson(
        client: OkHttpClient,
        url: String,
        payload: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse {
        val builder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
        headers.forEach { (key, value) -> builder.addHeader(key, value) }
        return client.newCall(builder.build()).execute().use { response ->
            HttpResponse(response.code, response.body?.string().orEmpty())
        }
    }

    private fun parseJson(body: String): JSONObject? = try {
        if (body.isBlank()) null else JSONObject(body)
    } catch (_: Exception) {
        null
    }

    /** 各平台的错误字段拼成一句人话：QQ 是 code+message，其它平台是 errcode+errmsg。 */
    private fun describe(response: HttpResponse, json: JSONObject?): String {
        if (json != null) {
            val message = json.optString("errmsg").ifEmpty { json.optString("message") }
            if (message.isNotEmpty()) {
                val code = json.optInt("errcode", json.optInt("code", response.code))
                return "HTTP ${response.code} · $code $message"
            }
        }
        return "HTTP ${response.code} · ${response.body.take(200).ifBlank { "响应为空" }}"
    }

    private data class HttpResponse(val code: Int, val body: String)

    /** 握手拿到的一次会话：类型 + 目标 ID + 触发消息 ID（可用于被动回复）。 */
    private data class QqHandshake(val type: String, val id: String, val msgId: String = "")

    /** 已记住的会话，只存类型和 ID，消息 ID 用过就失效不落盘。 */
    private data class QqTarget(val type: String, val id: String)

    /** 单条通道的发送结果，最终汇总成 outputs。 */
    private data class ChannelResult(
        val channel: String,
        val success: Boolean,
        val statusCode: Int,
        val messageId: String = "",
        val responseBody: String = "",
        val error: String = "",
        val target: String = ""
    )
}

/** access_token 进程内缓存，避免每条推送都多打一次 token 接口。 */
internal object BotTokenCache {
    private data class Entry(val token: String, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun get(key: String): String? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() >= entry.expiresAt) {
            cache.remove(key)
            return null
        }
        return entry.token
    }

    fun put(key: String, token: String, ttlSeconds: Long) {
        // 提前 60s 过期，避免拿到边界上的 token
        val ttl = (ttlSeconds - 60).coerceAtLeast(30)
        cache[key] = Entry(token, System.currentTimeMillis() + ttl * 1000)
    }

    fun invalidate(key: String) {
        cache.remove(key)
    }
}
