// 文件: main/java/com/chaomixian/vflow/core/workflow/module/interaction/AutoGLMModuleUIProvider.kt
// 描述: 「AutoGLM 智能体」的编辑卡片。模型选择与「AI 对话」一致：
//      从已保存的模型配置里选，选中后自动带出 Base URL / API Key / 模型名。
package com.chaomixian.vflow.core.workflow.module.interaction

import android.content.Context
import android.content.Intent
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.workflow_editor.RichTextView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText

/**
 * AutoGLM 专用 UI 提供者
 */
class AutoGLMModuleUIProvider : ModuleUIProvider {

    class ViewHolder(view: View) : CustomEditorViewHolder(view) {
        val presetGroup: ChipGroup = view.findViewById(R.id.cg_preset)
        val presetHint: TextView = view.findViewById(R.id.tv_preset_hint)

        val baseUrlEdit: TextInputEditText = view.findViewById(R.id.et_base_url)
        val modelEdit: TextInputEditText = view.findViewById(R.id.et_model)
        val apiKeyEdit: TextInputEditText = view.findViewById(R.id.et_api_key)

        val instructionContainer: FrameLayout = view.findViewById(R.id.container_instruction)

        val stepsSlider: Slider = view.findViewById(R.id.slider_max_steps)
        val stepsText: TextView = view.findViewById(R.id.tv_max_steps_value)

        val displayModeGroup: MaterialButtonToggleGroup = view.findViewById(R.id.tbg_display_mode)
        val displayModeHint: TextView = view.findViewById(R.id.tv_display_mode_hint)
        val keepScreenOnSwitch: MaterialSwitch = view.findViewById(R.id.switch_keep_screen_on)
        val keepScreenOnHint: TextView = view.findViewById(R.id.tv_keep_screen_on_hint)

        /** 当前选中的模型预设 id，空表示自定义 */
        var presetId: String = AgentPresetChips.PRESET_CUSTOM

        /** 当前选中预设的显示名，写进参数供摘要使用 */
        var presetName: String = ""

        var instructionRichText: RichTextView? = null
        var allSteps: List<ActionStep>? = null
    }

    override fun getHandledInputIds(): Set<String> = setOf(
        "preset_id", "preset_name", "base_url", "model", "api_key", "instruction", "max_steps",
        "display_mode", "keep_screen_on"
    )

    override fun createEditor(
        context: Context,
        parent: ViewGroup,
        currentParameters: Map<String, Any?>,
        onParametersChanged: () -> Unit,
        onMagicVariableRequested: ((String) -> Unit)?,
        allSteps: List<ActionStep>?,
        onStartActivityForResult: ((Intent, (Int, Intent?) -> Unit) -> Unit)?
    ): CustomEditorViewHolder {
        // 复用 partial_agent_editor 布局，但隐藏不需要的元素
        val view = LayoutInflater.from(context).inflate(R.layout.partial_agent_editor, parent, false)
        val holder = ViewHolder(view)
        holder.allSteps = allSteps

        view.findViewById<View>(R.id.btn_select_tools)?.isVisible = false
        view.findViewById<View>(R.id.tv_selected_tools)?.isVisible = false

        // 模型选项：直接读「AI 对话」里已保存的模型
        val selectedPresetId = currentParameters["preset_id"] as? String
            ?: AgentPresetChips.PRESET_CUSTOM
        holder.presetId = selectedPresetId
        holder.presetName = currentParameters["preset_name"] as? String ?: ""

        AgentPresetChips.build(
            context = context,
            group = holder.presetGroup,
            hint = holder.presetHint,
            presets = AgentPresetChips.load(context),
            selectedPresetId = selectedPresetId
        ) { preset ->
            // 先更新选中状态再刷新输入框：setText 会触发 onParametersChanged，
            // 那会儿读到的必须是新选中的预设，否则会把旧 id 写回参数。
            holder.presetId = preset?.id ?: AgentPresetChips.PRESET_CUSTOM
            holder.presetName = preset?.let { it.name.ifBlank { it.model } } ?: ""

            if (preset != null) {
                holder.baseUrlEdit.setText(preset.baseUrl)
                holder.modelEdit.setText(preset.model)
                holder.apiKeyEdit.setText(preset.apiKey)
            }
            onParametersChanged()
        }

        // 设置默认值
        holder.baseUrlEdit.setText(
            currentParameters["base_url"] as? String
                ?: "https://open.bigmodel.cn/api/paas/v4"
        )
        holder.modelEdit.setText(currentParameters["model"] as? String ?: "autoglm-phone")
        holder.apiKeyEdit.setText(currentParameters["api_key"] as? String ?: "")

        // 恢复 Instruction
        setupInstructionEditor(
            context,
            holder,
            currentParameters["instruction"] as? String ?: "",
            onMagicVariableRequested
        )

        // 恢复 Max Steps (默认 30)
        val maxSteps = (currentParameters["max_steps"] as? Number)?.toFloat() ?: 30f
        holder.stepsSlider.value = maxSteps
        holder.stepsText.text = "${maxSteps.toInt()}"

        val textWatcher = { _: Editable? -> onParametersChanged() }
        holder.baseUrlEdit.doAfterTextChanged(textWatcher)
        holder.modelEdit.doAfterTextChanged(textWatcher)
        holder.apiKeyEdit.doAfterTextChanged(textWatcher)

        holder.stepsSlider.addOnChangeListener { _, value, _ ->
            holder.stepsText.text = "${value.toInt()}"
            onParametersChanged()
        }

        // 执行环境：bind 内部先恢复选中态再挂监听，否则卡片一打开就会回写参数
        val initialMode = currentParameters["display_mode"] as? String
        AgentDisplayModeUi.bind(
            group = holder.displayModeGroup,
            hint = holder.displayModeHint,
            initialMode = initialMode
        ) { mode ->
            AgentDisplayModeUi.renderKeepScreenOnHint(holder.keepScreenOnHint, mode)
            onParametersChanged()
        }
        AgentDisplayModeUi.renderKeepScreenOnHint(holder.keepScreenOnHint, initialMode ?: AgentDisplayModeUi.MAIN)

        // 屏幕常亮：老工作流没有这个参数时按开处理，与执行侧兜底一致
        holder.keepScreenOnSwitch.isChecked = when (val raw = currentParameters["keep_screen_on"]) {
            is Boolean -> raw
            is String -> raw.trim().equals("true", ignoreCase = true)
            else -> true
        }
        holder.keepScreenOnSwitch.setOnCheckedChangeListener { _, _ -> onParametersChanged() }

        return holder
    }

    private fun setupInstructionEditor(
        context: Context,
        holder: ViewHolder,
        initialValue: String,
        onMagicReq: ((String) -> Unit)?
    ) {
        holder.instructionContainer.removeAllViews()
        val row = LayoutInflater.from(context).inflate(R.layout.row_editor_input, null)
        row.findViewById<TextView>(R.id.input_name).visibility = View.GONE
        val valueContainer = row.findViewById<ViewGroup>(R.id.input_value_container)
        val magicButton = row.findViewById<ImageButton>(R.id.button_magic_variable)

        magicButton.setOnClickListener { onMagicReq?.invoke("instruction") }

        val richEditorLayout = LayoutInflater.from(context).inflate(R.layout.rich_text_editor, valueContainer, false)
        val richTextView = richEditorLayout.findViewById<RichTextView>(R.id.rich_text_view)

        richTextView.setRichText(initialValue, holder.allSteps ?: emptyList())
        richTextView.tag = "instruction"

        holder.instructionRichText = richTextView
        valueContainer.addView(richEditorLayout)
        holder.instructionContainer.addView(row)
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val h = holder as ViewHolder

        return mapOf(
            "preset_id" to h.presetId,
            "preset_name" to h.presetName,
            "base_url" to h.baseUrlEdit.text.toString(),
            "model" to h.modelEdit.text.toString(),
            "api_key" to h.apiKeyEdit.text.toString(),
            "instruction" to (h.instructionRichText?.getRawText() ?: ""),
            "max_steps" to h.stepsSlider.value.toDouble(),
            "display_mode" to AgentDisplayModeUi.selectedMode(h.displayModeGroup),
            "keep_screen_on" to h.keepScreenOnSwitch.isChecked
        )
    }
}
