package com.chaomixian.vflow.core.workflow.module.triggers.handlers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.module.triggers.IntervalTriggerModule
import com.chaomixian.vflow.core.workflow.module.triggers.TimeTriggerModule
import com.chaomixian.vflow.services.TimeTriggerReceiver
import java.util.Calendar
import java.util.Date

object AlarmTriggerScheduler {
    private const val TAG = "AlarmTriggerScheduler"

    fun schedule(context: Context, trigger: TriggerSpec) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = createPendingIntent(context, trigger)
        val nextTriggerTime = calculateNextTriggerTime(trigger)

        if (nextTriggerTime == null) {
            DebugLogger.w(TAG, "无法为工作流 '${trigger.workflowName}' 计算下一次触发时间。")
            cancel(context, trigger)
            return
        }

        // Android 12+ 精确闹钟需 SCHEDULE_EXACT_ALARM 授权（Android 14 起对新装应用默认不授予）。
        // 未授权时 setAlarmClock / setExactAndAllowWhileIdle / setExact 会抛 SecurityException，
        // 因此先检查，未授权则降级为非精确闹钟，保证工作流仍能触发。
        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (!canScheduleExact) {
            DebugLogger.w(TAG, "未获得精确闹钟权限，降级为非精确闹钟：'${trigger.workflowName}'")
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextTriggerTime.timeInMillis,
                intent
            )
        } else when {
            // 指定时刻是用户可见的「闹钟」，用 setAlarmClock 保证准点；
            // 时段重复一天会响十几次，挂常驻闹钟图标是骚扰，改走 setExactAndAllowWhileIdle。
            trigger.type == TimeTriggerModule().id && !TimeTriggerModule.isWindowMode(trigger.parameters) -> {
                val alarmClockInfo = AlarmManager.AlarmClockInfo(nextTriggerTime.timeInMillis, intent)
                alarmManager.setAlarmClock(alarmClockInfo, intent)
            }
            else -> {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            nextTriggerTime.timeInMillis,
                            intent
                        )
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ->
                        alarmManager.setExact(
                            AlarmManager.RTC_WAKEUP,
                            nextTriggerTime.timeInMillis,
                            intent
                        )
                    else ->
                        alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            nextTriggerTime.timeInMillis,
                            intent
                        )
                }
            }
        }

        DebugLogger.d(TAG, "已为 '${trigger.workflowName}' 调度下一次触发: ${Date(nextTriggerTime.timeInMillis)}")
    }

    fun cancel(context: Context, trigger: TriggerSpec) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(createPendingIntent(context, trigger))
        DebugLogger.d(TAG, "已取消触发器 '${trigger.triggerId}' 的定时任务。")
    }

    internal fun calculateNextTriggerTime(
        trigger: TriggerSpec,
        now: Calendar = Calendar.getInstance()
    ): Calendar? {
        return when (trigger.type) {
            TimeTriggerModule().id -> calculateTimeTriggerTime(trigger, now)
            IntervalTriggerModule().id -> calculateIntervalTriggerTime(trigger, now)
            else -> null
        }
    }

    internal fun calculateTimeTriggerTime(
        trigger: TriggerSpec,
        now: Calendar = Calendar.getInstance()
    ): Calendar? {
        if (TimeTriggerModule.isWindowMode(trigger.parameters)) {
            return calculateWindowTriggerTime(trigger, now)
        }

        val time = trigger.parameters["time"] as? String ?: return null
        val days = (trigger.parameters["days"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        val parts = time.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size != 2) return null
        val hour = parts[0]
        val minute = parts[1]

        if (days.isEmpty()) {
            return Calendar.getInstance().apply {
                timeInMillis = now.timeInMillis
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(now)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }

        for (offset in 0..7) {
            val checkDay = Calendar.getInstance().apply {
                timeInMillis = now.timeInMillis
                add(Calendar.DAY_OF_YEAR, offset)
            }
            val dayOfWeek = checkDay.get(Calendar.DAY_OF_WEEK)
            if (dayOfWeek !in days) continue

            val candidateTime = Calendar.getInstance().apply {
                timeInMillis = checkDay.timeInMillis
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidateTime.after(now)) {
                return candidateTime
            }
        }
        return null
    }

    /**
     * 时段重复模式：在 [windows] 内按 [interval_minutes] 触发。
     *
     * 触发点固定对齐到「时段起点 + k×间隔」，**不**从上次触发时刻累加。
     * 这样即使闹钟被 Doze 延迟、App 重启或重新调度，落点依然在同一组格点上，
     * 不会像「间隔触发」那样越走越偏，也不会在时段外继续触发。
     *
     * 被延迟而错过的格点直接跳过，不补触发。
     */
    internal fun calculateWindowTriggerTime(
        trigger: TriggerSpec,
        now: Calendar = Calendar.getInstance()
    ): Calendar? {
        val windows = TimeTriggerModule.parseWindows(trigger.parameters["windows"])
        if (windows.isEmpty()) return null

        val stepMinutes = (trigger.parameters["interval_minutes"] as? Number)?.toInt()
            ?.takeIf { it > 0 } ?: return null
        val stepSeconds = stepMinutes * 60

        val days = (trigger.parameters["days"] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toInt() }
            ?: emptyList()

        val nowSeconds = now.get(Calendar.HOUR_OF_DAY) * 3600 +
            now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND)

        for (offset in 0..7) {
            val day = Calendar.getInstance().apply {
                timeInMillis = now.timeInMillis
                add(Calendar.DAY_OF_YEAR, offset)
            }
            if (days.isNotEmpty() && day.get(Calendar.DAY_OF_WEEK) !in days) continue

            var earliest: Calendar? = null
            for ((startMinute, endMinute) in windows) {
                val baseSeconds = startMinute * 60
                // 今天：取严格晚于当前时刻的第一个格点（+1 避免重排时原地自激）；
                // 之后几天：从时段起点算起。
                val slot = if (offset == 0) {
                    maxOf(0, Math.floorDiv(nowSeconds - baseSeconds, stepSeconds) + 1)
                } else {
                    0
                }
                val slotSeconds = baseSeconds + slot * stepSeconds
                if (slotSeconds > endMinute * 60) continue

                val candidate = Calendar.getInstance().apply {
                    timeInMillis = day.timeInMillis
                    set(Calendar.HOUR_OF_DAY, slotSeconds / 3600)
                    set(Calendar.MINUTE, slotSeconds / 60 % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (earliest == null || candidate.before(earliest)) {
                    earliest = candidate
                }
            }
            // 当天只要命中任一窗口就可以直接返回；未命中则顺延到下一天。
            if (earliest != null) return earliest
        }
        return null
    }

    internal fun calculateIntervalTriggerTime(
        trigger: TriggerSpec,
        now: Calendar = Calendar.getInstance()
    ): Calendar? {
        val interval = (trigger.parameters["interval"] as? Number)?.toLong() ?: return null
        if (interval <= 0L) return null

        val unit = (trigger.parameters["unit"] as? String)?.takeIf { it in IntervalTriggerModule.UNIT_OPTIONS }
            ?: IntervalTriggerModule.UNIT_MINUTE

        return Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            set(Calendar.MILLISECOND, 0)
            when (unit) {
                IntervalTriggerModule.UNIT_SECOND -> add(Calendar.SECOND, interval.toInt())
                IntervalTriggerModule.UNIT_HOUR -> {
                    set(Calendar.SECOND, 0)
                    add(Calendar.HOUR_OF_DAY, interval.toInt())
                }
                IntervalTriggerModule.UNIT_DAY -> {
                    set(Calendar.SECOND, 0)
                    add(Calendar.DAY_OF_YEAR, interval.toInt())
                }
                else -> {
                    set(Calendar.SECOND, 0)
                    add(Calendar.MINUTE, interval.toInt())
                }
            }
        }
    }

    private fun createPendingIntent(context: Context, trigger: TriggerSpec): PendingIntent {
        val intent = Intent(context, TimeTriggerReceiver::class.java).apply {
            action = TimeTriggerReceiver.ACTION_TRIGGER
            putExtra(TimeTriggerReceiver.EXTRA_WORKFLOW_ID, trigger.workflowId)
            putExtra(TimeTriggerReceiver.EXTRA_TRIGGER_STEP_ID, trigger.stepId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, trigger.triggerId.hashCode(), intent, flags)
    }
}
