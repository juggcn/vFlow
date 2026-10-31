package com.chaomixian.vflow.core.workflow.module.triggers.handlers

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.types.complex.VScreenElement
import com.chaomixian.vflow.core.utils.getCompatStableId
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.module.triggers.*
import com.chaomixian.vflow.services.ServiceStateBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import li.songe.selector.MatchOption
import li.songe.selector.QueryContext
import li.songe.selector.Selector
import li.songe.selector.Transform
import li.songe.selector.property.CompareOperator
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 元素触发器处理器
 * 监听页面变化，匹配选择器并触发工作流
 */
class ElementTriggerHandler : ListeningTriggerHandler() {

    companion object {
        private const val TAG = "ElementTriggerHandler"
    }

    // 存储所有工作流的状态（动态创建）
    private val triggerStates = CopyOnWriteArrayList<ElementTriggerState>()

    // Transform 实例
    private val transform = createTransform()

    // 监听Job
    private var listeningJob: Job? = null

    // 事件后补查Job（等控件延迟渲染）
    private var recheckJob: Job? = null

    // 串行化匹配检查：事件流与补查可能并发执行，避免状态竞争
    private val checkMutex = Mutex()

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun startListening(context: Context) {
        DebugLogger.d(TAG, "开始监听元素变化")

        syncTriggerStates()

        listeningJob = triggerScope.launch {
            // 同时监听窗口变化和内容变化事件
            launch {
                ServiceStateBus.windowChangeEventFlow.collect { (packageName, className) ->
                    DebugLogger.d(TAG, "收到窗口变化事件: $packageName / $className")
                    checkAndTriggerWorkflows(context)
                    scheduleRecheck(context)
                }
            }
            launch {
                // 内容变化事件高频（列表滚动、倒计时文字每秒/每 100ms 刷新可达几十次）。
                // 用 sample 而非 debounce：debounce 在事件持续不断时会被一直重置而永不 emit，
                // 倒计时按钮就会整段漏掉；sample 按固定周期取最新值，事件再密也一定会触发检查。
                ServiceStateBus.windowContentChangedFlow
                    .sample(TRIGGER_CONTENT_DEBOUNCE_MS)
                    .collect { (packageName, className) ->
                        DebugLogger.d(TAG, "收到窗口内容变化事件: $packageName / $className")
                        checkAndTriggerWorkflows(context)
                        scheduleRecheck(context)
                    }
            }
        }
    }

    /**
     * 事件后的短周期补查：控件常在页面出现后 50~300ms 才渲染完成，
     * 事件瞬间查一遍树可能漏掉，未命中时每隔一小段时间重查（新事件会取消上一轮）。
     */
    private fun scheduleRecheck(context: Context) {
        recheckJob?.cancel()
        recheckJob = triggerScope.launch {
            repeat(TRIGGER_RECHECK_TIMES) {
                delay(TRIGGER_RECHECK_INTERVAL_MS)
                checkAndTriggerWorkflows(context)
            }
        }
    }

    override fun stopListening(context: Context) {
        DebugLogger.d(TAG, "停止监听元素变化")
        listeningJob?.cancel()
        listeningJob = null
        recheckJob?.cancel()
        recheckJob = null
        triggerStates.clear()
    }

    /**
     * 同步 triggerStates 与 listeningTriggers
     */
    private fun syncTriggerStates() {
        val currentTriggerIds = listeningTriggers.map { it.triggerId }.toSet()

        triggerStates.removeAll { state ->
            !currentTriggerIds.contains(state.trigger.triggerId)
        }

        for (trigger in listeningTriggers) {
            if (triggerStates.none { it.trigger.triggerId == trigger.triggerId }) {
                val state = createTriggerState(trigger)
                if (state != null) {
                    triggerStates.add(state)
                    DebugLogger.d(TAG, "加载触发器 '${trigger.triggerId}'")
                }
            }
        }
    }

    /**
     * 创建触发器状态
     */
    private fun createTriggerState(trigger: TriggerSpec): ElementTriggerState? {
        val config = trigger.parameters

        val selectorString = config["selector"] as? String
        if (selectorString.isNullOrBlank()) {
            return null
        }

        val selector = try {
            Selector.parse(selectorString)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "解析选择器失败: $selectorString", e)
            return null
        }

        return ElementTriggerState(
            trigger = trigger,
            selector = selector,
            matchDelay = (config["matchDelay"] as? Number)?.toLong() ?: 0L,
            actionDelay = (config["actionDelay"] as? Number)?.toLong() ?: 0L,
            matchTime = (config["matchTime"] as? Number)?.toLong() ?: 0L,
            actionCd = (config["actionCd"] as? Number)?.toLong() ?: 1000L,
            actionMaximum = (config["actionMaximum"] as? Number)?.toInt()
        )
    }

    /**
     * 检查并触发匹配的工作流
     */
    private suspend fun checkAndTriggerWorkflows(context: Context) {
        // 先同步状态
        syncTriggerStates()

        val service = ServiceStateBus.getAccessibilityService() ?: return
        // 扫描所有窗口（含 Dialog / PopupWindow 等弹窗层），任一窗口命中即可
        val windowRoots = collectWindowRoots(service)
        if (windowRoots.isEmpty()) return

        checkMutex.withLock {
            checkAndTriggerWorkflowsLocked(context, windowRoots)
        }
    }

    private fun checkAndTriggerWorkflowsLocked(context: Context, windowRoots: List<AccessibilityNodeInfo>) {

        val t = System.currentTimeMillis()

        // 用于同一次检查中的去重（避免同一节点触发多个选择器）
        val matchedThisTime = mutableSetOf<Int?>()

        for (state in triggerStates) {
            try {
                // 1. 匹配选择器：任一窗口命中即可
                var matchedNode: AccessibilityNodeInfo? = null
                for (windowRoot in windowRoots) {
                    val node = transform.querySelectorAll(windowRoot, state.selector, MatchOption(fastQuery = true))
                        .firstOrNull()
                    if (node != null) {
                        matchedNode = node
                        break
                    }
                }

                if (matchedNode == null) {
                    // 节点消失：重置首次匹配时间，下次重新累计 matchDelay
                    if (state.matchChangedTime != 0L) {
                        state.matchChangedTime = 0L
                    }
                    continue
                }

                // 2. 记录首次匹配时间（matchDelay / matchTime 的计时起点）
                if (state.matchChangedTime == 0L) {
                    state.matchChangedTime = t
                }

                // 3. 状态检查（冷却 / matchDelay 持续时长 / matchTime 有效窗 / 次数）
                val status = state.getStatus()
                if (status != TriggerStatus.Ready) {
                    continue
                }

                // 4. 同一次检查中去重（避免不同选择器匹配到同一节点重复触发）
                val nodeId = matchedNode.getCompatStableId()
                if (nodeId in matchedThisTime) {
                    continue
                }
                matchedThisTime.add(nodeId)

                // 5. 处理触发延迟
                val nodeForTrigger = matchedNode
                if (state.actionDelay > 0) {
                    state.actionDelayTriggerTime = t
                    triggerScope.launch {
                        delay(state.actionDelay)
                        performTrigger(context, state, nodeForTrigger)
                    }
                } else {
                    performTrigger(context, state, nodeForTrigger)
                }
            } catch (e: Exception) {
                DebugLogger.e(TAG, "处理工作流 '${state.trigger.workflowName}' 时出错", e)
            }
        }
    }

    /**
     * 执行触发
     */
    private fun performTrigger(context: Context, state: ElementTriggerState, node: AccessibilityNodeInfo) {
        // 检查冷却时间
        if (state.getStatus() != TriggerStatus.Ready) {
            return
        }

        // 创建 VScreenElement
        val element = VScreenElement.fromAccessibilityNode(node, calculateDepth(node))

        // 调用 WorkflowExecutor
        executeTrigger(context, state.trigger, ElementTriggerData(element))

        // 更新状态
        state.actionTriggerTime = System.currentTimeMillis()
        state.actionCount++
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
     * 快速查询后代节点
     */
    private fun fastQueryDescendants(
        rootNode: AccessibilityNodeInfo,
        fastQueryList: List<li.songe.selector.FastQuery>
    ): Sequence<AccessibilityNodeInfo> {
        return sequence {
            val stack = mutableListOf(rootNode)
            while (stack.isNotEmpty()) {
                val node = stack.removeAt(stack.size - 1)

                // 检查节点是否匹配所有 fastQuery 条件
                var match = true
                for (fq in fastQueryList) {
                    when (fq) {
                        is li.songe.selector.FastQuery.Text -> {
                            val nodeText = node.text?.toString() ?: ""
                            val matched = when (fq.operator) {
                                is CompareOperator.Equal -> nodeText == fq.value
                                is CompareOperator.NotEqual -> nodeText != fq.value
                                is CompareOperator.Less -> nodeText < fq.value
                                is CompareOperator.LessEqual -> nodeText <= fq.value
                                is CompareOperator.More -> nodeText > fq.value
                                is CompareOperator.MoreEqual -> nodeText >= fq.value
                                is CompareOperator.Start -> nodeText.startsWith(fq.value)
                                is CompareOperator.Include -> nodeText.contains(fq.value)
                                is CompareOperator.End -> nodeText.endsWith(fq.value)
                                else -> false
                            }
                            if (!matched) {
                                match = false
                                break
                            }
                        }
                        is li.songe.selector.FastQuery.Id -> {
                            if (node.viewIdResourceName != fq.value) {
                                match = false
                                break
                            }
                        }
                        is li.songe.selector.FastQuery.Vid -> {
                            val id = node.viewIdResourceName ?: continue
                            val pkg = node.packageName?.toString()
                            val vid = if (pkg != null && id.startsWith(pkg) && id.startsWith(":id/", pkg.length)) {
                                id.substring(pkg.length + ":id/".length)
                            } else {
                                id
                            }
                            if (vid != fq.value) {
                                match = false
                                break
                            }
                        }
                    }
                }

                if (match) {
                    yield(node)
                }

                // 添加子节点到栈中
                for (i in node.childCount - 1 downTo 0) {
                    node.getChild(i)?.let { stack.add(it) }
                }
            }
        }
    }

    /**
     * 获取节点属性
     */
    private fun getNodeAttr(node: Any, name: String): Any? {
        if (node !is AccessibilityNodeInfo) return null

        return when (name) {
            "text" -> node.text?.toString()
            "desc", "contentDescription" -> node.contentDescription?.toString()
            "id" -> node.viewIdResourceName
            "vid" -> {
                val id = node.viewIdResourceName ?: return null
                val pkg = node.packageName?.toString() ?: return null
                if (id.startsWith(pkg) && id.startsWith(":id/", pkg.length)) {
                    id.substring(pkg.length + ":id/".length)
                } else {
                    id
                }
            }
            "viewId" -> node.viewIdResourceName
            "name", "class", "className" -> node.className?.toString()
            "_id" -> node.getCompatStableId()
            "clickable" -> node.isClickable
            "enabled" -> node.isEnabled
            "checkable" -> node.isCheckable
            "checked" -> node.isChecked
            "focusable" -> node.isFocusable
            "focused" -> node.isFocused
            "scrollable" -> node.isScrollable
            "longClickable" -> node.isLongClickable
            "selected" -> node.isSelected
            "editable" -> node.isEditable
            "visibleToUser" -> node.isVisibleToUser
            "childCount" -> node.childCount
            "depth" -> calculateDepth(node)
            "parent" -> node.parent
            "index" -> {
                val parent = node.parent
                var result = -1
                if (parent != null) {
                    for (i in 0 until parent.childCount) {
                        if (parent.getChild(i) == node) {
                            result = i
                            break
                        }
                    }
                }
                result
            }
            "left" -> getBounds(node).left
            "top" -> getBounds(node).top
            "right" -> getBounds(node).right
            "bottom" -> getBounds(node).bottom
            "width" -> getBounds(node).width()
            "height" -> getBounds(node).height()
            else -> null
        }
    }

    private fun getBounds(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }

    private fun getNodeInvoke(node: Any, name: String, args: List<Any>): Any? {
        if (node !is AccessibilityNodeInfo) return null

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

/**
 * 元素触发器触发数据
 */
data class ElementTriggerData(
    val element: VScreenElement
) : android.os.Parcelable {
    override fun toString(): String = "ElementTriggerData(element=${element.asString()})"

    companion object CREATOR : android.os.Parcelable.Creator<ElementTriggerData> {
        override fun createFromParcel(parcel: android.os.Parcel): ElementTriggerData {
            return ElementTriggerData(
                element = parcel.readParcelable(VScreenElement::class.java.classLoader)!!
            )
        }

        override fun newArray(size: Int): Array<ElementTriggerData?> {
            return arrayOfNulls(size)
        }
    }

    override fun writeToParcel(parcel: android.os.Parcel, flags: Int) {
        parcel.writeParcelable(element, flags)
    }

    override fun describeContents(): Int = 0
}
