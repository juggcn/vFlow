// 文件: main/java/com/chaomixian/vflow/services/InteractionRecorder.kt
// 描述: 交互录制器。监听无障碍点击/输入事件，记录「点了哪个控件」，并转换成工作流步骤。
//
// 设计要点:
// 1. 不拦触摸。录制层只要不吃事件，用户就能看到真实界面反馈，录完即所得；
// 2. 每次点击当场生成候选选择器（复用 SelectorCandidateBuilder），录制结束时已经算好，
//    停止录制不需要再扫一遍界面；
// 3. 步骤靠 ModuleRegistry.createSteps() 拿默认参数再覆盖，避免手写参数漏字段；
// 4. 每次 copy 必须换新 id，否则多个步骤 id 相同会被 DiffUtil 当成同一条。

package com.chaomixian.vflow.services

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.chaomixian.vflow.core.inspect.SelectorCandidateBuilder
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.module.triggers.handlers.collectWindowRoots
import java.util.UUID

object InteractionRecorder {

    private const val TAG = "InteractionRecorder"

    /** 最多保留的录制条数，防止长时间录制把内存撑爆 */
    const val MAX_CAPTURED = 60

    /** 同一节点该间隔内的重复点击视为连点，只留一条 */
    private const val DUPLICATE_CLICK_WINDOW_MS = 250L

    /** 同一节点该间隔内的连续输入合并成一条 */
    private const val INPUT_MERGE_WINDOW_MS = 2000L

    enum class Mode { IDLE, RECORD }

    enum class Kind { CLICK, INPUT }

    /**
     * 一条录制记录。
     *
     * @param selector 首选选择器；为空表示该控件没有任何可识别信息，回放时只能退回坐标
     * @param unique 首选选择器是否唯一命中
     */
    class Captured(
        val index: Int,
        val kind: Kind,
        val packageName: String,
        val activityClass: String,
        val selector: String?,
        val selectorLabel: String,
        val unique: Boolean,
        val x: Int,
        val y: Int,
        val text: String,
        val fingerprint: String,
        val at: Long
    ) {
        /** 菜单/Toast 里显示的一行摘要 */
        val display: String
            get() {
                val name = when {
                    text.isNotBlank() -> "输入「${text.take(12)}」"
                    selector != null -> selector.take(40)
                    else -> "($x,$y)"
                }
                val mark = if (selector != null && !unique) "?" else ""
                return "$index. $name$mark"
            }
    }

    @Volatile
    var mode: Mode = Mode.IDLE
        private set

    private val captured = mutableListOf<Captured>()
    private var nextIndex = 1

    /** 是否正在录制 */
    val isRecording: Boolean get() = mode == Mode.RECORD

    /** 已录制的条数 */
    val capturedCount: Int
        @Synchronized get() = captured.size

    @Synchronized
    fun startRecording() {
        captured.clear()
        nextIndex = 1
        mode = Mode.RECORD
        DebugLogger.d(TAG, "开始录制交互")
    }

    /** 停止录制，返回录制到的条数，数据保留供生成步骤 */
    @Synchronized
    fun stopRecording(): Int {
        mode = Mode.IDLE
        val count = captured.size
        DebugLogger.d(TAG, "停止录制，共 $count 条")
        return count
    }

    @Synchronized
    fun clear() {
        captured.clear()
        nextIndex = 1
        mode = Mode.IDLE
    }

    @Synchronized
    fun snapshot(): List<Captured> = captured.toList()

    // region 事件捕获

    /**
     * 无障碍事件入口。只在录制中处理点击与文本变化。
     * 由 AccessibilityService.onAccessibilityEvent 调用，主线程执行。
     */
    fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent) {
        if (mode != Mode.RECORD) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            return
        }

        // 忽略 vFlow 自己（悬浮窗操作、编辑器内点击）
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank() || packageName == service.packageName) return

        val node: AccessibilityNodeInfo = event.source ?: return

        // 输入事件是逐字触发的，每次都遍历整棵树统计命中数会让输入明显发顿。
        // 输入框靠 vid / text 定位已经够用，所以这里跳过命中统计（roots 传空 = 一律视为唯一）。
        val isInput = type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        val roots = if (isInput) {
            emptyList()
        } else {
            runCatching { collectWindowRoots(service) }.getOrDefault(emptyList())
        }
        val result = runCatching { SelectorCandidateBuilder.inspect(node, roots) }.getOrNull() ?: run {
            DebugLogger.d(TAG, "节点信息不可用，跳过本次记录")
            return
        }

        val activityClass = ServiceStateBus.lastActivityClassName.orEmpty()
        val fingerprint = SelectorCandidateBuilder.fingerprint(result)
        val best = result.best
        val now = System.currentTimeMillis()

        if (isInput) {
            val value = event.text?.joinToString("").orEmpty()
            if (value.isEmpty()) return
            appendInput(packageName, activityClass, fingerprint, value, result, best, now)
            return
        }

        // 点击事件：event.source 常常是不可点击的子节点，取沿父链找到的可点击节点
        val clickBounds = android.graphics.Rect().also { result.clickNode.getBoundsInScreen(it) }
        appendClick(
            packageName = packageName,
            activityClass = activityClass,
            fingerprint = fingerprint,
            x = clickBounds.centerX(),
            y = clickBounds.centerY(),
            bestSelector = best?.selector,
            bestLabel = best?.label.orEmpty(),
            unique = best?.unique == true,
            now = now
        )
    }

    @Synchronized
    private fun appendClick(
        packageName: String,
        activityClass: String,
        fingerprint: String,
        x: Int,
        y: Int,
        bestSelector: String?,
        bestLabel: String,
        unique: Boolean,
        now: Long
    ) {
        // 连点去重：同一节点短时间内重复点击只记一次
        captured.lastOrNull()?.let { last ->
            if (last.kind == Kind.CLICK &&
                last.fingerprint == fingerprint &&
                now - last.at <= DUPLICATE_CLICK_WINDOW_MS
            ) {
                return
            }
        }
        if (captured.size >= MAX_CAPTURED) {
            DebugLogger.w(TAG, "已达到 $MAX_CAPTURED 条上限，后续操作不再记录")
            return
        }
        captured.add(
            Captured(
                index = nextIndex++,
                kind = Kind.CLICK,
                packageName = packageName,
                activityClass = activityClass,
                selector = bestSelector,
                selectorLabel = bestLabel,
                unique = unique,
                x = x,
                y = y,
                text = "",
                fingerprint = fingerprint,
                at = now
            )
        )
    }

    @Synchronized
    private fun appendInput(
        packageName: String,
        activityClass: String,
        fingerprint: String,
        value: String,
        result: com.chaomixian.vflow.core.inspect.InspectResult,
        best: com.chaomixian.vflow.core.inspect.SelectorCandidate?,
        now: Long
    ) {
        // 连续输入合并：同一个输入框 2 秒内的多次文本变化只保留最后一次
        captured.lastOrNull()?.let { last ->
            if (last.kind == Kind.INPUT &&
                last.fingerprint == fingerprint &&
                now - last.at <= INPUT_MERGE_WINDOW_MS
            ) {
                captured[captured.lastIndex] = Captured(
                    index = last.index,
                    kind = Kind.INPUT,
                    packageName = packageName,
                    activityClass = activityClass,
                    selector = best?.selector,
                    selectorLabel = best?.label.orEmpty(),
                    unique = best?.unique == true,
                    x = last.x,
                    y = last.y,
                    text = value,
                    fingerprint = fingerprint,
                    at = now
                )
                return
            }
        }
        // 首次输入：把输入框的位置也带上，回放时先点一下再输入，避免焦点不在输入框
        val bounds = android.graphics.Rect().also { result.clickNode.getBoundsInScreen(it) }
        if (captured.size >= MAX_CAPTURED) return
        captured.add(
            Captured(
                index = nextIndex++,
                kind = Kind.INPUT,
                packageName = packageName,
                activityClass = activityClass,
                selector = best?.selector,
                selectorLabel = best?.label.orEmpty(),
                unique = best?.unique == true,
                x = bounds.centerX(),
                y = bounds.centerY(),
                text = value,
                fingerprint = fingerprint,
                at = now
            )
        )
    }

    // endregion

    // region 生成步骤

    /**
     * 把录制结果转换成工作流步骤。
     *
     * - 点击：有选择器就用选择器，没有则退回坐标；
     * - 输入：先点一次输入框再输入（保证焦点）；
     * - 页面（Activity）发生变化时，在该动作前插入「等待界面稳定」。
     */
    fun toActionSteps(): List<ActionStep> {
        val items = snapshot()
        if (items.isEmpty()) return emptyList()

        val steps = mutableListOf<ActionStep>()
        var previousActivity: String? = null
        var previousFingerprint: String? = null

        for (item in items) {
            if (previousActivity != null &&
                item.activityClass.isNotBlank() &&
                item.activityClass != previousActivity
            ) {
                newStep("vflow.ui.wait_stable", emptyMap())?.let { steps.add(it) }
            }

            when (item.kind) {
                Kind.CLICK -> {
                    val target = item.selector ?: "${item.x},${item.y}"
                    newStep("vflow.device.click", mapOf("target" to target))?.let { steps.add(it) }
                }

                Kind.INPUT -> {
                    if (item.text.isNotBlank()) {
                        // 输入前先点一下输入框保证焦点；录制时已经点过的就不重复点了
                        if (previousFingerprint != item.fingerprint) {
                            val target = item.selector ?: "${item.x},${item.y}"
                            newStep("vflow.device.click", mapOf("target" to target))
                                ?.let { steps.add(it) }
                        }
                        newStep("vflow.interaction.input_text", mapOf("text" to item.text))
                            ?.let { steps.add(it) }
                    }
                }
            }

            if (item.activityClass.isNotBlank()) previousActivity = item.activityClass
            previousFingerprint = item.fingerprint
        }

        DebugLogger.d(TAG, "由 ${items.size} 条记录生成 ${steps.size} 个步骤")
        return steps
    }

    /** 用模块默认参数建步骤再覆盖，避免漏字段；id 必须新生成 */
    private fun newStep(moduleId: String, overrides: Map<String, Any?>): ActionStep? {
        val module = ModuleRegistry.getModule(moduleId)
        if (module == null) {
            DebugLogger.w(TAG, "模块不存在，跳过: $moduleId")
            return null
        }
        val base = module.createSteps().firstOrNull() ?: return null
        return base.copy(
            parameters = if (overrides.isEmpty()) base.parameters else base.parameters + overrides,
            id = UUID.randomUUID().toString()
        )
    }

    // endregion
}
