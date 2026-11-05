package com.chaomixian.vflow.core.workflow.module.triggers.handlers

import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.model.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class AlarmTriggerSchedulerTest {

    @Test
    fun `time trigger schedules next matching weekday`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.APRIL, 21, 10, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val trigger = TriggerSpec(
            workflow = Workflow(id = "workflow-1", name = "Morning Alarm"),
            step = ActionStep(
                id = "trigger-1",
                moduleId = "vflow.trigger.time",
                parameters = mapOf(
                    "time" to "09:00",
                    "days" to listOf(Calendar.WEDNESDAY)
                )
            )
        )

        val next = AlarmTriggerScheduler.calculateNextTriggerTime(trigger, now)

        assertNotNull(next)
        assertEquals(Calendar.WEDNESDAY, next!!.get(Calendar.DAY_OF_WEEK))
        assertEquals(9, next.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, next.get(Calendar.MINUTE))
        assertEquals(2026, next.get(Calendar.YEAR))
        assertEquals(Calendar.APRIL, next.get(Calendar.MONTH))
        assertEquals(22, next.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `interval trigger schedules next hour based on interval`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.APRIL, 21, 10, 15, 48)
            set(Calendar.MILLISECOND, 512)
        }
        val trigger = TriggerSpec(
            workflow = Workflow(id = "workflow-2", name = "Hourly Poll"),
            step = ActionStep(
                id = "trigger-2",
                moduleId = "vflow.trigger.interval",
                parameters = mapOf(
                    "interval" to 2L,
                    "unit" to "hour"
                )
            )
        )

        val next = AlarmTriggerScheduler.calculateNextTriggerTime(trigger, now)

        assertNotNull(next)
        assertEquals(12, next!!.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, next.get(Calendar.MINUTE))
        assertEquals(0, next.get(Calendar.SECOND))
        assertEquals(0, next.get(Calendar.MILLISECOND))
    }

    @Test
    fun `interval trigger supports seconds`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.APRIL, 21, 10, 15, 48)
            set(Calendar.MILLISECOND, 512)
        }
        val trigger = TriggerSpec(
            workflow = Workflow(id = "workflow-4", name = "Second Poll"),
            step = ActionStep(
                id = "trigger-4",
                moduleId = "vflow.trigger.interval",
                parameters = mapOf(
                    "interval" to 5L,
                    "unit" to "second"
                )
            )
        )

        val next = AlarmTriggerScheduler.calculateNextTriggerTime(trigger, now)

        assertNotNull(next)
        assertEquals(10, next!!.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, next.get(Calendar.MINUTE))
        assertEquals(53, next.get(Calendar.SECOND))
        assertEquals(0, next.get(Calendar.MILLISECOND))
    }

    @Test
    fun `interval trigger rejects non positive interval`() {
        val now = Calendar.getInstance()
        val trigger = TriggerSpec(
            workflow = Workflow(id = "workflow-3", name = "Broken"),
            step = ActionStep(
                id = "trigger-3",
                moduleId = "vflow.trigger.interval",
                parameters = mapOf(
                    "interval" to 0L,
                    "unit" to "minute"
                )
            )
        )

        val next = AlarmTriggerScheduler.calculateNextTriggerTime(trigger, now)

        assertNull(next)
    }

    // ---- 时段重复模式 ----

    private fun windowTrigger(
        id: String,
        windows: List<String>,
        intervalMinutes: Long = 10L,
        days: List<Int> = listOf(
            Calendar.SUNDAY,
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY,
            Calendar.SATURDAY
        )
    ) = TriggerSpec(
        workflow = Workflow(id = "workflow-$id", name = "Window $id"),
        step = ActionStep(
            id = id,
            moduleId = "vflow.trigger.time",
            parameters = mapOf(
                "mode" to "window",
                "windows" to windows,
                "interval_minutes" to intervalMinutes,
                "days" to days
            )
        )
    )

    private fun calendarAt(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }

    @Test
    fun `window trigger aligns to grid instead of drifting from now`() {
        // 2026-04-21 是周二
        val now = calendarAt(2026, Calendar.APRIL, 21, 10, 3)
        val next = AlarmTriggerScheduler.calculateNextTriggerTime(
            windowTrigger("w1", listOf("09:30-11:30")),
            now
        )

        assertNotNull(next)
        assertEquals(10, next!!.get(Calendar.HOUR_OF_DAY))
        assertEquals(10, next.get(Calendar.MINUTE))
        assertEquals(0, next.get(Calendar.SECOND))
    }

    @Test
    fun `window trigger does not fire before window start`() {
        val now = calendarAt(2026, Calendar.APRIL, 21, 9, 0)
        val next = AlarmTriggerScheduler.calculateNextTriggerTime(
            windowTrigger("w2", listOf("09:30-11:30")),
            now
        )

        assertNotNull(next)
        assertEquals(9, next!!.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, next.get(Calendar.MINUTE))
    }

    @Test
    fun `window trigger includes window end boundary`() {
        val now = calendarAt(2026, Calendar.APRIL, 21, 11, 20)
        val next = AlarmTriggerScheduler.calculateNextTriggerTime(
            windowTrigger("w3", listOf("09:30-11:30")),
            now
        )

        assertNotNull(next)
        assertEquals(11, next!!.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, next.get(Calendar.MINUTE))
    }

    @Test
    fun `window trigger jumps to the next window on the same day`() {
        val now = calendarAt(2026, Calendar.APRIL, 21, 11, 45)
        val next = AlarmTriggerScheduler.calculateNextTriggerTime(
            windowTrigger("w4", listOf("09:30-11:30", "13:00-15:00")),
            now
        )

        assertNotNull(next)
        assertEquals(Calendar.TUESDAY, next!!.get(Calendar.DAY_OF_WEEK))
        assertEquals(13, next.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, next.get(Calendar.MINUTE))
    }

    @Test
    fun `window trigger advances to the next allowed weekday`() {
        val now = calendarAt(2026, Calendar.APRIL, 21, 15, 10)
        val next = AlarmTriggerScheduler.calculateNextTriggerTime(
            windowTrigger("w5", listOf("13:00-15:00"), days = listOf(Calendar.WEDNESDAY)),
            now
        )

        assertNotNull(next)
        assertEquals(Calendar.WEDNESDAY, next!!.get(Calendar.DAY_OF_WEEK))
        assertEquals(22, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(13, next.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, next.get(Calendar.MINUTE))
    }

    @Test
    fun `window trigger rejects malformed windows`() {
        val now = calendarAt(2026, Calendar.APRIL, 21, 10, 0)
        assertNull(
            AlarmTriggerScheduler.calculateNextTriggerTime(
                windowTrigger("w6", listOf("11:30-09:30")),
                now
            )
        )
        assertNull(
            AlarmTriggerScheduler.calculateNextTriggerTime(
                windowTrigger("w7", listOf("not-a-window")),
                now
            )
        )
    }

    @Test
    fun `window trigger rejects non positive interval`() {
        val now = calendarAt(2026, Calendar.APRIL, 21, 10, 0)
        assertNull(
            AlarmTriggerScheduler.calculateNextTriggerTime(
                windowTrigger("w8", listOf("09:30-11:30"), intervalMinutes = 0L),
                now
            )
        )
    }
}
