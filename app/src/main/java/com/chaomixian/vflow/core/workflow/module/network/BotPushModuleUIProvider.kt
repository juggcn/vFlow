// 文件: main/java/com/chaomixian/vflow/core/workflow/module/network/BotPushModuleUIProvider.kt
// 描述: 「机器人推送」的自定义参数编辑器。
//      默认编辑器把各条通道的开关和凭证平铺成一长串，一屏放不下、也看不出开了哪几条。
//      这里改成：顶部基础信息（标题/内容/格式）→ 一行可多选的通道芯片 → 只渲染已选通道的配置卡片。

package com.chaomixian.vflow.core.workflow.module.network

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.workflow_editor.ActionEditorViewStateReader
import com.chaomixian.vflow.ui.workflow_editor.RichTextView
import com.chaomixian.vflow.ui.workflow_editor.StandardControlFactory
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 一条推送通道在编辑器里的描述。 */
internal data class BotChannelSpec(
    val enableId: String,
    val label: String,
    /** 卡片里按顺序渲染的字段。 */
    val fields: List<String>,
    /** 判空用的必填字段，为空说明这条通道还没配好。 */
    val required: List<String>
)

internal class BotPushEditorHolder(
    view: View,
    val values: MutableMap<String, Any?>,
    val rows: MutableMap<String, View>,
    val inputDefs: Map<String, InputDefinition>
) : CustomEditorViewHolder(view) {

    /** 把当前控件里的值读回 values，返回快照。 */
    fun collect(): Map<String, Any?> {
        rows.forEach { (id, row) ->
            val def = inputDefs[id] ?: return@forEach
            val result = ActionEditorViewStateReader.readParameterValue(row, def, values[id])
            if (result.shouldUpdate) values[id] = result.value
        }
        return values.toMap()
    }

    override fun insertVariable(inputId: String, variableReference: String): Boolean {
        val row = rows[inputId] ?: return false
        val richText = row.findViewById<RichTextView>(R.id.rich_text_view) ?: return false
        richText.insertVariablePill(variableReference)
        return true
    }
}

internal class BotPushModuleUIProvider(
    private val module: BotPushModule
) : ModuleUIProvider {

    companion object {
        private const val ID_TITLE = "title"
        private const val ID_CONTENT = "content"
        private const val ID_FORMAT = "format"

        /** 顶部基础信息，固定在最前面。 */
        private val BASIC_FIELDS = listOf(ID_TITLE, ID_CONTENT, ID_FORMAT)

        private val CHANNELS = listOf(
            BotChannelSpec(
                enableId = "enable_qq",
                label = "QQ 官方机器人",
                fields = listOf("qq_app_id", "qq_app_secret", "qq_handshake_timeout", "qq_msg_id"),
                required = listOf("qq_app_id", "qq_app_secret")
            ),
            BotChannelSpec(
                enableId = "enable_wechat",
                label = "微信（扫码）",
                fields = emptyList(),
                required = emptyList()
            )
        )

        val HANDLED_IDS: Set<String> = buildSet {
            addAll(BASIC_FIELDS)
            CHANNELS.forEach { spec ->
                add(spec.enableId)
                addAll(spec.fields)
            }
            // 微信号由卡片里的选择器写入，不单独渲染一行
            add("wechat_account")
        }
    }

    override fun getHandledInputIds(): Set<String> = HANDLED_IDS

    override fun hasCustomEditor(): Boolean = true

    override fun createEditor(
        context: Context,
        parent: ViewGroup,
        currentParameters: Map<String, Any?>,
        onParametersChanged: () -> Unit,
        onMagicVariableRequested: ((inputId: String) -> Unit)?,
        allSteps: List<ActionStep>?,
        onStartActivityForResult: ((Intent, (resultCode: Int, data: Intent?) -> Unit) -> Unit)?
    ): CustomEditorViewHolder {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val inputDefs = module.getInputs().associateBy { it.id }
        val values = currentParameters.toMutableMap()
        // 开关落盘可能是 Boolean / String / Number，统一成 Boolean 便于读。
        CHANNELS.forEach { spec ->
            values[spec.enableId] = module.isChannelEnabled(currentParameters, spec.enableId)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(4), 0, dp(8))
        }

        val rows = mutableMapOf<String, View>()

        fun addRow(id: String, container: LinearLayout) {
            val def = inputDefs[id] ?: return
            val row = StandardControlFactory.createParameterInputRow(
                context = context,
                inputDef = def,
                currentValue = values[id],
                allSteps = allSteps,
                onMagicVariableRequested = onMagicVariableRequested
            )
            rows[id] = row
            container.addView(row)
        }

        fun readBack() {
            rows.forEach { (id, row) ->
                val def = inputDefs[id] ?: return@forEach
                val result = ActionEditorViewStateReader.readParameterValue(row, def, values[id])
                if (result.shouldUpdate) values[id] = result.value
            }
        }

        /** 卡片里的整宽描边按钮，点之前先把当前控件读回 values。 */
        fun actionButton(
            label: String,
            onClick: (ActionStep) -> Unit,
            marginStartEnd: Int,
            marginTop: Int,
            marginBottom: Int
        ): MaterialButton = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = label
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = marginStartEnd
                marginEnd = marginStartEnd
                topMargin = marginTop
                bottomMargin = marginBottom
            }
            setOnClickListener {
                readBack()
                onClick(ActionStep(moduleId = module.id, parameters = values.toMap()))
            }
        }

        // ---- 基础信息 ----
        val basicBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        BASIC_FIELDS.forEach { addRow(it, basicBox) }
        root.addView(basicBox)

        // ---- 通道区标题 ----
        val sectionHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(2))
        }
        val sectionTitle = TextView(context).apply {
            text = "推送通道"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
        }
        val sectionHint = TextView(context).apply {
            textSize = 12f
            setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
        }
        sectionHeader.addView(
            sectionTitle,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        sectionHeader.addView(sectionHint)
        root.addView(sectionHeader)

        // ---- 通道芯片（可多选）----
        val chipGroup = ChipGroup(context).apply {
            isSingleLine = false
            isSingleSelection = false
            setPadding(dp(12), dp(6), dp(12), dp(2))
            chipSpacingHorizontal = dp(8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(chipGroup)

        // ---- 已选通道的配置卡片 ----
        val cardsBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        root.addView(cardsBox)

        // buildCard 声明在 rebuildCards 之前，卡片里的按钮回调只能通过这个引用重建列表
        var requestRebuild: () -> Unit = {}

        /**
         * 微信卡片下半部分：当前账号 + 「测试连接」+「切换 / 添加微信号」。
         * 账号存在本机账号池里，每个步骤只记 key，所以不同步骤可以各用一个微信号。
         */
        fun addWechatAccountSection(box: LinearLayout) {
            val accounts = module.wechatAccounts()
            val selected = module.resolveWechatAccount(values["wechat_account"].asText())

            box.addView(
                TextView(context).apply {
                    text = when {
                        selected != null -> "当前微信号：${selected.label}"
                        accounts.isEmpty() -> "还没绑定微信号，点下面按钮扫一个"
                        else -> "本机绑了 ${accounts.size} 个微信号，点「切换」选一个"
                    }
                    textSize = 12f
                    setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
                    setPadding(dp(16), 0, dp(16), 0)
                }
            )

            fun selectAccount(key: String) {
                values["wechat_account"] = key
                onParametersChanged()
                requestRebuild()
            }

            fun bindNew() {
                module.startWechatQrLogin(context) { key -> selectAccount(key) }
            }

            fun showPicker() {
                val list = module.wechatAccounts()
                val items = list.map { it.label }.toTypedArray() + "扫码添加新微信号"
                val builder = MaterialAlertDialogBuilder(context)
                    .setTitle("选择微信号")
                    .setItems(items) { _, which ->
                        if (which < list.size) selectAccount(list[which].key) else bindNew()
                    }
                    .setNegativeButton("取消", null)
                if (selected != null) {
                    builder.setNeutralButton("解绑 ${selected.label}") { _, _ ->
                        module.removeWechatAccount(selected.key)
                        selectAccount("")
                        Toast.makeText(context, "已解绑 ${selected.label}", Toast.LENGTH_SHORT).show()
                    }
                }
                builder.show()
            }

            box.addView(
                actionButton(
                    label = if (selected != null) "重新测试（当前：${selected.label}）" else "测试连接",
                    onClick = {
                        when {
                            accounts.isEmpty() -> bindNew()
                            selected == null -> showPicker()
                            selected.contextToken.isEmpty() -> bindNew()
                            else -> module.sendTestMessage(selected.key)
                        }
                    },
                    marginStartEnd = dp(16),
                    marginTop = dp(6),
                    marginBottom = dp(4)
                )
            )
            box.addView(
                actionButton(
                    label = if (accounts.isEmpty()) "扫码添加微信号" else "切换 / 添加微信号",
                    onClick = { if (accounts.isEmpty()) bindNew() else showPicker() },
                    marginStartEnd = dp(16),
                    marginTop = 0,
                    marginBottom = dp(10)
                )
            )
        }

        fun buildCard(spec: BotChannelSpec): View {
            val card = MaterialCardView(context).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = themeColor(
                    context,
                    com.google.android.material.R.attr.colorOutlineVariant,
                    Color.LTGRAY
                )
                setCardBackgroundColor(
                    themeColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE)
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(2))
            }

            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(12), dp(6))
            }
            val title = TextView(context).apply {
                text = spec.label
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
            }
            val status = TextView(context).apply {
                textSize = 12f
                setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            }
            val ready = spec.required.isNotEmpty() &&
                spec.required.all { values[it].asText().isNotEmpty() }
            // 绑定结果存在本机（微信按账号、QQ 按 AppID），这里只反映当前是否真的连得上
            val bound = module.channelBoundLabel(values, spec.enableId)
            if (spec.enableId == "enable_wechat") {
                // 没扫码 / 绑了多个但没选 / 选了还没收到消息 / 已连接
                val account = module.resolveWechatAccount(values["wechat_account"].asText())
                status.text = when {
                    module.wechatAccounts().isEmpty() -> "待扫码"
                    account == null -> "待选账号"
                    account.contextToken.isEmpty() -> "待发消息"
                    else -> "已连接"
                }
                if (account?.contextToken?.isNotEmpty() == true) {
                    status.setTextColor(themeColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE))
                }
            } else {
                status.text = when {
                    !ready -> "待填写"
                    bound != null -> "已连接"
                    else -> "待连接"
                }
                if (ready) {
                    status.setTextColor(themeColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE))
                }
            }

            // 配置齐全的卡片默认收起，一眼看到「已配置 / 待填写」，点标题再展开
            val fieldsBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (ready) View.GONE else View.VISIBLE
            }
            val arrow = ImageView(context).apply {
                setImageResource(
                    if (ready) R.drawable.rounded_keyboard_arrow_down_24
                    else R.drawable.rounded_keyboard_arrow_up_24
                )
                setColorFilter(
                    themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
                )
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(4) }
            }
            headerRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            headerRow.addView(status)
            headerRow.addView(arrow)
            headerRow.setOnClickListener {
                val expand = fieldsBox.visibility != View.VISIBLE
                fieldsBox.visibility = if (expand) View.VISIBLE else View.GONE
                arrow.setImageResource(
                    if (expand) R.drawable.rounded_keyboard_arrow_up_24
                    else R.drawable.rounded_keyboard_arrow_down_24
                )
            }
            box.addView(headerRow)
            box.addView(fieldsBox)

            spec.fields.forEach { addRow(it, fieldsBox) }

            // 两条通道的连接方式一样：先在微信 / QQ 里发一条消息，再点这个按钮确认
            if (spec.enableId == "enable_wechat") {
                addWechatAccountSection(box)
            } else {
                bindButtonFor(spec.enableId, context, values)?.let { (label, onClick) ->
                    box.addView(actionButton(label, onClick, dp(16), dp(6), dp(10)))
                }
            }

            card.addView(box)
            return card
        }

        fun rebuildCards() {
            readBack()
            cardsBox.removeAllViews()
            CHANNELS.filter { values[it.enableId] == true }.forEach { spec ->
                cardsBox.addView(buildCard(spec))
            }
            val count = CHANNELS.count { values[it.enableId] == true }
            sectionHint.text = when (count) {
                0 -> "请至少选一条"
                else -> "已选 $count 条，可同时发送"
            }
        }

        requestRebuild = { rebuildCards() }

        fun toggleChannel(enableId: String, checked: Boolean) {
            readBack()
            values[enableId] = checked
            onParametersChanged()
            rebuildCards()
        }

        CHANNELS.forEach { spec ->
            val chip = Chip(context).apply {
                text = spec.label
                isCheckable = true
                isChecked = values[spec.enableId] == true
                isCheckedIconVisible = true
                chipMinHeight = dp(34).toFloat()
                textSize = 13f
                setOnCheckedChangeListener { _, checked -> toggleChannel(spec.enableId, checked) }
            }
            chipGroup.addView(chip)
        }

        val holder = BotPushEditorHolder(root, values, rows, inputDefs)
        rebuildCards()
        return holder
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val editor = holder as? BotPushEditorHolder ?: return emptyMap()
        return editor.collect()
    }

    /** QQ 连接按钮：填完 AppID/AppSecret，点一下等消息即可。微信有账号选择器，单独处理。 */
    private fun bindButtonFor(
        enableId: String,
        context: Context,
        values: Map<String, Any?>
    ): Pair<String, (ActionStep) -> Unit>? = when (enableId) {
        "enable_qq" -> {
            val appId = values["qq_app_id"].asText()
            val bound = module.qqBoundLabel(appId)
            val label = if (bound != null) "重新测试（当前：$bound）" else "测试连接"
            label to { step: ActionStep -> module.startQqHandshake(step) }
        }
        else -> null
    }
}

/** 参数值可能是 String / Boolean / Number，统一取可读文本。 */
internal fun Any?.asText(): String = when (this) {
    null -> ""
    is String -> trim()
    else -> toString().trim()
}

internal fun themeColor(context: Context, attr: Int, fallback: Int): Int {
    val typedArray = context.obtainStyledAttributes(intArrayOf(attr))
    return try {
        typedArray.getColor(0, fallback)
    } finally {
        typedArray.recycle()
    }
}
