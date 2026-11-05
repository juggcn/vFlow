// 文件: main/java/com/chaomixian/vflow/core/workflow/module/integration/MainTrendModule.kt
// 描述: 通达信「主趋势线」监控模块。
//      主趋势线 = EMA(EMA(CLOSE,10),10)，本根大于上一根为红（买），否则为绿（卖）。
//      按最新一根相对上一根的方向变化，输出 绿转红 / 红转绿 / 无变化 三种结果。

package com.chaomixian.vflow.core.workflow.module.integration

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.module.isMagicVariable
import com.chaomixian.vflow.core.module.isNamedVariable
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.types.basic.VList
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 主趋势线监控模块。
 *
 * 指标口径（与通达信一致）：
 *   主趋势线 = EMA(EMA(CLOSE,10),10)
 *   红（买）= 主趋势线 > REF(主趋势线,1)
 *   绿（卖）= 其余（含持平）
 *
 * 输出按"本根相对上一根是否翻转"给出三种结果，供下游 If 模块判断。
 */
class MainTrendModule : BaseModule() {

    companion object {
        const val PERIOD_5 = "5"
        const val PERIOD_15 = "15"
        const val PERIOD_30 = "30"
        const val PERIOD_60 = "60"
        const val PERIOD_DAY = "day"
        const val PERIOD_WEEK = "week"

        /** 分钟档（5/15/30/60）统一走 K 线分钟接口。 */
        private fun isMinutePeriod(period: String): Boolean =
            period != PERIOD_DAY && period != PERIOD_WEEK

        const val SOURCE_AUTO = "auto"
        const val SOURCE_TENCENT = "tencent"
        const val SOURCE_EASTMONEY = "eastmoney"
        const val SOURCE_SINA = "sina"

        const val STATUS_GREEN_TO_RED = "green_to_red"
        const val STATUS_RED_TO_GREEN = "red_to_green"
        const val STATUS_MIXED = "mixed"
        const val STATUS_NO_CHANGE = "no_change"

        /** 参与计算的 K 线根数。EMA 递推 150 根后误差已可忽略。 */
        private const val BARS = 150

        /** EMA 周期，与通达信原式一致。 */
        private const val EMA_PERIOD = 10

        /** 低于该根数视为本次取数无效，触发重试。 */
        private const val MIN_BARS = EMA_PERIOD + 3

        /** 单个源的重试次数上限，防止填错把请求打爆。 */
        private const val MAX_RETRY = 10

        /** 重试基础间隔，第 n 次重试等 n × 该值，避免被频控时越撞越紧。 */
        private const val RETRY_DELAY_MS = 600L

        private const val UA = "Mozilla/5.0 (Linux; Android 13)"

        private val HTTP: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .build()
        }
    }

    /** 单只标的的取数结果。 */
    private data class Series(val code: String, val name: String, val closes: List<Double>, val source: String)

    override val id = "vflow.integration.main_trend"

    override val metadata = ActionMetadata(
        name = "主趋势线监控",
        description = "按 5/15/30/60 分钟或日线、周线计算主趋势线 EMA(EMA(C,10),10)，单源重试后切换，输出绿转红、红转绿或无变化",
        iconRes = R.drawable.rounded_analytics_24,
        category = "网络",
        categoryId = "network"
    )

    private val periodOptions =
        listOf(PERIOD_5, PERIOD_15, PERIOD_30, PERIOD_60, PERIOD_DAY, PERIOD_WEEK)
    private val sourceOptions = listOf(SOURCE_AUTO, SOURCE_TENCENT, SOURCE_EASTMONEY, SOURCE_SINA)

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = "codes",
            name = "监控标的（每行一个）",
            staticType = ParameterType.STRING,
            defaultValue = "sh600000",
            acceptsMagicVariable = true,
            supportsRichText = true
        ),
        InputDefinition(
            id = "period",
            name = "周期",
            staticType = ParameterType.ENUM,
            defaultValue = PERIOD_DAY,
            options = periodOptions,
            optionsStringRes = listOf(
                R.string.option_vflow_integration_main_trend_period_5,
                R.string.option_vflow_integration_main_trend_period_15,
                R.string.option_vflow_integration_main_trend_period_30,
                R.string.option_vflow_integration_main_trend_period_60,
                R.string.option_vflow_integration_main_trend_period_day,
                R.string.option_vflow_integration_main_trend_period_week
            ),
            legacyValueMap = mapOf(
                "5分钟" to PERIOD_5,
                "15分钟" to PERIOD_15,
                "30分钟" to PERIOD_30,
                "60分钟" to PERIOD_60,
                "日线" to PERIOD_DAY,
                "周线" to PERIOD_WEEK
            ),
            acceptsMagicVariable = false
        ),
        InputDefinition(
            id = "source",
            name = "数据源",
            staticType = ParameterType.ENUM,
            defaultValue = SOURCE_AUTO,
            options = sourceOptions,
            optionsStringRes = listOf(
                R.string.option_vflow_integration_main_trend_source_auto,
                R.string.option_vflow_integration_main_trend_source_tencent,
                R.string.option_vflow_integration_main_trend_source_eastmoney,
                R.string.option_vflow_integration_main_trend_source_sina
            ),
            acceptsMagicVariable = false
        ),
        InputDefinition(
            id = "retry_count",
            name = "每个源重试次数",
            staticType = ParameterType.NUMBER,
            defaultValue = 3.0,
            acceptsMagicVariable = true,
            acceptedMagicVariableTypes = setOf(VTypeRegistry.NUMBER.id)
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition("status", "结果状态", VTypeRegistry.STRING.id),
        OutputDefinition("has_green_to_red", "是否出现绿转红", VTypeRegistry.BOOLEAN.id),
        OutputDefinition("has_red_to_green", "是否出现红转绿", VTypeRegistry.BOOLEAN.id),
        OutputDefinition("green_to_red", "绿转红标的", VTypeRegistry.LIST.id),
        OutputDefinition("red_to_green", "红转绿标的", VTypeRegistry.LIST.id),
        OutputDefinition("details", "逐标的明细", VTypeRegistry.LIST.id),
        OutputDefinition("summary", "汇总文本", VTypeRegistry.STRING.id),
        OutputDefinition("source", "实际数据源", VTypeRegistry.STRING.id),
        OutputDefinition("failed", "取数失败的标的", VTypeRegistry.STRING.id)
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val rawCodes = (step.parameters["codes"] as? String ?: "").trim()
        val codes = parseCodes(rawCodes)
        val rawPeriod = step.parameters["period"] as? String ?: PERIOD_DAY
        val period = periodOptions.firstOrNull { it == rawPeriod } ?: PERIOD_DAY
        val periodLabel = when (period) {
            PERIOD_DAY -> "日线"
            PERIOD_WEEK -> "周线"
            else -> "${period}分钟"
        }
        // 标的填的是变量引用时，编辑期拿不到运行期的实际代码，
        // parseCodes 解析结果必然为空，这里改成输出变量胶囊（渲染后形如「#1 标的代码」），
        // 避免误报「未配置标的」。
        if (codes.isEmpty() && (rawCodes.isMagicVariable() || rawCodes.isNamedVariable())) {
            val codesInput = getInputs().firstOrNull { it.id == "codes" }
            val pill = PillUtil.createPillFromParam(rawCodes, codesInput)
            return PillUtil.buildSpannable(context, "主趋势线 · ", pill, " · $periodLabel")
        }
        val codeLabel = when {
            codes.isEmpty() -> "未配置标的"
            codes.size == 1 -> codes.first()
            else -> "${codes.first()} +${codes.size - 1}"
        }
        return "主趋势线 · $codeLabel · $periodLabel"
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val codes = parseCodes(context.getVariableAsString("codes", ""))
        if (codes.isEmpty()) {
            return@withContext ExecutionResult.Failure("参数错误", "请至少填写一个标的代码，如 sh600000")
        }

        val inputsById = getInputs().associateBy { it.id }
        val period = inputsById["period"]
            ?.normalizeEnumValue(context.getVariableAsString("period", PERIOD_DAY))
            ?: PERIOD_DAY
        val sourceMode = inputsById["source"]
            ?.normalizeEnumValue(context.getVariableAsString("source", SOURCE_AUTO))
            ?: SOURCE_AUTO
        val retryCount = context.getVariableAsInt("retry_count") ?: 3

        val greenToRed = mutableListOf<VObject>()
        val redToGreen = mutableListOf<VObject>()
        val details = mutableListOf<VObject>()
        val failed = mutableListOf<String>()
        val summaryLines = mutableListOf<String>()
        var usedSource = ""

        for ((index, code) in codes.withIndex()) {
            onProgress(ProgressUpdate("正在计算 ${index + 1}/${codes.size}：$code"))

            val series = try {
                fetchSeries(code, period, sourceMode, retryCount) { message ->
                    onProgress(ProgressUpdate(message))
                }
            } catch (e: Exception) {
                failed.add("$code(${e.message ?: "未知错误"})")
                continue
            }

            if (series.closes.size < EMA_PERIOD + 3) {
                failed.add("$code(数据不足 ${series.closes.size} 根)")
                continue
            }
            if (usedSource.isEmpty()) usedSource = series.source

            val e1 = ema(series.closes, EMA_PERIOD)
            val e2 = ema(e1, EMA_PERIOD)
            val n = e2.size
            val cur = e2[n - 1]
            val prev = e2[n - 2]
            val prev2 = e2[n - 3]

            val isUp = cur > prev
            val wasUp = prev > prev2
            val change = when {
                isUp && !wasUp -> STATUS_GREEN_TO_RED
                !isUp && wasUp -> STATUS_RED_TO_GREEN
                else -> STATUS_NO_CHANGE
            }

            val close = series.closes.last()
            val changeLabel = when (change) {
                STATUS_GREEN_TO_RED -> "绿转红"
                STATUS_RED_TO_GREEN -> "红转绿"
                else -> "无变化"
            }
            val display = if (series.name.isNotBlank()) "$code ${series.name}" else code

            if (change == STATUS_GREEN_TO_RED) greenToRed.add(VString(display))
            if (change == STATUS_RED_TO_GREEN) redToGreen.add(VString(display))

            details.add(
                VDictionary(
                    mapOf(
                        "code" to VString(code),
                        "name" to VString(series.name),
                        "close" to VNumber(close),
                        "trend" to VNumber(cur),
                        "prev" to VNumber(prev),
                        "direction" to VString(if (isUp) "up" else "down"),
                        "change" to VString(change)
                    )
                )
            )

            summaryLines.add("$display  收盘 $close  主趋势线 ${"%.3f".format(cur)}(${if (isUp) "↑" else "↓"})  $changeLabel")
        }

        if (details.isEmpty()) {
            val reason = if (failed.isEmpty()) "没有可用的 K 线数据" else "全部标的取数失败：${failed.joinToString("，")}"
            return@withContext ExecutionResult.Failure("主趋势线计算失败", reason)
        }

        val status = when {
            greenToRed.isNotEmpty() && redToGreen.isNotEmpty() -> STATUS_MIXED
            greenToRed.isNotEmpty() -> STATUS_GREEN_TO_RED
            redToGreen.isNotEmpty() -> STATUS_RED_TO_GREEN
            else -> STATUS_NO_CHANGE
        }

        val summary = buildString {
            append(summaryLines.joinToString("\n"))
            if (failed.isNotEmpty()) {
                append("\n取数失败：")
                append(failed.joinToString("，"))
            }
        }

        onProgress(ProgressUpdate("计算完成：$status"))

        ExecutionResult.Success(
            mapOf(
                "status" to VString(status),
                "has_green_to_red" to VBoolean(greenToRed.isNotEmpty()),
                "has_red_to_green" to VBoolean(redToGreen.isNotEmpty()),
                "green_to_red" to VList(greenToRed),
                "red_to_green" to VList(redToGreen),
                "details" to VList(details),
                "summary" to VString(summary),
                "source" to VString(usedSource),
                "failed" to VString(failed.joinToString("，"))
            )
        )
    }

    // ---------------- 取数 ----------------

    /**
     * 按顺序尝试各数据源。每个源最多试 [retryCount] 次，
     * 次次都失败才切到下一个源；所有源都失败才抛异常。
     */
    private suspend fun fetchSeries(
        code: String,
        period: String,
        sourceMode: String,
        retryCount: Int,
        onRetry: suspend (String) -> Unit
    ): Series {
        val order = when (sourceMode) {
            SOURCE_TENCENT -> listOf(SOURCE_TENCENT)
            SOURCE_EASTMONEY -> listOf(SOURCE_EASTMONEY)
            SOURCE_SINA -> listOf(SOURCE_SINA)
            else -> if (isMinutePeriod(period)) {
                // 分钟档：腾讯走 mkline（不复权），东财支持前复权，优先东财
                listOf(SOURCE_EASTMONEY, SOURCE_TENCENT, SOURCE_SINA)
            } else {
                listOf(SOURCE_TENCENT, SOURCE_EASTMONEY, SOURCE_SINA)
            }
        }

        val attempts = retryCount.coerceIn(1, MAX_RETRY)
        var lastError: Exception? = null

        for (source in order) {
            for (attempt in 1..attempts) {
                try {
                    val series = when (source) {
                        SOURCE_TENCENT -> fetchTencent(code, period)
                        SOURCE_EASTMONEY -> fetchEastmoney(code, period)
                        else -> fetchSina(code, period)
                    }
                    if (series.closes.size >= MIN_BARS) return series
                    lastError = IOException(
                        "${sourceLabel(source)} 只返回 ${series.closes.size} 根 K 线"
                    )
                } catch (e: Exception) {
                    lastError = e
                }

                if (attempt < attempts) {
                    onRetry(
                        "$code ${sourceLabel(source)} 第 $attempt 次失败" +
                            "（${lastError?.message ?: "未知错误"}），稍后重试"
                    )
                    delay(RETRY_DELAY_MS * attempt)
                }
            }
        }
        throw lastError ?: IOException("所有数据源均无数据")
    }

    private fun sourceLabel(source: String): String = when (source) {
        SOURCE_TENCENT -> "腾讯"
        SOURCE_EASTMONEY -> "东方财富"
        else -> "新浪"
    }

    private fun fetchTencent(code: String, period: String): Series {
        val (url, arrayKey) = when {
            isMinutePeriod(period) -> {
                // 分钟档走 mkline（不提供复权），周期键为 m5 / m15 / m30 / m60
                val key = "m$period"
                "https://ifzq.gtimg.cn/appstock/app/kline/mkline?param=$code,$key,,$BARS" to key
            }
            period == PERIOD_WEEK ->
                "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$code,week,,,$BARS,qfq" to "qfqweek"
            else ->
                "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$code,day,,,$BARS,qfq" to "qfqday"
        }
        val root = JSONObject(httpGet(url))
        val node = root.optJSONObject("data")?.optJSONObject(code)
            ?: throw IOException("腾讯返回无 data")

        val array = node.optJSONArray(arrayKey)
            ?: node.optJSONArray(arrayKey.removePrefix("qfq"))
            ?: throw IOException("腾讯返回无 $arrayKey")

        val closes = ArrayList<Double>(array.length())
        for (i in 0 until array.length()) {
            val bar = array.optJSONArray(i) ?: continue
            val close = bar.optString(2).toDoubleOrNull() ?: continue
            closes.add(close)
        }

        val name = node.optJSONObject("qt")
            ?.optJSONArray(code)
            ?.optString(1)
            .orEmpty()

        return Series(code, name, closes, "腾讯")
    }

    private fun fetchEastmoney(code: String, period: String): Series {
        val secid = (if (code.startsWith("sh")) "1." else "0.") + code.substring(2)
        val klt = when {
            isMinutePeriod(period) -> period.toIntOrNull() ?: 60
            period == PERIOD_WEEK -> 102
            else -> 101
        }
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
                "?secid=$secid&ut=fa5fd1943c7b386f172d6893dbfba10b&klt=$klt&fqt=1" +
                "&end=20500101&lmt=$BARS&fields1=f1,f2,f3,f4,f5,f6" +
                "&fields2=f51,f52,f53,f54,f55,f56,f57,f58"

        val root = JSONObject(
            httpGet(
                url,
                mapOf("User-Agent" to UA, "Referer" to "https://quote.eastmoney.com/")
            )
        )
        val data = root.optJSONObject("data") ?: throw IOException("东财返回 data 为空")
        val klines = data.optJSONArray("klines") ?: throw IOException("东财返回无 klines")

        val closes = ArrayList<Double>(klines.length())
        for (i in 0 until klines.length()) {
            val parts = klines.optString(i).split(",")
            val close = parts.getOrNull(2)?.toDoubleOrNull() ?: continue
            closes.add(close)
        }

        return Series(code, data.optString("name"), closes, "东方财富")
    }

    private fun fetchSina(code: String, period: String): Series {
        val scale = when {
            isMinutePeriod(period) -> period.toIntOrNull() ?: 60
            period == PERIOD_WEEK -> 1200
            else -> 240
        }
        val url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
                "CN_MarketData.getKLineData?symbol=$code&scale=$scale&ma=no&datalen=$BARS"
        val body = httpGet(url)
        if (!body.trimStart().startsWith("[")) throw IOException("新浪返回非数组")

        val array = JSONArray(body)
        val closes = ArrayList<Double>(array.length())
        for (i in 0 until array.length()) {
            val close = array.optJSONObject(i)?.optString("close")?.toDoubleOrNull() ?: continue
            closes.add(close)
        }
        return Series(code, "", closes, "新浪")
    }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.addHeader(key, value) }
        HTTP.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("响应为空")
        }
    }

    // ---------------- 计算 ----------------

    /** 通达信 EMA：首值取第一个数据，其后 alpha = 2/(N+1)。 */
    private fun ema(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val alpha = 2.0 / (period + 1)
        val result = ArrayList<Double>(values.size)
        result.add(values[0])
        for (i in 1 until values.size) {
            result.add(result[i - 1] + alpha * (values[i] - result[i - 1]))
        }
        return result
    }

    /** 支持换行、逗号、分号、空格分隔，去重并统一成 sh/sz/bj 前缀。 */
    private fun parseCodes(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split('\n', ',', '，', ';', '；', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeCode(it) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun normalizeCode(input: String): String {
        val code = input.lowercase().replace("\\s".toRegex(), "")
        if (code.startsWith("sh") || code.startsWith("sz") || code.startsWith("bj")) return code
        if (code.length != 6 || code.any { !it.isDigit() }) return ""
        return when (code[0]) {
            '6', '5', '9' -> "sh$code"
            '4', '8' -> "bj$code"
            else -> "sz$code"
        }
    }
}
