package com.chaomixian.vflow.core.workflow.module.ui

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.chaomixian.vflow.core.execution.ExecutionContext
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
import com.chaomixian.vflow.core.module.ValidationResult
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.module.triggers.handlers.collectWindowRoots
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.services.AccessibilityService as VFlowAccessibilityService
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import kotlinx.coroutines.delay

/**
 * 等待界面稳定。
 *
 * 页面切换、广告渲染都是渐进的，固定 sleep 要么太短要么太慢。
 * 这里持续给无障碍树算一个指纹，连续 stable_ms 没变化就认为界面稳定，可以继续下一步。
 */
class WaitUiStableModule : BaseModule() {

    companion object {
        private const val OUTPUT_STABLE = "stable"
        private const val OUTPUT_ELAPSED_MS = "elapsed_ms"

        /** 指纹计算时最多遍历的节点数，防止异常页面卡住 */
        private const val MAX_WALK_NODES = 2000

        private const val INPUT_STABLE_MS = "stable_ms"
        private const val INPUT_TIMEOUT_MS = "timeout_ms"
        private const val INPUT_POLL_MS = "poll_ms"
    }

    override val id = "vflow.ui.wait_stable"

    override val metadata = ActionMetadata(
        name = "等待界面稳定",
        description = "轮询界面结构，连续一段时间没有变化即视为稳定，比固定延时更省时间也更可靠。",
        iconRes = com.chaomixian.vflow.R.drawable.rounded_avg_time_24,
        category = "界面交互",
        categoryId = "interaction"
    )

    override val aiMetadata = AiModuleMetadata(
        usageScopes = setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW),
        riskLevel = AiModuleRiskLevel.READ_ONLY,
        workflowStepDescription = "Wait until the accessibility UI stops changing for a given period, with a timeout."
    )

    override val requiredPermissions = listOf(PermissionManager.ACCESSIBILITY)

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            INPUT_STABLE_MS,
            "稳定时长(毫秒)",
            ParameterType.NUMBER,
            500,
            acceptsMagicVariable = true,
            hint = "界面连续这么久没有变化就算稳定"
        ),
        InputDefinition(
            INPUT_TIMEOUT_MS,
            "超时(毫秒)",
            ParameterType.NUMBER,
            5000,
            acceptsMagicVariable = true,
            hint = "超时后直接返回，输出 stable=false"
        ),
        InputDefinition(
            INPUT_POLL_MS,
            "轮询间隔(毫秒)",
            ParameterType.NUMBER,
            150,
            acceptsMagicVariable = false,
            isFolded = true
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition(OUTPUT_STABLE, "是否稳定", VTypeRegistry.BOOLEAN.id),
        OutputDefinition(OUTPUT_ELAPSED_MS, "耗时(毫秒)", VTypeRegistry.NUMBER.id)
    )

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        val service = context.services.get(VFlowAccessibilityService::class)
            ?: return ExecutionResult.Failure("等待失败", "无障碍服务未启动")

        val stableMs = (context.getVariableAsInt(INPUT_STABLE_MS) ?: 500).toLong().coerceAtLeast(50L)
        val timeoutMs = (context.getVariableAsInt(INPUT_TIMEOUT_MS) ?: 5000).toLong().coerceAtLeast(stableMs)
        val pollMs = (context.getVariableAsInt(INPUT_POLL_MS) ?: 150).toLong().coerceAtLeast(50L)

        val start = System.currentTimeMillis()
        var lastSignature: Int? = null
        var lastChangeAt = start
        var stable = false

        while (true) {
            val now = System.currentTimeMillis()
            val signature = signatureOf(service)
            if (signature != lastSignature) {
                lastSignature = signature
                lastChangeAt = now
            }

            if (now - lastChangeAt >= stableMs) {
                stable = true
                break
            }
            if (now - start >= timeoutMs) break

            delay(pollMs)
        }

        val elapsed = System.currentTimeMillis() - start
        onProgress(ProgressUpdate(if (stable) "界面已稳定，用时 ${elapsed} 毫秒" else "等待超时，用时 ${elapsed} 毫秒"))

        return ExecutionResult.Success(
            mapOf(
                OUTPUT_STABLE to VBoolean(stable),
                OUTPUT_ELAPSED_MS to VNumber(elapsed)
            )
        )
    }

    override fun validate(step: ActionStep, allSteps: List<ActionStep>): ValidationResult =
        ValidationResult(true)

    override fun createSteps(): List<ActionStep> =
        listOf(ActionStep(moduleId = this.id, parameters = emptyMap()))

    /** 卡片外侧摘要：直接带出稳定时长与超时两个参数，不用点开才知道设了多少 */
    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val inputs = getInputs()
        fun pillOf(id: String) = PillUtil.createPillFromParam(
            step.parameters[id],
            inputs.find { it.id == id }
        )
        return PillUtil.buildSpannable(
            context,
            "稳定 ",
            pillOf(INPUT_STABLE_MS),
            " 毫秒 · 超时 ",
            pillOf(INPUT_TIMEOUT_MS),
            " 毫秒"
        )
    }

    /** 给当前所有窗口的节点树算指纹：节点数 + 文本与类名的哈希，任一变化都会让指纹变化 */
    private fun signatureOf(service: VFlowAccessibilityService): Int {
        var hash = 7
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        collectWindowRoots(service).forEach { queue.add(it) }

        while (queue.isNotEmpty() && visited < MAX_WALK_NODES) {
            val node = queue.removeFirst()
            visited++
            hash = 31 * hash + (node.className?.hashCode() ?: 0)
            hash = 31 * hash + (node.text?.hashCode() ?: 0)
            hash = 31 * hash + (node.contentDescription?.hashCode() ?: 0)
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return 31 * hash + visited
    }
}
