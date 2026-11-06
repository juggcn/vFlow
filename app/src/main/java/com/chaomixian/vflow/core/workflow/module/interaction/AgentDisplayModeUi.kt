// 文件: main/java/com/chaomixian/vflow/core/workflow/module/interaction/AgentDisplayModeUi.kt
// 描述: 「AI 智能体」与「AutoGLM 智能体」共用的执行环境控件封装。
//
// 两个模块共用 partial_agent_editor.xml，执行环境是一组
// MaterialButtonToggleGroup（主屏幕 / 虚拟屏幕）。

package com.chaomixian.vflow.core.workflow.module.interaction

import android.widget.TextView
import com.chaomixian.vflow.R
import com.chaomixian.vflow.services.ShellManager
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors

internal object AgentDisplayModeUi {

    const val MAIN = "main_screen"
    const val VIRTUAL = "virtual_screen"

    /** 老工作流里可能存的是显示名（中/英），统一收敛到枚举值 */
    fun normalize(raw: String?): String = when (raw?.trim()) {
        VIRTUAL, "虚拟屏幕 (后台)", "Virtual Screen (Background)" -> VIRTUAL
        else -> MAIN
    }

    /** 读当前选中的执行环境 */
    fun selectedMode(group: MaterialButtonToggleGroup): String =
        if (group.checkedButtonId == R.id.btn_display_virtual) VIRTUAL else MAIN

    /**
     * 恢复选中态并挂监听。
     *
     * 必须先 check() 再 addOnButtonCheckedListener：反过来会在卡片打开、恢复初始值时
     * 立刻触发一次回调，把参数改写成默认值（和 AgentPresetChips 同一个坑）。
     */
    fun bind(
        group: MaterialButtonToggleGroup,
        hint: TextView,
        initialMode: String?,
        onModeChanged: (String) -> Unit
    ) {
        val mode = normalize(initialMode)
        group.check(if (mode == VIRTUAL) R.id.btn_display_virtual else R.id.btn_display_main)
        renderHint(hint, mode)

        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val selected = if (checkedId == R.id.btn_display_virtual) VIRTUAL else MAIN
            renderHint(hint, selected)
            onModeChanged(selected)
        }
    }

    /**
     * 虚拟屏依赖 Shizuku：VirtualDisplayManager 走的是 ShellManager 的用户服务，
     * 全局 shell 模式设成 root / core 时权限检查会显示「已满足」，执行到这一步必然失败。
     * 提前把这条提示摆出来，并用警示色区分「只是说明」和「现在就跑不了」。
     */
    private fun renderHint(hint: TextView, mode: String) {
        val virtual = mode == VIRTUAL
        // 只有虚拟屏才关心 Shizuku；主屏模式不该因为没装 Shizuku 就报红
        val ready = !virtual || ShellManager.isShizukuActive(hint.context.applicationContext)

        val resId = when {
            !virtual -> R.string.agent_display_mode_hint_main
            ready -> R.string.agent_display_mode_hint_virtual
            else -> R.string.agent_display_mode_hint_virtual_unavailable
        }
        hint.setText(resId)
        // 取不到主题色就别改，保留 XML 里的 onSurfaceVariant，绝不把文字刷成全透明
        // colorError 用 framework 的 attr：material R 里只有 colorErrorContainer，没有 colorError
        val colorAttr = if (ready) {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        } else {
            android.R.attr.colorError
        }
        runCatching { hint.setTextColor(MaterialColors.getColor(hint, colorAttr)) }
    }

    /**
     * 「屏幕常亮」的说明随执行环境变化：主屏必须压住息屏，
     * 虚拟屏在后台跑、主屏熄不熄都不影响执行，常亮只剩「想看着进度」这一个用处。
     */
    fun renderKeepScreenOnHint(hint: TextView, mode: String) {
        hint.setText(
            if (normalize(mode) == VIRTUAL) {
                R.string.hint_vflow_ai_agent_keep_screen_on_virtual
            } else {
                R.string.hint_vflow_ai_agent_keep_screen_on
            }
        )
    }
}
