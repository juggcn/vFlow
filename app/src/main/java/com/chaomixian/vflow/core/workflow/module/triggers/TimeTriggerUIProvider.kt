// 文件: main/java/com/chaomixian/vflow/core/workflow/module/triggers/TimeTriggerUIProvider.kt
package com.chaomixian.vflow.core.workflow.module.triggers

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Calendar
import java.util.Locale

class TimeTriggerViewHolder(
    view: View,
    val modeToggle: MaterialButtonToggleGroup,
    val pointContainer: View,
    val windowContainer: View,
    val timeTextView: TextView,
    val pickTimeButton: Button,
    val windowList: LinearLayout,
    val addWindowButton: View,
    val intervalInput: TextInputEditText,
    val dayChips: Map<Int, Chip>
) : CustomEditorViewHolder(view) {
    /** 时段数据，元素形如 "09:30-11:30" */
    val windows = mutableListOf<String>()
}

class TimeTriggerUIProvider : ModuleUIProvider {

    companion object {
        private val ALL_DAYS = listOf(
            Calendar.SUNDAY,
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY,
            Calendar.SATURDAY
        )

        // 同一个 FragmentActivity 里连续 show 同一个 tag 会抛 IllegalStateException，故按用途区分
        private const val TAG_POINT_TIME = "TIME_TRIGGER_POINT"
        private const val TAG_WINDOW_START = "TIME_TRIGGER_WINDOW_START"
        private const val TAG_WINDOW_END = "TIME_TRIGGER_WINDOW_END"
    }

    override fun getHandledInputIds(): Set<String> =
        setOf("mode", "time", "windows", "interval_minutes", "days")

    override fun createPreview(
        context: Context,
        parent: ViewGroup,
        step: ActionStep,
        allSteps: List<ActionStep>,
        onStartActivityForResult: ((Intent, (resultCode: Int, data: Intent?) -> Unit) -> Unit)?
    ): View? = null

    override fun createEditor(
        context: Context,
        parent: ViewGroup,
        currentParameters: Map<String, Any?>,
        onParametersChanged: () -> Unit,
        onMagicVariableRequested: ((String) -> Unit)?,
        allSteps: List<ActionStep>?,
        onStartActivityForResult: ((Intent, (Int, Intent?) -> Unit) -> Unit)?
    ): CustomEditorViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.partial_time_trigger_editor, parent, false)
        val holder = TimeTriggerViewHolder(
            view = view,
            modeToggle = view.findViewById(R.id.toggle_trigger_mode),
            pointContainer = view.findViewById(R.id.container_point_mode),
            windowContainer = view.findViewById(R.id.container_window_mode),
            timeTextView = view.findViewById(R.id.text_selected_time),
            pickTimeButton = view.findViewById(R.id.button_pick_time),
            windowList = view.findViewById(R.id.container_windows),
            addWindowButton = view.findViewById(R.id.button_add_window),
            intervalInput = view.findViewById(R.id.et_window_interval),
            dayChips = mapOf(
                Calendar.SUNDAY to view.findViewById<Chip>(R.id.chip_sun),
                Calendar.MONDAY to view.findViewById<Chip>(R.id.chip_mon),
                Calendar.TUESDAY to view.findViewById<Chip>(R.id.chip_tue),
                Calendar.WEDNESDAY to view.findViewById<Chip>(R.id.chip_wed),
                Calendar.THURSDAY to view.findViewById<Chip>(R.id.chip_thu),
                Calendar.FRIDAY to view.findViewById<Chip>(R.id.chip_fri),
                Calendar.SATURDAY to view.findViewById<Chip>(R.id.chip_sat)
            )
        )

        // ---- 恢复已有参数 ----
        val currentTime = (currentParameters["time"] as? String)
            ?.takeIf { TimeTriggerModule.parseMinutesOfDay(it) != null }
            ?: "09:00"
        holder.timeTextView.text = currentTime

        val parsedWindows = TimeTriggerModule.parseWindows(currentParameters["windows"])
            .map { TimeTriggerModule.formatWindow(it) }
        holder.windows.clear()
        holder.windows.addAll(parsedWindows.ifEmpty { TimeTriggerModule.DEFAULT_WINDOWS })

        val interval = (currentParameters["interval_minutes"] as? Number)?.toInt()
            ?.takeIf { it > 0 } ?: TimeTriggerModule.DEFAULT_INTERVAL_MINUTES
        holder.intervalInput.setText(interval.toString())
        holder.intervalInput.addTextChangedListener(SimpleTextWatcher { onParametersChanged() })

        // 兼容从JSON加载的 Double 列表和默认的 Int 列表
        val daysAny = currentParameters["days"]
        val currentDays = when (daysAny) {
            is List<*> -> daysAny.mapNotNull { (it as? Number)?.toInt() }
            else -> emptyList()
        }
        // 未勾选任何星期按「每天」处理，编辑时回填为全选
        val effectiveDays = currentDays.ifEmpty { ALL_DAYS }
        holder.dayChips.forEach { (dayInt, chip) ->
            chip.isChecked = effectiveDays.contains(dayInt)
        }

        renderWindows(context, holder, onParametersChanged)

        // ---- 模式切换 ----
        holder.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            applyModeVisibility(holder, checkedId == R.id.button_mode_window)
            onParametersChanged()
        }
        val windowMode = TimeTriggerModule.isWindowMode(currentParameters)
        holder.modeToggle.check(if (windowMode) R.id.button_mode_window else R.id.button_mode_point)
        // check() 在部分版本上不会回调监听器，这里兜底同步一次
        applyModeVisibility(holder, windowMode)

        // ---- 指定时刻：选择时间 ----
        holder.pickTimeButton.setOnClickListener {
            showTimePicker(context, holder.timeTextView.text.toString(), TAG_POINT_TIME) { picked ->
                holder.timeTextView.text = picked
                onParametersChanged()
            }
        }

        // ---- 时段重复：增加时段 ----
        holder.addWindowButton.setOnClickListener {
            holder.windows.add(lastWindowOrDefault(holder.windows))
            renderWindows(context, holder, onParametersChanged)
            onParametersChanged()
        }

        // ---- 星期选择 ----
        holder.dayChips.values.forEach { chip ->
            chip.setOnCheckedChangeListener { _, _ -> onParametersChanged() }
        }

        return holder
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val h = holder as TimeTriggerViewHolder
        val days = h.dayChips.filter { it.value.isChecked }.keys.toList()
            .ifEmpty { ALL_DAYS }
        val windowMode = h.modeToggle.checkedButtonId == R.id.button_mode_window
        val windows = h.windows.toList().ifEmpty { TimeTriggerModule.DEFAULT_WINDOWS }
        val interval = h.intervalInput.text?.toString()?.toIntOrNull()?.takeIf { it > 0 }
            ?: TimeTriggerModule.DEFAULT_INTERVAL_MINUTES

        return mapOf(
            "mode" to if (windowMode) TimeTriggerModule.MODE_WINDOW else TimeTriggerModule.MODE_POINT,
            "time" to h.timeTextView.text.toString(),
            "windows" to windows,
            "interval_minutes" to interval.toLong(),
            "days" to days
        )
    }

    /** 按模式切换两块区域的可见性 */
    private fun applyModeVisibility(holder: TimeTriggerViewHolder, windowMode: Boolean) {
        holder.pointContainer.visibility = if (windowMode) View.GONE else View.VISIBLE
        holder.windowContainer.visibility = if (windowMode) View.VISIBLE else View.GONE
    }

    /**
     * 重建时段行。删除行会改变下标，因此每次都整体重建，避免闭包捕获到过期 index。
     */
    private fun renderWindows(
        context: Context,
        holder: TimeTriggerViewHolder,
        onParametersChanged: () -> Unit
    ) {
        holder.windowList.removeAllViews()

        val ranges = holder.windows.toList()
        ranges.forEachIndexed { index, range ->
            val row = LayoutInflater.from(context)
                .inflate(R.layout.partial_time_window_row, holder.windowList, false)
            val startView = row.findViewById<TextView>(R.id.text_window_start)
            val endView = row.findViewById<TextView>(R.id.text_window_end)

            val parsed = TimeTriggerModule.parseWindows(listOf(range)).firstOrNull()
            val startText = parsed?.let { TimeTriggerModule.formatMinutesOfDay(it.first) } ?: "09:30"
            val endText = parsed?.let { TimeTriggerModule.formatMinutesOfDay(it.second) } ?: "11:30"
            startView.text = startText
            endView.text = endText

            startView.setOnClickListener {
                showTimePicker(context, startView.text.toString(), TAG_WINDOW_START) { picked ->
                    holder.windows[index] = "$picked-${endView.text}"
                    startView.text = picked
                    onParametersChanged()
                }
            }
            endView.setOnClickListener {
                showTimePicker(context, endView.text.toString(), TAG_WINDOW_END) { picked ->
                    holder.windows[index] = "${startView.text}-$picked"
                    endView.text = picked
                    onParametersChanged()
                }
            }

            row.findViewById<ImageButton>(R.id.button_remove_window).setOnClickListener {
                // 至少保留一个时段
                if (holder.windows.size <= 1) return@setOnClickListener
                holder.windows.removeAt(index)
                renderWindows(context, holder, onParametersChanged)
                onParametersChanged()
            }

            holder.windowList.addView(row)
        }
    }

    /** 新增时段时，以上一个时段的结束时间为起点，往后延一小时 */
    private fun lastWindowOrDefault(windows: List<String>): String {
        val last = TimeTriggerModule.parseWindows(windows).lastOrNull()
            ?: return TimeTriggerModule.DEFAULT_WINDOWS.first()
        val start = (last.second + 30) % (24 * 60)
        val end = minOf(start + 60, 24 * 60 - 1)
        return "${TimeTriggerModule.formatMinutesOfDay(start)}-${TimeTriggerModule.formatMinutesOfDay(end)}"
    }

    private fun showTimePicker(
        context: Context,
        current: String,
        tag: String,
        onPicked: (String) -> Unit
    ) {
        val minutes = TimeTriggerModule.parseMinutesOfDay(current) ?: (9 * 60 + 30)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(minutes / 60)
            .setMinute(minutes % 60)
            .setTitleText(context.getString(R.string.label_trigger_time))
            .build()

        picker.addOnPositiveButtonClickListener {
            onPicked(String.format(Locale.ROOT, "%02d:%02d", picker.hour, picker.minute))
        }

        // 需要 FragmentManager 来显示 picker
        val activity = context as? FragmentActivity ?: return
        val fragmentManager = activity.supportFragmentManager
        if (fragmentManager.isStateSaved) return
        picker.show(fragmentManager, tag)
    }
}
