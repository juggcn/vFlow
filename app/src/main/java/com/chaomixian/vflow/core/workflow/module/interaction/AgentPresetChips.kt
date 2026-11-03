// 文件: main/java/com/chaomixian/vflow/core/workflow/module/interaction/AgentPresetChips.kt
// 描述: 「AI 智能体」「AutoGLM 智能体」共用的模型选择控件。
//
// 与「AI 对话」模块保持完全一致：模型来源是 AI 对话里已保存的模型配置，
// 选中后自动带出 Base URL / API Key / 模型名；末尾固定一个「自定义」，
// 选它则保留手填内容。

package com.chaomixian.vflow.core.workflow.module.interaction

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.chaomixian.vflow.R
import com.chaomixian.vflow.ui.chat.ChatPresetConfig
import com.chaomixian.vflow.ui.chat.ChatPresetRepository
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

internal object AgentPresetChips {

    /** preset_id 为空表示自定义 */
    const val PRESET_CUSTOM = ""

    /** 读取「AI 对话」里已保存的模型列表 */
    fun load(context: Context): List<ChatPresetConfig> {
        return runCatching { ChatPresetRepository(context).getPresets() }.getOrDefault(emptyList())
    }

    /**
     * 按已配置模型动态生成选项。
     *
     * 必须先建 chip、再挂选中监听：chip 的 isChecked 是在构建阶段设置的，
     * 如果监听先挂上，恢复选中状态时会立刻触发一次「选中预设」，
     * 把用户上一次手填的连接信息冲掉。
     */
    fun build(
        context: Context,
        group: ChipGroup,
        hint: TextView?,
        presets: List<ChatPresetConfig>,
        selectedPresetId: String,
        onPresetSelected: (ChatPresetConfig?) -> Unit
    ) {
        group.removeAllViews()

        presets.forEach { preset ->
            val chip = Chip(context).apply {
                id = View.generateViewId()
                text = preset.name.ifBlank { preset.model }
                isCheckable = true
                isCheckedIconVisible = true
                isChecked = preset.id == selectedPresetId
                tag = preset
            }
            group.addView(chip)
        }

        val customChip = Chip(context).apply {
            id = View.generateViewId()
            text = context.getString(R.string.option_vflow_ai_completion_provider_custom)
            isCheckable = true
            isCheckedIconVisible = true
            isChecked = presets.none { it.id == selectedPresetId }
            tag = null
        }
        group.addView(customChip)

        // 一个模型都没配过时给个提示，不然只剩「自定义」会让人以为是 bug
        hint?.isVisible = presets.isEmpty()

        group.setOnCheckedStateChangeListener { chipGroup, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val chip = chipGroup.findViewById<Chip>(checkedIds[0])
                ?: return@setOnCheckedStateChangeListener
            onPresetSelected(chip.tag as? ChatPresetConfig)
        }
    }
}
