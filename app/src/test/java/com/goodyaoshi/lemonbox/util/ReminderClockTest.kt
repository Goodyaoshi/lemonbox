package com.goodyaoshi.lemonbox.util

import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.ReminderRepeatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 提醒触发时间的纯计算测试。
 * 固定「现在」= 2026-10-04（周日）12:00，避免依赖系统时钟。
 */
class ReminderClockTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: ZonedDateTime = LocalDateTime.of(2026, 10, 4, 12, 0).atZone(zone)

    private fun reminder(
        repeatType: ReminderRepeatType,
        fireTime: String = "19:00",
        targetDate: String? = null,
        intervalDays: Int = 1,
        weekdays: String = ""
    ) = Reminder(
        title = "测试提醒",
        repeatType = repeatType.name,
        fireTime = fireTime,
        targetDate = targetDate,
        intervalDays = intervalDays,
        weekdays = weekdays
    )

    private fun expected(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    // —— firstFireAt：创建时算首轮 ——

    @Test
    fun firstFireAt_onceInFuture_returnsTodayTimestamp() {
        val r = reminder(ReminderRepeatType.ONCE, targetDate = "2026-10-04", fireTime = "19:00")
        assertEquals(expected(LocalDate.of(2026, 10, 4), 19), ReminderClock.firstFireAt(r, now))
    }

    @Test
    fun firstFireAt_onceTimePassed_returnsNull() {
        val r = reminder(ReminderRepeatType.ONCE, targetDate = "2026-10-04", fireTime = "08:00")
        assertNull(ReminderClock.firstFireAt(r, now))
    }

    @Test
    fun firstFireAt_onceDatePassed_returnsNull() {
        val r = reminder(ReminderRepeatType.ONCE, targetDate = "2026-10-03", fireTime = "19:00")
        assertNull(ReminderClock.firstFireAt(r, now))
    }

    @Test
    fun firstFireAt_dailyTimeNotReached_returnsToday() {
        val r = reminder(ReminderRepeatType.DAILY, fireTime = "19:00")
        assertEquals(expected(LocalDate.of(2026, 10, 4), 19), ReminderClock.firstFireAt(r, now))
    }

    @Test
    fun firstFireAt_dailyTimePassed_returnsTomorrow() {
        val r = reminder(ReminderRepeatType.DAILY, fireTime = "08:00")
        assertEquals(expected(LocalDate.of(2026, 10, 5), 8), ReminderClock.firstFireAt(r, now))
    }

    @Test
    fun firstFireAt_interval_countsFromTomorrow() {
        // 首轮从「明天」起算一个间隔：10/4 建「每 3 天」→ 10/7
        val r = reminder(ReminderRepeatType.INTERVAL, fireTime = "19:00", intervalDays = 3)
        assertEquals(expected(LocalDate.of(2026, 10, 7), 19), ReminderClock.firstFireAt(r, now))
    }

    @Test
    fun firstFireAt_weekly_hitsTodayWhenNotReached() {
        // 今天是周日，19:00 还没到 → 当天算
        val r = reminder(ReminderRepeatType.WEEKLY, fireTime = "19:00", weekdays = "7")
        assertEquals(expected(LocalDate.of(2026, 10, 4), 19), ReminderClock.firstFireAt(r, now))
    }

    @Test
    fun firstFireAt_weekly_passedJumpsToNextWeek() {
        // 本周一 9/28 已过 → 下周一 10/5
        val r = reminder(ReminderRepeatType.WEEKLY, fireTime = "08:00", weekdays = "1")
        assertEquals(expected(LocalDate.of(2026, 10, 5), 8), ReminderClock.firstFireAt(r, now))
    }

    @Test
    fun firstFireAt_weeklyEmptyWeekdays_returnsNull() {
        val r = reminder(ReminderRepeatType.WEEKLY, weekdays = "")
        assertNull(ReminderClock.firstFireAt(r, now))
    }

    // —— nextAfter：触发/完成后的下一轮 ——

    @Test
    fun nextAfter_once_returnsNull() {
        val r = reminder(ReminderRepeatType.ONCE, targetDate = "2026-10-05", fireTime = "19:00")
        assertNull(ReminderClock.nextAfter(r, LocalDate.of(2026, 10, 5), now))
    }

    @Test
    fun nextAfter_dailyEarlyCompletion_sameDayStillCounts() {
        // 「提前完成」：当天 19:00 还没到 → 当天 19:00 仍算数
        val r = reminder(ReminderRepeatType.DAILY, fireTime = "19:00")
        assertEquals(
            expected(LocalDate.of(2026, 10, 4), 19),
            ReminderClock.nextAfter(r, LocalDate.of(2026, 10, 4), now)
        )
    }

    @Test
    fun nextAfter_interval_fromAnchorPlusStep() {
        val r = reminder(ReminderRepeatType.INTERVAL, fireTime = "19:00", intervalDays = 3)
        assertEquals(
            expected(LocalDate.of(2026, 10, 7), 19),
            ReminderClock.nextAfter(r, LocalDate.of(2026, 10, 4), now)
        )
    }

    @Test
    fun nextAfter_interval_staleAnchorAdvancesToFuture() {
        // anchor 已过期（后台晚跑）：10/1 + 3 = 10/4 08:00 已过 → 再推一格到 10/7 08:00
        val r = reminder(ReminderRepeatType.INTERVAL, fireTime = "08:00", intervalDays = 3)
        assertEquals(
            expected(LocalDate.of(2026, 10, 7), 8),
            ReminderClock.nextAfter(r, LocalDate.of(2026, 10, 1), now)
        )
    }

    @Test
    fun nextAfter_weekly_jumpsToNextMatchingWeekday() {
        // anchor=周日 10/4，周几=周一 → 10/5 08:00
        val r = reminder(ReminderRepeatType.WEEKLY, fireTime = "08:00", weekdays = "1")
        assertEquals(
            expected(LocalDate.of(2026, 10, 5), 8),
            ReminderClock.nextAfter(r, LocalDate.of(2026, 10, 4), now)
        )
    }

    // —— 展示文案 ——

    @Test
    fun describeRepeat_coversAllTypes() {
        assertEquals(
            "一次性",
            ReminderClock.describeRepeat(reminder(ReminderRepeatType.ONCE, targetDate = "2026-10-05"))
        )
        assertEquals("每天", ReminderClock.describeRepeat(reminder(ReminderRepeatType.DAILY)))
        assertEquals(
            "每 3 天",
            ReminderClock.describeRepeat(reminder(ReminderRepeatType.INTERVAL, intervalDays = 3))
        )
        assertEquals(
            "每周一、周四",
            ReminderClock.describeRepeat(
                reminder(ReminderRepeatType.WEEKLY, weekdays = Reminder.encodeWeekdays(listOf(4, 1)))
            )
        )
    }

    @Test
    fun fireAtText_labelsTodayTomorrowAndDate() {
        val today = LocalDate.of(2026, 10, 4)
        assertEquals("今天 19:00", ReminderClock.fireAtText(expected(today, 19), today))
        assertEquals("明天 08:00", ReminderClock.fireAtText(expected(today.plusDays(1), 8), today))
        assertEquals("10/12 20:00", ReminderClock.fireAtText(expected(today.plusDays(8), 20), today))
    }
}
