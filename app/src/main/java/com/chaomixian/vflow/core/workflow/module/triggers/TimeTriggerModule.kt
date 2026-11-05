// 文件: main/java/com/chaomixian/vflow/core/workflow/module/triggers/TimeTriggerModule.kt
package com.chaomixian.vflow.core.workflow.module.triggers

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import java.util.*

class TimeTriggerModule : BaseModule() {

    companion object {
        /** 指定时刻：每天在该时刻触发一次 */
        const val MODE_POINT = "point"

        /** 时段重复：在若干时间窗口内按固定间隔对齐触发 */
        const val MODE_WINDOW = "window"

        /** 时段模式下的默认间隔（分钟） */
        const val DEFAULT_INTERVAL_MINUTES = 10

        /** 时段模式下未配置任何时段时的兜底 */
        val DEFAULT_WINDOWS = listOf("09:30-11:30")

        fun isWindowMode(parameters: Map<String, Any?>): Boolean =
            (parameters["mode"] as? String) == MODE_WINDOW

        /**
         * 解析 "HH:mm" 为当天分钟数，非法返回 null。
         */
        fun parseMinutesOfDay(text: String?): Int? {
            val value = text?.trim().orEmpty()
            val parts = value.split(":")
            if (parts.size != 2) return null
            val hour = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }

        /**
         * 解析时段列表，元素形如 "09:30-11:30"。非法项直接跳过。
         */
        fun parseWindows(raw: Any?): List<Pair<Int, Int>> {
            val list = raw as? List<*> ?: return emptyList()
            val result = mutableListOf<Pair<Int, Int>>()
            for (item in list) {
                val text = item as? String ?: continue
                val parts = text.split("-")
                if (parts.size != 2) continue
                val start = parseMinutesOfDay(parts[0]) ?: continue
                val end = parseMinutesOfDay(parts[1]) ?: continue
                if (end <= start) continue
                result.add(start to end)
            }
            return result
        }

        fun formatMinutesOfDay(minutes: Int): String =
            String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

        fun formatWindow(window: Pair<Int, Int>): String =
            "${formatMinutesOfDay(window.first)}-${formatMinutesOfDay(window.second)}"
    }

    override val id = "vflow.trigger.time"
    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_trigger_time_name,
        descriptionStringRes = R.string.module_vflow_trigger_time_desc,
        name = "定时触发",  // Fallback
        description = "在指定时刻或时段内触发工作流，类似闹钟",  // Fallback
        iconRes = R.drawable.rounded_avg_time_24,  // 复用一个时间图标
        category = "触发器",
        categoryId = "trigger"
    )

    override val requiredPermissions = listOf(PermissionManager.EXACT_ALARM)

    // 为该模块提供自定义的UI交互逻辑
    override val uiProvider: ModuleUIProvider? = TimeTriggerUIProvider()

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = "mode",
            name = "触发方式",
            nameStringRes = R.string.param_vflow_trigger_time_mode_name,
            staticType = ParameterType.ENUM,
            defaultValue = MODE_POINT,
            options = listOf(MODE_POINT, MODE_WINDOW),
            optionsStringRes = listOf(
                R.string.option_vflow_trigger_time_mode_point,
                R.string.option_vflow_trigger_time_mode_window
            ),
            acceptsMagicVariable = false,
            acceptsNamedVariable = false
        ),
        InputDefinition(
            id = "time",
            name = "触发时间",
            nameStringRes = R.string.param_vflow_trigger_time_time_name,
            staticType = ParameterType.STRING,
            defaultValue = "09:00",
            acceptsMagicVariable = false
        ),
        InputDefinition(
            id = "windows",
            name = "触发时段",
            nameStringRes = R.string.param_vflow_trigger_time_windows_name,
            staticType = ParameterType.ANY, // 存储为 List<String>，元素形如 "09:30-11:30"
            defaultValue = DEFAULT_WINDOWS,
            acceptsMagicVariable = false
        ),
        InputDefinition(
            id = "interval_minutes",
            name = "时段内间隔",
            nameStringRes = R.string.param_vflow_trigger_time_interval_name,
            staticType = ParameterType.NUMBER,
            defaultValue = DEFAULT_INTERVAL_MINUTES.toLong(),
            acceptsMagicVariable = false,
            acceptsNamedVariable = false
        ),
        InputDefinition(
            id = "days",
            name = "重复日期",
            nameStringRes = R.string.param_vflow_trigger_time_days_name,
            staticType = ParameterType.ANY, // 存储为 List<Int>
            // 默认值为包含所有星期的列表
            defaultValue = listOf(Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY),
            acceptsMagicVariable = false
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = emptyList()

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        // 兼容从JSON加载的 Double 列表和默认的 Int 列表
        val daysAny = step.parameters["days"]
        val daysInt = when (daysAny) {
            is List<*> -> daysAny.mapNotNull { (it as? Number)?.toInt() }
            else -> emptyList()
        }

        val everyday = context.getString(R.string.summary_vflow_trigger_time_everyday)
        val workdays = context.getString(R.string.summary_vflow_trigger_time_workdays)

        val dayName = { dayInt: Int ->
            when (dayInt) {
                Calendar.SUNDAY -> context.getString(R.string.day_sunday)
                Calendar.MONDAY -> context.getString(R.string.day_monday)
                Calendar.TUESDAY -> context.getString(R.string.day_tuesday)
                Calendar.WEDNESDAY -> context.getString(R.string.day_wednesday)
                Calendar.THURSDAY -> context.getString(R.string.day_thursday)
                Calendar.FRIDAY -> context.getString(R.string.day_friday)
                Calendar.SATURDAY -> context.getString(R.string.day_saturday)
                else -> ""
            }
        }

        // 未勾选任何星期按「每天」处理（此前误显示为「单次」，与实际调度行为不符）
        val daysText = when {
            daysInt.isEmpty() || daysInt.size >= 7 -> everyday
            daysInt.sorted() == listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY) -> workdays
            else -> daysInt.sorted().joinToString(", ") { dayName(it) }
        }
        val daysPill = PillUtil.Pill(daysText, "days")

        val suffix = context.getString(R.string.summary_vflow_trigger_time_suffix)

        if (isWindowMode(step.parameters)) {
            val windows = parseWindows(step.parameters["windows"]).sortedBy { it.first }
            val interval = (step.parameters["interval_minutes"] as? Number)?.toInt()
                ?.takeIf { it > 0 } ?: DEFAULT_INTERVAL_MINUTES

            if (windows.isEmpty()) {
                return PillUtil.buildSpannable(
                    context,
                    daysPill,
                    " ",
                    context.getString(R.string.error_vflow_trigger_time_window_invalid)
                )
            }

            val rangePill = PillUtil.Pill(formatWindow(windows.first()), "windows")
            val intervalPill = PillUtil.Pill(
                context.getString(R.string.summary_vflow_trigger_time_every_minutes, interval.toString()),
                "interval_minutes"
            )

            return if (windows.size > 1) {
                val more = context.getString(R.string.summary_vflow_trigger_time_window_more, windows.size.toString())
                PillUtil.buildSpannable(context, daysPill, " ", rangePill, more, " ", intervalPill, suffix)
            } else {
                PillUtil.buildSpannable(context, daysPill, " ", rangePill, " ", intervalPill, suffix)
            }
        }

        val time = step.parameters["time"] as? String ?: "00:00"
        val timePill = PillUtil.Pill(time, "time")
        return PillUtil.buildSpannable(context, daysPill, " ", timePill, suffix)
    }

    override fun validate(step: ActionStep, allSteps: List<ActionStep>): ValidationResult {
        if (isWindowMode(step.parameters)) {
            if (parseWindows(step.parameters["windows"]).isEmpty()) {
                return ValidationResult(
                    false,
                    appContext.getString(R.string.error_vflow_trigger_time_window_invalid)
                )
            }
            val interval = (step.parameters["interval_minutes"] as? Number)?.toInt()
            if (interval == null || interval <= 0) {
                return ValidationResult(
                    false,
                    appContext.getString(R.string.error_vflow_trigger_time_interval_invalid)
                )
            }
            return ValidationResult(true)
        }

        if (parseMinutesOfDay(step.parameters["time"] as? String) == null) {
            return ValidationResult(
                false,
                appContext.getString(R.string.error_vflow_trigger_time_invalid)
            )
        }
        return ValidationResult(true)
    }

    override suspend fun execute(context: ExecutionContext, onProgress: suspend (ProgressUpdate) -> Unit): ExecutionResult {
        onProgress(ProgressUpdate("定时任务已触发"))
        return ExecutionResult.Success()
    }
}
