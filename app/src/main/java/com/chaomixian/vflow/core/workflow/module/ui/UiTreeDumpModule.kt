package com.chaomixian.vflow.core.workflow.module.ui

import android.content.Context
import android.graphics.Rect
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
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.module.triggers.handlers.collectWindowRoots
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.services.AccessibilityService as VFlowAccessibilityService
import java.io.File

/**
 * 当前界面结构导出。
 *
 * 把无障碍树（含弹窗、浮层）导出成文本或 JSON，用于调选择器、排查「为什么这个按钮没被点到」。
 * 元素触发器与 GKD 触发器都按这棵树匹配，看到树就等于看到它们眼里的界面。
 */
class UiTreeDumpModule : BaseModule() {

    companion object {
        private const val OUTPUT_TREE = "tree"
        private const val OUTPUT_NODE_COUNT = "node_count"
        private const val OUTPUT_FILE_PATH = "file_path"

        private const val FORMAT_TEXT = "text"
        private const val FORMAT_JSON = "json"

        /** 节点数上限，防止异常页面把变量撑爆 */
        private const val MAX_NODES = 3000
    }

    override val id = "vflow.ui.dump_tree"

    override val metadata = ActionMetadata(
        name = "导出界面结构",
        description = "把当前界面的无障碍节点树导出为文本或 JSON，用于调试选择器与排查控件定位问题。",
        iconRes = com.chaomixian.vflow.R.drawable.rounded_data_object_24,
        category = "界面交互",
        categoryId = "interaction"
    )

    override val aiMetadata = AiModuleMetadata(
        usageScopes = setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW),
        riskLevel = AiModuleRiskLevel.READ_ONLY,
        workflowStepDescription = "Dump the current accessibility UI tree (text or JSON) into a variable for selector debugging."
    )

    override val requiredPermissions = listOf(PermissionManager.ACCESSIBILITY)

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            "format",
            "导出格式",
            ParameterType.ENUM,
            FORMAT_TEXT,
            options = listOf(FORMAT_TEXT, FORMAT_JSON),
            optionsStringRes = emptyList(),
            acceptsMagicVariable = false,
            hint = "text 便于肉眼比对，json 便于二次解析"
        ),
        InputDefinition(
            "max_depth",
            "最大层级",
            ParameterType.NUMBER,
            30,
            acceptsMagicVariable = false,
            hint = "超过该层级的子节点不再展开"
        ),
        InputDefinition(
            "only_text_nodes",
            "只导出有文字或可点击的节点",
            ParameterType.BOOLEAN,
            true,
            acceptsMagicVariable = false,
            hint = "关掉会连布局容器一起导出，结构完整但内容很长"
        ),
        InputDefinition(
            "save_path",
            "保存到文件",
            ParameterType.STRING,
            "",
            acceptsMagicVariable = true,
            supportsRichText = false,
            isFolded = true,
            hint = "留空则只写入变量，不落盘"
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition(OUTPUT_TREE, "界面结构", VTypeRegistry.STRING.id),
        OutputDefinition(OUTPUT_NODE_COUNT, "节点数量", VTypeRegistry.NUMBER.id),
        OutputDefinition(OUTPUT_FILE_PATH, "保存路径", VTypeRegistry.STRING.id)
    )

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        val service = context.services.get(VFlowAccessibilityService::class)
            ?: return ExecutionResult.Failure("导出失败", "无障碍服务未启动")

        val roots = collectWindowRoots(service)
        if (roots.isEmpty()) {
            return ExecutionResult.Failure("导出失败", "没有获取到任何窗口，可能当前界面不可访问")
        }

        val format = context.getVariableAsString("format", FORMAT_TEXT)
        val maxDepth = context.getVariableAsInt("max_depth") ?: 30
        val onlyTextNodes = context.getVariableAsBoolean("only_text_nodes") ?: true
        val savePath = context.getVariableAsString("save_path", "")

        onProgress(ProgressUpdate("正在读取界面结构…"))

        var count = 0
        val jsonItems = StringBuilder()
        val textLines = StringBuilder()

        fun appendNode(windowIndex: Int, node: AccessibilityNodeInfo, depth: Int) {
            if (count >= MAX_NODES) return
            val bounds = Rect()
            runCatching { node.getBoundsInScreen(bounds) }
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val clickable = runCatching { node.isClickable }.getOrDefault(false)
            val scrollable = runCatching { node.isScrollable }.getOrDefault(false)

            val keep = !onlyTextNodes || text != null || desc != null || clickable
            if (keep) {
                count++
                val className = node.className?.toString()?.substringAfterLast('.') ?: "?"
                val viewId = node.viewIdResourceName

                if (format == FORMAT_JSON) {
                    if (jsonItems.isNotEmpty()) jsonItems.append(',')
                    jsonItems.append('{')
                    jsonItems.append("\"w\":").append(windowIndex)
                    jsonItems.append(",\"d\":").append(depth)
                    jsonItems.append(",\"class\":\"").append(escape(className)).append('"')
                    jsonItems.append(",\"id\":\"").append(escape(viewId ?: "")).append('"')
                    jsonItems.append(",\"text\":\"").append(escape(text ?: "")).append('"')
                    jsonItems.append(",\"desc\":\"").append(escape(desc ?: "")).append('"')
                    jsonItems.append(",\"bounds\":[").append(bounds.left).append(',')
                        .append(bounds.top).append(',').append(bounds.right).append(',')
                        .append(bounds.bottom).append(']')
                    jsonItems.append(",\"clickable\":").append(clickable)
                    jsonItems.append(",\"scrollable\":").append(scrollable)
                    jsonItems.append('}')
                } else {
                    textLines.append("  ".repeat(depth.coerceAtMost(20)))
                    textLines.append('[').append(windowIndex).append('/').append(depth).append("] ")
                    textLines.append(className)
                    if (!viewId.isNullOrEmpty()) textLines.append(" id=").append(viewId)
                    if (clickable) textLines.append(" 可点击")
                    if (scrollable) textLines.append(" 可滚动")
                    textLines.append(" (").append(bounds.left).append(',').append(bounds.top)
                        .append('-').append(bounds.right).append(',').append(bounds.bottom).append(')')
                    if (text != null) textLines.append(" 文字=\"").append(text).append('"')
                    if (desc != null) textLines.append(" 描述=\"").append(desc).append('"')
                    textLines.append('\n')
                }
            }

            if (depth >= maxDepth) return
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                appendNode(windowIndex, child, depth + 1)
            }
        }

        roots.forEachIndexed { index, root ->
            runCatching { appendNode(index, root, 0) }
        }

        val tree = if (format == FORMAT_JSON) "[$jsonItems]" else textLines.toString()

        var writtenPath = ""
        if (savePath.isNotBlank()) {
            runCatching {
                val file = File(savePath)
                file.parentFile?.mkdirs()
                file.writeText(tree)
                writtenPath = file.absolutePath
            }.onFailure {
                writtenPath = ""
            }
        }

        onProgress(ProgressUpdate("已导出 $count 个节点"))

        return ExecutionResult.Success(
            mapOf(
                OUTPUT_TREE to VString(tree),
                OUTPUT_NODE_COUNT to VNumber(count),
                OUTPUT_FILE_PATH to VString(writtenPath)
            )
        )
    }

    override fun validate(step: ActionStep, allSteps: List<ActionStep>): ValidationResult =
        ValidationResult(true)

    override fun createSteps(): List<ActionStep> =
        listOf(ActionStep(moduleId = this.id, parameters = emptyMap()))

    override fun getSummary(context: Context, step: ActionStep): CharSequence = "导出当前界面结构"

    /** JSON 字符串转义 */
    private fun escape(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        for (ch in raw) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(' ') else sb.append(ch)
            }
        }
        return sb.toString()
    }
}
