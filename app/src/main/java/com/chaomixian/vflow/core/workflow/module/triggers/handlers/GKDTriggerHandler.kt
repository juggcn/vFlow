package com.chaomixian.vflow.core.workflow.module.triggers.handlers

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.types.complex.VScreenElement
import com.chaomixian.vflow.core.utils.StorageManager
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.module.triggers.*
import com.chaomixian.vflow.core.workflow.module.triggers.RuleExecutionState
import com.chaomixian.vflow.services.ServiceStateBus
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.JsonArray as GsonJsonArray
import com.google.gson.JsonElement as GsonJsonElement
import com.google.gson.JsonObject as GsonJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import li.songe.json5.Json5
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import li.songe.selector.FastQuery
import li.songe.selector.MatchOption
import li.songe.selector.QueryContext
import li.songe.selector.Selector
import li.songe.selector.Transform
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * GKD订阅触发器处理器
 * 解析 gkd 格式的订阅规则，监听页面变化并触发工作流
 */
class GKDTriggerHandler : ListeningTriggerHandler() {

    companion object {
        private const val TAG = "GKDTriggerHandler"
        private const val RULES_DIR = "gkd_rules"
    }

    // 存储所有工作流的状态
    private val triggerStates = CopyOnWriteArrayList<GKDTriggerState>()

    /** 触发器参数指纹，用于判断参数是否被改动、需要重建状态（否则改完要重启无障碍服务） */
    private val triggerFingerprints = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 单轮检查内共享的选择器查询缓存，配合 checkMutex 使用 */
    private val passQueryCache = HashMap<Long, AccessibilityNodeInfo?>()

    // Transform 实例
    private val transform = createTransform()

    // 监听Job
    private var listeningJob: Job? = null

    // 事件后补查Job（等广告控件延迟渲染）
    private var recheckJob: Job? = null

    // 串行化匹配检查：事件流与补查可能并发执行，避免状态竞争
    private val checkMutex = Mutex()

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun startListening(context: Context) {
        DebugLogger.d(TAG, "开始监听 GKD 订阅规则")

        syncTriggerStates()

        listeningJob = triggerScope.launch {
            launch {
                ServiceStateBus.windowChangeEventFlow.collect { (packageName, className) ->
                    DebugLogger.d(TAG, "收到窗口变化事件: $packageName / $className")
                    checkAndTriggerWorkflows(context, packageName, className)
                    scheduleRecheck(context, packageName, className)
                }
            }
            launch {
                // 内容变化事件高频（列表滚动、倒计时文字刷新每秒可达几十次）。
                // 用 sample 而非 debounce：事件持续不断时 debounce 会被一直重置而永不 emit，
                // 倒计时类按钮整段漏检；sample 按固定周期取最新值，一定能触发检查。
                ServiceStateBus.windowContentChangedFlow
                    .sample(TRIGGER_CONTENT_DEBOUNCE_MS)
                    .collect { (packageName, className) ->
                        DebugLogger.d(TAG, "收到窗口内容变化事件: $packageName / $className")
                        checkAndTriggerWorkflows(context, packageName, className)
                        scheduleRecheck(context, packageName, className)
                    }
            }
        }
    }

    /**
     * 事件后的短周期补查：开屏广告的典型时序是 Activity 先出现、广告控件
     * 50~300ms 后才渲染完成，事件瞬间查一遍树大概率还没画出来。未命中时
     * 每隔一小段时间重查，命中即停（新事件会取消上一轮补查）。
     */
    private fun scheduleRecheck(context: Context, packageName: String, className: String) {
        recheckJob?.cancel()
        recheckJob = triggerScope.launch {
            repeat(TRIGGER_RECHECK_TIMES) {
                delay(TRIGGER_RECHECK_INTERVAL_MS)
                checkAndTriggerWorkflows(context, packageName, className)
            }
        }
    }

    override fun stopListening(context: Context) {
        DebugLogger.d(TAG, "停止监听 GKD 订阅规则")
        listeningJob?.cancel()
        listeningJob = null
        recheckJob?.cancel()
        recheckJob = null
        triggerStates.clear()
        triggerFingerprints.clear()
        passQueryCache.clear()
    }

    /**
     * 同步 triggerStates 与 listeningTriggers
     */
    private fun syncTriggerStates() {
        val currentTriggerIds = listeningTriggers.map { it.triggerId }.toSet()

        triggerStates.removeAll { state ->
            val id = state.trigger.triggerId
            if (!currentTriggerIds.contains(id)) return@removeAll true
            // 参数变了（订阅地址、规则组范围等）：丢弃旧状态，下面按新参数重建并重新解析订阅
            if (triggerFingerprints[id] != state.trigger.fingerprint()) {
                DebugLogger.d(TAG, "触发器 '$id' 参数已变更，重新加载 GKD 规则")
                triggerFingerprints.remove(id)
                return@removeAll true
            }
            false
        }

        for (trigger in listeningTriggers) {
            if (triggerStates.none { it.trigger.triggerId == trigger.triggerId }) {
                val state = createTriggerState(trigger)
                if (state != null) {
                    triggerStates.add(state)
                    triggerFingerprints[trigger.triggerId] = trigger.fingerprint()
                    DebugLogger.d(TAG, "加载工作流 '${trigger.workflowName}' 的 GKD 规则")
                }
            }
        }
    }

    /**
     * 创建触发器状态
     */
    private fun createTriggerState(trigger: TriggerSpec): GKDTriggerState? {
        val config = trigger.parameters

        val rules = mutableListOf<ResolvedGKDRule>()

        // 是否连订阅里被「默认关闭」的规则组一起解析。
        // 社区订阅（如「梦念逍遥のGKD订阅」）默认只开「开屏广告」，全屏广告 / 局部广告 / 更新提示
        // 等组的 enable 都是 false，要靠用户在 GKD 客户端里逐组打开；vFlow 没有这套组开关界面，
        // 严格遵循它就等于绝大多数广告规则拿不到。因此默认全部解析。
        val includeDisabled = (config["enableMode"] as? String) != "enabled"
        DebugLogger.d(TAG, "enableMode=${if (includeDisabled) "all" else "enabled"}")

        // 1. 从订阅 URL 下载
        val subscriptionUrl = config["subscriptionUrl"] as? String
        if (!subscriptionUrl.isNullOrBlank()) {
            DebugLogger.d(TAG, "工作流 '${trigger.workflowName}' 从订阅 URL 加载: $subscriptionUrl")
            val parsedRules = downloadAndParseSubscription(subscriptionUrl, getRulesDir(), includeDisabled)
            DebugLogger.d(TAG, "工作流 '${trigger.workflowName}' 解析到 ${parsedRules.size} 条规则")
            rules.addAll(parsedRules)
        }

        // 2. 从文件加载
        val subscriptionFile = config["subscriptionFile"] as? String
        if (!subscriptionFile.isNullOrBlank()) {
            val fileRules = loadRulesFromFile(subscriptionFile, includeDisabled)
            rules.addAll(fileRules)
        }

        if (rules.isEmpty()) {
            DebugLogger.w(TAG, "工作流 '${trigger.workflowName}' 没有有效的 GKD 规则")
            return null
        }

        return GKDTriggerState(trigger = trigger, rules = rules)
    }

    /**
     * 下载并解析远程订阅
     * 使用缓存避免重复下载
     */
    private fun downloadAndParseSubscription(url: String, rulesDir: File, includeDisabled: Boolean): List<ResolvedGKDRule> {
        // 生成缓存文件名（使用 URL 的 hash 作为文件名）
        val fileName = "subscription_${url.hashCode()}.json"
        val file = File(rulesDir, fileName)

        // 检查缓存是否存在且有效（1小时内有效）
        val cacheValid = file.exists() && (System.currentTimeMillis() - file.lastModified()) < 3600000

        if (cacheValid) {
            DebugLogger.d(TAG, "使用缓存的订阅: ${file.absolutePath}")
            return try {
                val content = file.readText()
                if (content.isNotEmpty()) {
                    parseSubscriptionContent(content, includeDisabled)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                DebugLogger.e(TAG, "读取缓存失败，尝试重新下载", e)
                downloadSubscription(url, file, includeDisabled)
            }
        }

        // 缓存不存在或已过期，下载订阅
        return downloadSubscription(url, file, includeDisabled)
    }

    /**
     * 实际执行下载操作
     * 在后台线程执行网络请求
     */
    private fun downloadSubscription(url: String, file: File, includeDisabled: Boolean): List<ResolvedGKDRule> {
        DebugLogger.d(TAG, "开始下载订阅: $url")

        return runBlocking(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder().build()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                DebugLogger.d(TAG, "响应码: ${response.code}")

                if (!response.isSuccessful) {
                    DebugLogger.e(TAG, "下载订阅失败: ${response.code}")
                    return@runBlocking emptyList<ResolvedGKDRule>()
                }

                val body = response.body
                if (body == null) {
                    DebugLogger.e(TAG, "响应体为空")
                    return@runBlocking emptyList<ResolvedGKDRule>()
                }

                val content = body.string()
                DebugLogger.d(TAG, "下载内容长度: ${content.length}")

                if (content.isEmpty()) {
                    DebugLogger.e(TAG, "订阅内容为空")
                    return@runBlocking emptyList<ResolvedGKDRule>()
                }

                // 保存到文件
                file.writeText(content)
                DebugLogger.d(TAG, "已保存订阅到: ${file.absolutePath}")

                // 解析
                parseSubscriptionContent(content, includeDisabled)
            } catch (e: Exception) {
                DebugLogger.e(TAG, "下载订阅失败: ${e.message}", e)
                // 如果下载失败且有缓存文件，尝试使用缓存
                if (file.exists()) {
                    DebugLogger.d(TAG, "下载失败，尝试使用旧缓存")
                    return@runBlocking try {
                        val content = file.readText()
                        if (content.isNotEmpty()) {
                            parseSubscriptionContent(content, includeDisabled)
                        } else {
                            emptyList()
                        }
                    } catch (e2: Exception) {
                        DebugLogger.e(TAG, "读取旧缓存也失败", e2)
                        emptyList()
                    }
                }
                emptyList()
            }
        }
    }

    /**
     * 解析订阅内容
     */
    private fun parseSubscriptionContent(content: String, includeDisabled: Boolean): List<ResolvedGKDRule> {
        // 验证输入
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed.length < 10) {
            DebugLogger.w(TAG, "订阅内容为空或过短")
            return emptyList()
        }

        return try {
            // 使用 Json5 解析（支持单引号、注释等）
            val jsonElement = Json5.parseToJson5Element(trimmed)
            parseJsonElement(jsonElement, includeDisabled)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "解析订阅内容失败: ${trimmed.take(100)}", e)
            emptyList()
        }
    }

    /**
     * 解析 JSON 元素
     */
    private fun parseJsonElement(jsonElement: JsonElement, includeDisabled: Boolean): List<ResolvedGKDRule> {
        val rules = mutableListOf<ResolvedGKDRule>()

        if (jsonElement !is JsonObject) {
            DebugLogger.w(TAG, "JSON 根元素不是对象")
            return rules
        }
        val rootObj = jsonElement

        DebugLogger.d(TAG, "解析根对象，字段: ${rootObj.keys}")

        // 解析 apps
        val appsElement = rootObj["apps"]
        if (appsElement is JsonArray) {
            DebugLogger.d(TAG, "apps 数组元素数量: ${appsElement.size}")
            appsElement.forEachIndexed { index, appJson ->
                if (appJson !is JsonObject) {
                    DebugLogger.w(TAG, "app[$index] 不是对象")
                    return@forEachIndexed
                }
                val appObj = appJson

                // 检查 app 级别的启用状态
                val appEnable = appObj["enable"]?.jsonPrimitive?.booleanOrNull
                if (!includeDisabled && appEnable == false) {
                    DebugLogger.d(TAG, "app[$index] 已禁用，跳过")
                    return@forEachIndexed
                }

                val appId = appObj["id"]?.jsonPrimitive?.content ?: run {
                    DebugLogger.w(TAG, "app[$index] 缺少 id 字段")
                    return@forEachIndexed
                }
                val appName = appObj["name"]?.jsonPrimitive?.content

                val groupsElement = appObj["groups"]
                if (groupsElement is JsonArray) {
                    groupsElement.forEachIndexed { gIndex, groupJson ->
                        if (groupJson !is JsonObject) {
                            DebugLogger.w(TAG, "app[$appId].groups[$gIndex] 不是对象")
                            return@forEachIndexed
                        }
                        val groupObj = groupJson

                        // 检查 group 级别的启用状态
                        val groupEnable = groupObj["enable"]?.jsonPrimitive?.booleanOrNull
                        if (!includeDisabled && groupEnable == false) {
                            DebugLogger.d(TAG, "app[$appId].groups[$gIndex] 已禁用，跳过")
                            return@forEachIndexed
                        }

                        val groupName = groupObj["name"]?.jsonPrimitive?.content ?: "未命名"

                        val rulesElement = groupObj["rules"]
                        if (rulesElement is JsonArray) {
                            rulesElement.forEachIndexed { rIndex, ruleJson ->
                                if (ruleJson !is JsonObject) {
                                    DebugLogger.w(TAG, "app[$appId].$groupName.rules[$rIndex] 不是对象")
                                    return@forEachIndexed
                                }
                                val ruleObj = ruleJson

                                // 检查 rule 级别的启用状态
                                val ruleEnable = ruleObj["enable"]?.jsonPrimitive?.booleanOrNull
                                if (!includeDisabled && ruleEnable == false) {
                                    DebugLogger.d(TAG, "app[$appId].$groupName.rules[$rIndex] 已禁用，跳过")
                                    return@forEachIndexed
                                }

                                val rule = parseRule(ruleObj, groupName, appId)
                                if (rule != null) {
                                    rules.add(rule)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            DebugLogger.w(TAG, "apps 字段不是数组或为空: ${appsElement?.javaClass?.simpleName}")
        }

        // 解析 globalGroups
        val globalGroupsElement = rootObj["globalGroups"]
        if (globalGroupsElement is JsonArray) {
            DebugLogger.d(TAG, "globalGroups 数组元素数量: ${globalGroupsElement.size}")
            globalGroupsElement.forEachIndexed { index, groupJson ->
                if (groupJson !is JsonObject) {
                    DebugLogger.w(TAG, "globalGroups[$index] 不是对象")
                    return@forEachIndexed
                }
                val groupObj = groupJson

                // 检查 globalGroup 级别的启用状态
                val groupEnable = groupObj["enable"]?.jsonPrimitive?.booleanOrNull
                if (!includeDisabled && groupEnable == false) {
                    DebugLogger.d(TAG, "globalGroups[$index] 已禁用，跳过")
                    return@forEachIndexed
                }

                val groupName = groupObj["name"]?.jsonPrimitive?.content ?: "全局"

                val rulesElement = groupObj["rules"]
                if (rulesElement is JsonArray) {
                    rulesElement.forEachIndexed { rIndex, ruleJson ->
                        if (ruleJson !is JsonObject) {
                            DebugLogger.w(TAG, "globalGroups.$groupName.rules[$rIndex] 不是对象")
                            return@forEachIndexed
                        }
                        val ruleObj = ruleJson

                        // 检查 rule 级别的启用状态
                        val ruleEnable = ruleObj["enable"]?.jsonPrimitive?.booleanOrNull
                        if (!includeDisabled && ruleEnable == false) {
                            DebugLogger.d(TAG, "globalGroups.$groupName.rules[$rIndex] 已禁用，跳过")
                            return@forEachIndexed
                        }

                        val rule = parseRule(ruleObj, groupName, null)
                        if (rule != null) {
                            rules.add(rule)
                        }
                    }
                }
            }
        }

        DebugLogger.d(TAG, "共解析到 ${rules.size} 条规则")
        return rules
    }

    /**
     * 解析规则
     */
    private fun parseRule(
        ruleObj: JsonObject,
        groupName: String,
        appId: String?
    ): ResolvedGKDRule? {
        val ruleName = ruleObj["name"]?.jsonPrimitive?.content ?: "未命名规则"
        val ruleKey = ruleObj["key"]?.jsonPrimitive?.int

        // 解析 preKeys（可能是单个数字或数组）
        val preKeys = when (val preKeysElement = ruleObj["preKeys"]) {
            null -> emptyList()
            is JsonArray -> preKeysElement.mapNotNull { it.jsonPrimitive.int }
            else -> {
                // 尝试作为单个数字解析
                listOfNotNull(preKeysElement.jsonPrimitive.int)
            }
        }

        // 解析 resetMatch
        val resetMatch = ruleObj["resetMatch"]?.jsonPrimitive?.content

        // 解析规则自带动作
        val action = parseAction(ruleObj["action"])

        // 解析选择器
        val matches = parseSelectors(ruleObj["matches"])
        val anyMatches = parseSelectors(ruleObj["anyMatches"])
        val excludeMatches = parseSelectors(ruleObj["excludeMatches"])
        val excludeAllMatches = parseSelectors(ruleObj["excludeAllMatches"])

        // 如果没有有效的选择器，跳过
        if (matches.isEmpty() && anyMatches.isEmpty()) {
            return null
        }

        return ResolvedGKDRule(
            name = ruleName,
            groupName = groupName,
            appId = appId,
            activityIds = parseStringArray(ruleObj["activityIds"]),
            excludeActivityIds = parseStringArray(ruleObj["excludeActivityIds"]),
            matches = matches,
            anyMatches = anyMatches,
            excludeMatches = excludeMatches,
            excludeAllMatches = excludeAllMatches,
            key = ruleKey,
            preKeys = preKeys,
            resetMatch = resetMatch,
            actionCd = ruleObj["actionCd"]?.jsonPrimitive?.long,
            actionDelay = ruleObj["actionDelay"]?.jsonPrimitive?.long,
            matchDelay = ruleObj["matchDelay"]?.jsonPrimitive?.long,
            matchTime = ruleObj["matchTime"]?.jsonPrimitive?.long,
            matchRoot = ruleObj["matchRoot"]?.jsonPrimitive?.boolean,
            fastQuery = ruleObj["fastQuery"]?.jsonPrimitive?.boolean,
            actionMaximum = ruleObj["actionMaximum"]?.jsonPrimitive?.int,
            // 新增字段
            actionCdKey = ruleObj["actionCdKey"]?.jsonPrimitive?.int,
            actionMaximumKey = ruleObj["actionMaximumKey"]?.jsonPrimitive?.int,
            forcedTime = ruleObj["forcedTime"]?.jsonPrimitive?.long,
            priorityTime = ruleObj["priorityTime"]?.jsonPrimitive?.long,
            priorityActionMaximum = ruleObj["priorityActionMaximum"]?.jsonPrimitive?.int,
            action = action
        )
    }

    /**
     * 解析规则的 action 字段。
     *
     * gkd 允许三种写法：
     * - 字符串：`"back"`
     * - 对象：`{ "type": "clickCenter", ... }`
     * - 数组（多动作）：取第一个能识别出来的
     *
     * 只归一化成小写字符串返回，具体执行在 performTrigger 里分派。
     */
    private fun parseAction(element: JsonElement?): String? {
        fun fromItem(item: JsonElement?): String? = when (item) {
            null -> null
            is kotlinx.serialization.json.JsonPrimitive -> item.contentLowerOrNull()
            is JsonObject -> item["type"]?.jsonPrimitive?.contentLowerOrNull()
            else -> null
        }

        return when (element) {
            null -> null
            is JsonArray -> element.firstNotNullOfOrNull { fromItem(it) }
            else -> fromItem(element)
        }
    }

    /** 取小写内容，非字符串字面量返回 null */
    private fun kotlinx.serialization.json.JsonPrimitive.contentLowerOrNull(): String? =
        if (this.isString || this.content.isNotBlank()) this.content.lowercase() else null

    /**
     * 解析选择器列表
     */
    private fun parseSelectors(element: JsonElement?): List<Selector> {
        if (element == null) return emptyList()
        return when (element) {
            is JsonArray -> {
                element.mapNotNull { item ->
                    (item as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { source ->
                        try {
                            Selector.parse(source)
                        } catch (e: Exception) {
                            DebugLogger.w(TAG, "解析选择器失败: $source")
                            null
                        }
                    }
                }
            }
            is kotlinx.serialization.json.JsonPrimitive -> {
                element.content?.let { source ->
                    try {
                        listOf(Selector.parse(source))
                    } catch (e: Exception) {
                        DebugLogger.w(TAG, "解析选择器失败: $source")
                        emptyList()
                    }
                } ?: emptyList()
            }
            else -> emptyList()
        }
    }

    /**
     * 解析字符串数组
     */
    private fun parseStringArray(element: JsonElement?): List<String>? {
        if (element == null) return null
        return when (element) {
            is JsonArray -> {
                element.mapNotNull { item -> (item as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }
            is kotlinx.serialization.json.JsonPrimitive -> {
                element.content?.let { listOf(it) }
            }
            else -> null
        }
    }

    /**
     * 从文件加载规则
     */
    private fun loadRulesFromFile(filePath: String, includeDisabled: Boolean): List<ResolvedGKDRule> {
        val file = if (filePath.startsWith("/")) {
            File(filePath)
        } else {
            File(getRulesDir(), filePath)
        }

        if (!file.exists()) {
            DebugLogger.w(TAG, "规则文件不存在: ${file.absolutePath}")
            return emptyList()
        }

        return try {
            file.readText().let { content ->
                parseSubscriptionContent(content, includeDisabled)
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "读取规则文件失败: ${file.absolutePath}", e)
            emptyList()
        }
    }

    /**
     * 获取规则目录
     */
    private fun getRulesDir(): File {
        val dir = File(StorageManager.tempDir, RULES_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 检查并触发匹配的工作流
     */
    private suspend fun checkAndTriggerWorkflows(context: Context, packageName: String, className: String) {
        // 先同步状态
        syncTriggerStates()

        val service = ServiceStateBus.getAccessibilityService() ?: return
        // 扫描所有窗口（含 Dialog / PopupWindow 等弹窗层），激活窗口排最前。
        // 只扫 rootInActiveWindow 会漏掉弹窗广告。
        val windowRoots = collectWindowRoots(service)
        if (windowRoots.isEmpty()) return

        checkMutex.withLock {
            passQueryCache.clear()
            checkAndTriggerWorkflowsLocked(context, packageName, className, windowRoots)
        }
    }

    private fun checkAndTriggerWorkflowsLocked(
        context: Context,
        packageName: String,
        className: String,
        windowRoots: List<AccessibilityNodeInfo>
    ) {

        val t = System.currentTimeMillis()
        val currentPackage = packageName
        val currentActivity = className

        for (state in triggerStates) {
            try {
                // 检测应用/Activity 切换，处理 resetMatch
                val oldPackage = state.currentPackage
                val oldActivity = state.currentActivity

                if (oldPackage != currentPackage || oldActivity != currentActivity) {
                    // 应用或 Activity 发生变化
                    DebugLogger.d(TAG, "应用/Activity 切换: $oldPackage/$oldActivity -> $currentPackage/$currentActivity")

                    // 检查每个规则是否需要重置
                    for (rule in state.rules) {
                        if (rule.shouldReset(oldPackage, oldActivity, currentPackage, currentActivity)) {
                            DebugLogger.d(TAG, "规则 '${rule.name}' 需要重置 (resetMatch=${rule.resetMatch})")
                            state.resetRule(rule)

                            // 如果规则有 key，从 matchedKeys 中移除
                            if (rule.key != null) {
                                state.matchedKeys.remove(rule.key)
                                DebugLogger.d(TAG, "  移除已匹配的 key: ${rule.key}")
                            }
                        }
                    }

                    // 更新当前的应用和 Activity
                    state.currentPackage = currentPackage
                    state.currentActivity = currentActivity
                }

                // 匹配规则
                for (rule in state.rules) {
                    val ruleState = state.getRuleState(rule)

                    // 冷却与最大触发次数检查
                    if (!ruleState.canTrigger()) {
                        continue
                    }

                    // 检查前置规则 (preKeys)：GKD 官方语义是所有 preKeys 对应的
                    // 规则都已触发（自上次 reset 以来），而不是只看最后一次触发的规则
                    if (!rule.checkPreKeys(state.matchedKeys)) {
                        continue
                    }

                    // 检查应用匹配
                    if (rule.appId != null && rule.appId != currentPackage) {
                        continue
                    }

                    // 检查 Activity 匹配
                    if (!matchActivity(rule.activityIds, rule.excludeActivityIds, currentActivity)) {
                        continue
                    }

                    // 逐窗口匹配；指定包名的规则只扫对应包名的窗口，控制多窗口开销
                    var matchedNode: AccessibilityNodeInfo? = null
                    for (root in windowRoots) {
                        if (rule.appId != null) {
                            val rootPkg = root.packageName?.toString()
                            if (rootPkg != null && rootPkg != rule.appId) continue
                        }
                        val node = matchRuleInRoot(root, rule) ?: continue
                        matchedNode = node
                        break
                    }

                    if (matchedNode == null) {
                        // 节点消失：重置首次匹配时间，下次重新累计 matchDelay
                        if (ruleState.matchChangedTime != 0L) {
                            ruleState.matchChangedTime = 0L
                        }
                        continue
                    }

                    // matchDelay：节点需持续出现该时长才触发（防抖）。
                    // 首次命中只记录时间，由事件后的补查机制在延迟期满后确认触发
                    if (ruleState.matchChangedTime == 0L) {
                        ruleState.matchChangedTime = t
                        if (ruleState.matchDelay > 0) continue
                    } else if (t - ruleState.matchChangedTime < ruleState.matchDelay) {
                        continue
                    }

                    // matchTime：首次匹配后 matchTime 毫秒内有效，超窗作废
                    val matchTimeVal = ruleState.matchTime
                    if (matchTimeVal != null && matchTimeVal > 0 &&
                        t - ruleState.matchChangedTime > matchTimeVal
                    ) {
                        ruleState.matchChangedTime = 0L
                        continue
                    }

                    // forcedTime：首次匹配后的强制等待期内不动作
                    if (ruleState.isInForcedTime()) {
                        continue
                    }

                    // 记录匹配
                    ruleState.recordMatch(t)

                    // 如果规则有 key，添加到 matchedKeys
                    if (rule.key != null) {
                        state.matchedKeys.add(rule.key)
                        DebugLogger.d(TAG, "规则 '${rule.name}' 匹配成功，添加 key: ${rule.key}")
                    }

                    // 处理触发延迟
                    val nodeForTrigger = matchedNode
                    if (ruleState.actionDelay > 0) {
                        ruleState.startActionDelay(t)
                        val job = triggerScope.launch {
                            delay(ruleState.actionDelay)
                            performTrigger(context, state, rule, ruleState, nodeForTrigger)
                        }
                        ruleState.actionDelayJob = job
                    } else {
                        performTrigger(context, state, rule, ruleState, nodeForTrigger)
                    }
                    break // 触发后退出规则循环
                }
            } catch (e: Exception) {
                DebugLogger.e(TAG, "处理工作流 '${state.trigger.workflowName}' 时出错", e)
            }
        }
    }

    /**
     * 在单个窗口根节点上匹配规则
     * GKD 官方语义：matches 与 anyMatches 同时评估——
     * matches 配置了就必须全部命中，anyMatches 配置了就必须任一命中，两者取交集
     */
    private fun matchRuleInRoot(
        root: AccessibilityNodeInfo,
        rule: ResolvedGKDRule
    ): AccessibilityNodeInfo? {
        val matchOption = MatchOption(fastQuery = rule.fastQuery ?: false)

        val matchesNode = if (rule.matches.isEmpty()) null
        else queryRuleSelectors(root, rule.matches, matchOption, true)

        val anyNode = if (rule.anyMatches.isEmpty()) null
        else queryRuleSelectors(root, rule.anyMatches, matchOption, false)

        val ok = (rule.matches.isEmpty() || matchesNode != null) &&
                (rule.anyMatches.isEmpty() || anyNode != null)
        if (!ok) return null

        // 排除条件在命中所在的同一个窗口内检查
        if (rule.excludeMatches.isNotEmpty() &&
            queryRuleSelectors(root, rule.excludeMatches, matchOption, false) != null
        ) {
            return null
        }
        if (rule.excludeAllMatches.isNotEmpty()) {
            val allExcluded = rule.excludeAllMatches.all { selector ->
                queryRuleSelectors(root, listOf(selector), matchOption, false) == null
            }
            if (!allExcluded) {
                return null
            }
        }

        return matchesNode ?: anyNode
    }

    /**
     * 匹配 Activity
     */
    private fun matchActivity(
        activityIds: List<String>?,
        excludeActivityIds: List<String>?,
        currentActivity: String
    ): Boolean {
        // 检查排除
        if (excludeActivityIds != null) {
            if (excludeActivityIds.any { pattern ->
                    pattern == currentActivity || pattern == "*" || currentActivity.contains(pattern)
                }) {
                return false
            }
        }

        // 如果没有指定 activityIds，则匹配所有
        if (activityIds == null) return true

        // 检查是否匹配
        return activityIds.any { pattern ->
            pattern == currentActivity || pattern == "*" || currentActivity.contains(pattern)
        }
    }

    /**
     * 查询规则选择器匹配的节点
     * @param rootNode 根节点
     * @param selectors 选择器列表
     * @param matchOption 匹配选项
     * @param requireAll 是否要求全部匹配
     * @return 匹配的节点或 null
     */
    private fun queryRuleSelectors(
        rootNode: AccessibilityNodeInfo,
        selectors: List<Selector>,
        matchOption: MatchOption,
        requireAll: Boolean
    ): AccessibilityNodeInfo? {
        if (selectors.isEmpty()) return null

        return if (requireAll) {
            // 全部必须匹配
            var resultNode: AccessibilityNodeInfo? = null
            for (selector in selectors) {
                resultNode = queryFirst(rootNode, selector, matchOption) ?: return null
            }
            resultNode
        } else {
            // 任一匹配即可
            for (selector in selectors) {
                queryFirst(rootNode, selector, matchOption)?.let { return it }
            }
            null
        }
    }

    /**
     * 单次查询首个命中节点，带本轮检查缓存。
     *
     * 订阅里几千条规则会反复查同一棵树，同一 (窗口, 选择器) 在一轮检查内结果必然一致，
     * 缓存后可以省掉大量重复遍历；缓存只跨一轮，页面变化后会重建。
     */
    private fun queryFirst(
        rootNode: AccessibilityNodeInfo,
        selector: Selector,
        matchOption: MatchOption
    ): AccessibilityNodeInfo? {
        val key = queryCacheKey(rootNode, selector)
        if (passQueryCache.containsKey(key)) return passQueryCache[key]
        val node = transform.querySelectorAll(rootNode, selector, matchOption).firstOrNull()
        passQueryCache[key] = node
        return node
    }

    /**
     * 执行规则自带的动作。
     *
     * @param node 命中节点，用于取屏幕坐标
     * @param action 已归一化为小写的动作名
     * @return 是否执行成功
     */
    private fun performSelfAction(node: AccessibilityNodeInfo, action: String): Boolean {
        val service = ServiceStateBus.getAccessibilityService() ?: return false
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        val cx = rect.centerX()
        val cy = rect.centerY()

        return when (action) {
            "back" -> runCatching {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }.getOrDefault(false)

            "clickcenter" -> dispatchTap(service, cx, cy, 100L)

            // 优先走无障碍标准长按；节点不支持时退回坐标长按
            "longclick" -> runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            }.getOrDefault(false) || dispatchTap(service, cx, cy, 600L)

            else -> false
        }
    }

    /** 在指定坐标派发一次手势；durationMs 大于 500 即视为长按 */
    private fun dispatchTap(
        service: AccessibilityService,
        x: Int,
        y: Int,
        durationMs: Long
    ): Boolean {
        if (x < 0 || y < 0) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    /**
     * 查询所有匹配的节点
     */
    private fun queryAllMatchedNodes(
        rootNode: AccessibilityNodeInfo,
        rule: ResolvedGKDRule
    ): List<AccessibilityNodeInfo> {
        val matchOption = MatchOption(fastQuery = rule.fastQuery ?: false)
        val nodes = mutableSetOf<AccessibilityNodeInfo>()

        // 添加 anyMatches 匹配的节点
        for (selector in rule.anyMatches) {
            nodes.addAll(transform.querySelectorAll(rootNode, selector, matchOption))
        }

        // 添加 matches 匹配的节点
        for (selector in rule.matches) {
            nodes.addAll(transform.querySelectorAll(rootNode, selector, matchOption))
        }

        return nodes.toList()
    }

    /**
     * 执行触发
     */
    private fun performTrigger(
        context: Context,
        state: GKDTriggerState,
        rule: ResolvedGKDRule,
        ruleState: RuleExecutionState,
        node: AccessibilityNodeInfo
    ) {
        // 冷却与最大触发次数检查（matchDelay/matchTime 已在入口处按持续出现语义处理）
        if (!ruleState.canTrigger()) {
            return
        }

        // 更新状态
        ruleState.recordTrigger(System.currentTimeMillis())

        // 更新最后触发的规则（用于 preRules 检查）
        state.lastTriggerRule = rule

        // 规则自带非点击动作（back / clickCenter / longClick）由触发器直接执行，
        // 不再把命中节点交给工作流去点 —— 否则 back 规则会变成「点广告里的某个控件」，
        // 轻则没反应，重则跳到别的页面。其余动作（含未知值）维持旧行为，交给工作流。
        val selfAction = rule.action
        if (selfAction == "back" || selfAction == "clickcenter" || selfAction == "longclick") {
            val ok = performSelfAction(node, selfAction)
            DebugLogger.d(
                TAG,
                "规则 '${rule.name}' 自带动作 $selfAction，已由触发器直接执行(ok=$ok)，跳过工作流"
            )
            return
        }

        // 创建 VScreenElement
        val element = VScreenElement.fromAccessibilityNode(node, calculateDepth(node))

        // 查询所有匹配的节点
        val allNodes = queryAllMatchedNodes(node, rule)
        val allElements = allNodes.map { VScreenElement.fromAccessibilityNode(it, calculateDepth(it)) }

        DebugLogger.d(
            TAG,
            "GKD订阅触发: 规则=${rule.name}, " +
                    "element=${element.asString()}, " +
                    "key=${rule.key}, " +
                    "actionCount=${ruleState.actionCount}"
        )

        executeTrigger(
            context = context,
            trigger = state.trigger,
            triggerData = GKDTriggerData(
                element = element,
                allElements = allElements,
                ruleName = rule.name,
                ruleGroup = rule.groupName
            )
        )
    }

    /**
     * 创建 Transform 实例
     */
    private fun createTransform(): Transform<AccessibilityNodeInfo> {
        return Transform(
            getAttr = { target, name ->
                when (target) {
                    is QueryContext<*> -> when (name) {
                        "prev" -> target.prev
                        "current" -> target.current
                        else -> getNodeAttr(target.current as AccessibilityNodeInfo, name)
                    }
                    is AccessibilityNodeInfo -> getNodeAttr(target, name)
                    else -> null
                }
            },
            getInvoke = getInvoke@{ target, name, args ->
                when (target) {
                    is QueryContext<*> -> getNodeInvoke(target.current as AccessibilityNodeInfo, name, args)
                    is AccessibilityNodeInfo -> getNodeInvoke(target, name, args)
                    else -> null
                }
            },
            getName = { node -> node.className?.toString() },
            getChildren = { node ->
                sequence {
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { yield(it) }
                    }
                }
            },
            getParent = { node -> node.parent },
            traverseFastQueryDescendants = { rootNode, fastQueryList ->
                fastQueryDescendants(rootNode, fastQueryList)
            }
        )
    }

    /**
     * 快速查询后代节点 - 使用 Android Accessibility API
     */
    private fun fastQueryDescendants(
        rootNode: AccessibilityNodeInfo,
        fastQueryList: List<FastQuery>
    ): Sequence<AccessibilityNodeInfo> {
        return sequence {
            for (fq in fastQueryList) {
                when (fq) {
                    is FastQuery.Text -> {
                        // 使用 findAccessibilityNodeInfosByText
                        val nodes = rootNode.findAccessibilityNodeInfosByText(fq.value)
                        for (node in nodes) {
                            // 验证节点是否是 rootNode 的后代
                            if (isDescendantOf(rootNode, node)) {
                                yield(node)
                            }
                        }
                    }
                    is FastQuery.Id -> {
                        // 使用 findAccessibilityNodeInfosByViewId
                        val nodes = rootNode.findAccessibilityNodeInfosByViewId(fq.value)
                        for (node in nodes) {
                            // 验证节点是否是 rootNode 的后代
                            if (isDescendantOf(rootNode, node)) {
                                yield(node)
                            }
                        }
                    }
                    is FastQuery.Vid -> {
                        // vid 需要拼接包名
                        val pkg = rootNode.packageName?.toString()
                        val vid = if (pkg != null && fq.value.startsWith(pkg)) {
                            fq.value
                        } else if (pkg != null) {
                            "$pkg:id/${fq.value}"
                        } else {
                            fq.value
                        }
                        val nodes = rootNode.findAccessibilityNodeInfosByViewId(vid)
                        DebugLogger.d(TAG, "fastQuery vid=$vid, 找到 ${nodes.size} 个节点")
                        for (node in nodes) {
                            // 验证节点是否是 rootNode 的后代
                            if (isDescendantOf(rootNode, node)) {
                                DebugLogger.d(TAG, "  ✓ 节点是后代，添加到候选: ${node.viewIdResourceName}")
                                yield(node)
                            } else {
                                DebugLogger.d(TAG, "  ✗ 节点不是后代，跳过: ${node.viewIdResourceName}")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 检查节点是否是另一个节点的后代
     */
    private fun isDescendantOf(ancestor: AccessibilityNodeInfo, node: AccessibilityNodeInfo): Boolean {
        if (ancestor == node) return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            val parent = current.parent
            if (parent == ancestor) return true
            // 防止无限循环（虽然理论上不应该发生）
            if (parent == node) break
            current = parent
        }
        return false
    }

    /**
     * 获取节点属性
     */
    private fun getNodeAttr(node: AccessibilityNodeInfo, name: String): Any? {
        return when (name) {
            "id" -> node.viewIdResourceName
            "vid" -> {
                // vid 处理：提取 viewIdResourceName 中的简短形式
                val id = node.viewIdResourceName ?: return null
                val pkg = node.packageName?.toString()
                if (pkg != null && id.startsWith(pkg) && id.contains(":id/")) {
                    id.substringAfterLast(":id/")
                } else {
                    id
                }
            }

            "name", "class", "className" -> node.className?.toString()
            "text" -> node.text?.toString()
            "desc", "contentDescription" -> node.contentDescription?.toString()

            "clickable" -> node.isClickable
            "focusable" -> node.isFocusable
            "checkable" -> node.isCheckable
            "checked" -> {
                // 使用 compatChecked 逻辑
                when (node.isChecked) {
                    true -> true
                    false -> false
                    else -> null  // CHECKED_STATE_PARTIAL
                }
            }

            "editable" -> node.isEditable
            "longClickable" -> node.isLongClickable
            "visibleToUser" -> node.isVisibleToUser
            "scrollable" -> node.isScrollable
            "selected" -> node.isSelected
            "focused" -> node.isFocused

            "childCount" -> node.childCount

            "index" -> getNodeIndex(node)

            else -> null
        }
    }

    /**
     * 获取节点在父节点中的索引
     */
    private fun getNodeIndex(node: AccessibilityNodeInfo): Int {
        val parent = node.parent ?: return -1
        for (i in 0 until parent.childCount) {
            if (parent.getChild(i) == node) {
                return i
            }
        }
        return -1
    }

    private fun getNodeInvoke(node: AccessibilityNodeInfo, name: String, args: List<Any>): Any? {
        return when (name) {
            "getChild" -> {
                val index = args.firstOrNull()?.toString()?.toIntOrNull() ?: 0
                if (index in 0 until node.childCount) node.getChild(index) else null
            }
            "getParent" -> node.parent
            else -> null
        }
    }

    private fun calculateDepth(node: AccessibilityNodeInfo): Int {
        var depth = 0
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            current = current.parent
            depth++
        }
        return depth
    }
}
