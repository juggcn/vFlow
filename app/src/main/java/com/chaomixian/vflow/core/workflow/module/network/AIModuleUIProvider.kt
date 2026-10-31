// 文件: main/java/com/chaomixian/vflow/core/workflow/module/network/AIModuleUIProvider.kt
package com.chaomixian.vflow.core.workflow.module.network

import android.content.Context
import android.content.Intent
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.chat.ChatPresetConfig
import com.chaomixian.vflow.ui.chat.ChatPresetRepository
import com.chaomixian.vflow.ui.workflow_editor.RichTextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

class AIModuleUIProvider : ModuleUIProvider {
    class ViewHolder(view: View) : CustomEditorViewHolder(view) {
        val presetGroup: ChipGroup = view.findViewById(R.id.cg_preset)
        val presetHint: TextView = view.findViewById(R.id.tv_preset_hint)

        val apiKeyEdit: TextInputEditText = view.findViewById(R.id.et_api_key)
        val modelEdit: TextInputEditText = view.findViewById(R.id.et_model)
        val promptContainer: FrameLayout = view.findViewById(R.id.container_prompt)

        val advancedHeader: LinearLayout = view.findViewById(R.id.layout_advanced_header)
        val advancedContainer: LinearLayout = view.findViewById(R.id.container_advanced)
        val expandArrow: ImageView = view.findViewById(R.id.iv_expand_arrow)

        val baseUrlEdit: TextInputEditText = view.findViewById(R.id.et_base_url)
        val systemPromptEdit: TextInputEditText = view.findViewById(R.id.et_system_prompt)
        val tempSlider: Slider = view.findViewById(R.id.slider_temperature)
        val tempText: TextView = view.findViewById(R.id.tv_temperature_value)

        /** 当前选中的模型预设 id，空表示自定义 */
        var presetId: String = AIModule.PRESET_CUSTOM

        /** 当前选中预设的显示名，写进参数供摘要使用 */
        var presetName: String = ""

        // 引用动态创建的 RichTextView
        var promptRichText: RichTextView? = null
        var allSteps: List<ActionStep>? = null
    }

    override fun getHandledInputIds(): Set<String> = setOf(
        "preset_id", "preset_name", "api_key", "base_url", "model", "prompt", "system_prompt", "temperature", "show_advanced"
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
        val view = LayoutInflater.from(context).inflate(R.layout.partial_ai_completion_editor, parent, false)
        val holder = ViewHolder(view)
        holder.allSteps = allSteps

        // 恢复参数
        val selectedPresetId = currentParameters["preset_id"] as? String ?: AIModule.PRESET_CUSTOM
        holder.presetId = selectedPresetId
        holder.presetName = currentParameters["preset_name"] as? String ?: ""
        val presets = loadPresets(context)
        buildPresetChips(context, holder, presets, selectedPresetId)

        holder.apiKeyEdit.setText(currentParameters["api_key"] as? String ?: "")
        holder.modelEdit.setText(currentParameters["model"] as? String ?: "gpt-3.5-turbo")
        holder.baseUrlEdit.setText(currentParameters["base_url"] as? String ?: "https://api.openai.com/v1")
        holder.systemPromptEdit.setText(currentParameters["system_prompt"] as? String ?: "You are a helpful assistant.")

        val temp = (currentParameters["temperature"] as? Number)?.toFloat() ?: 0.7f
        holder.tempSlider.value = temp
        holder.tempText.text = String.format(Locale.US, "%.1f", temp)

        val showAdvanced = currentParameters["show_advanced"] as? Boolean ?: false
        holder.advancedContainer.isVisible = showAdvanced
        holder.expandArrow.rotation = if (showAdvanced) 180f else 0f

        // 创建 Prompt 富文本编辑器
        val promptValue = currentParameters["prompt"] as? String ?: ""
        setupPromptEditor(context, holder, promptValue, onMagicVariableRequested)

        // 监听器逻辑
        holder.presetGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(checkedIds[0]) ?: return@setOnCheckedStateChangeListener
            val preset = chip.tag as? ChatPresetConfig

            // 先更新选中状态，再刷新输入框：setText 会触发 onParametersChanged，
            // 那会儿读到的必须是新选中的预设，否则会把旧 id 写回参数。
            holder.presetId = preset?.id ?: AIModule.PRESET_CUSTOM
            holder.presetName = preset?.let { it.name.ifBlank { it.model } } ?: ""

            // 选中预设时，把连接信息带出来；自定义模式保持当前值不变
            if (preset != null) {
                holder.baseUrlEdit.setText(preset.baseUrl)
                holder.apiKeyEdit.setText(preset.apiKey)
                holder.modelEdit.setText(preset.model)
            }
            onParametersChanged()
        }

        holder.advancedHeader.setOnClickListener {
            val isVisible = holder.advancedContainer.isVisible
            holder.advancedContainer.isVisible = !isVisible
            holder.expandArrow.animate().rotation(if (!isVisible) 180f else 0f).setDuration(200).start()
            onParametersChanged()
        }

        holder.tempSlider.addOnChangeListener { _, value, _ ->
            holder.tempText.text = String.format(Locale.US, "%.1f", value)
            onParametersChanged()
        }

        val textWatcher = { _: Editable? -> onParametersChanged() }

        holder.apiKeyEdit.doAfterTextChanged(textWatcher)
        holder.modelEdit.doAfterTextChanged(textWatcher)
        holder.baseUrlEdit.doAfterTextChanged(textWatcher)
        holder.systemPromptEdit.doAfterTextChanged(textWatcher)

        return holder
    }

    /** 读取「AI 对话」里已保存的模型列表 */
    private fun loadPresets(context: Context): List<ChatPresetConfig> {
        return runCatching {
            ChatPresetRepository(context).getPresets()
        }.getOrDefault(emptyList())
    }

    /** 按已配置模型动态生成选项，末尾固定一个「自定义」 */
    private fun buildPresetChips(
        context: Context,
        holder: ViewHolder,
        presets: List<ChatPresetConfig>,
        selectedPresetId: String
    ) {
        holder.presetGroup.removeAllViews()

        presets.forEach { preset ->
            val chip = Chip(context).apply {
                id = View.generateViewId()
                text = preset.name.ifBlank { preset.model }
                isCheckable = true
                isCheckedIconVisible = true
                isChecked = preset.id == selectedPresetId
                tag = preset
            }
            holder.presetGroup.addView(chip)
        }

        val customChip = Chip(context).apply {
            id = View.generateViewId()
            text = context.getString(R.string.option_vflow_ai_completion_provider_custom)
            isCheckable = true
            isCheckedIconVisible = true
            isChecked = presets.none { it.id == selectedPresetId }
            tag = null
        }
        holder.presetGroup.addView(customChip)

        // 没配过模型时给个去处提示，不然只剩「自定义」会让人以为是 bug
        holder.presetHint.isVisible = presets.isEmpty()
    }

    private fun setupPromptEditor(
        context: Context,
        holder: ViewHolder,
        initialValue: String,
        onMagicReq: ((String) -> Unit)?
    ) {
        holder.promptContainer.removeAllViews()
        val row = LayoutInflater.from(context).inflate(R.layout.row_editor_input, null)
        row.findViewById<TextView>(R.id.input_name).visibility = View.GONE // 隐藏左侧标签，因为上方已有标题
        val valueContainer = row.findViewById<ViewGroup>(R.id.input_value_container)
        val magicButton = row.findViewById<ImageButton>(R.id.button_magic_variable)

        magicButton.setOnClickListener { onMagicReq?.invoke("prompt") }

        // 加载富文本编辑器布局
        val richEditorLayout = LayoutInflater.from(context).inflate(R.layout.rich_text_editor, valueContainer, false)
        val richTextView = richEditorLayout.findViewById<RichTextView>(R.id.rich_text_view)

        richTextView.setRichText(initialValue, holder.allSteps ?: emptyList())
        richTextView.tag = "prompt"

        // 将引用保存到 ViewHolder
        holder.promptRichText = richTextView

        valueContainer.addView(richEditorLayout)
        holder.promptContainer.addView(row)
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val h = holder as ViewHolder

        return mapOf(
            "preset_id" to h.presetId,
            "preset_name" to h.presetName,
            "api_key" to h.apiKeyEdit.text.toString(),
            "base_url" to h.baseUrlEdit.text.toString(),
            "model" to h.modelEdit.text.toString(),
            "prompt" to (h.promptRichText?.getRawText() ?: ""),
            "system_prompt" to h.systemPromptEdit.text.toString(),
            "temperature" to h.tempSlider.value.toDouble(),
            "show_advanced" to h.advancedContainer.isVisible
        )
    }
}
