package com.goodyaoshi.lemonbox.util

import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.ReminderRepeatType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 提醒触发时间的纯计算：创建算首轮、触发/完成后推进下一轮都走这里，
 * 三个入口共用同一套规则，也方便单测覆盖各重复方式。
 */
object ReminderClock {

    /** 周几的展示名，索引 = dayOfWeek.value - 1。 */
    val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * 创建提醒时算首轮触发时间。
     * 一次性提醒的时刻已经过了（比如给今天 19:00 建提醒但现在是 21:00）返回 null，调用方拒绝保存。
     */
    fun firstFireAt(reminder: Reminder, now: ZonedDateTime = ZonedDateTime.now()): Long? {
        if (reminder.repeat == ReminderRepeatType.ONCE) {
            val date = reminder.targetDate
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            val target = at(date, reminder.fireTime, now.zone) ?: return null
            return target.takeIf { it.isAfter(now) }?.toInstant()?.toEpochMilli()
        }
        return nextAfter(reminder, now.toLocalDate(), now)
    }

    /**
     * 周期提醒在 [anchor]（触发日或完成日）之后的下一轮触发时间。
     * 「提前完成」时若当天的时刻还没到，当天仍然算数（比如周三早上就完成了「每周三 19:00」的任务）。
     * 一次性提醒没有下一轮，返回 null。
     */
    fun nextAfter(reminder: Reminder, anchor: LocalDate, now: ZonedDateTime): Long? {
        return when (reminder.repeat) {
            ReminderRepeatType.ONCE -> null
            ReminderRepeatType.DAILY -> {
                if (reminder.isMedication) {
                    return nextMedicationDose(reminder, anchor, now)
                }
                var candidate = at(anchor, reminder.fireTime, now.zone) ?: return null
                if (!candidate.isAfter(now)) {
                    candidate = candidate.plusDays(1)
                }
                candidate.toInstant().toEpochMilli()
            }

            ReminderRepeatType.INTERVAL -> {
                val step = reminder.intervalDays.coerceAtLeast(1)
                var candidate = at(anchor.plusDays(step.toLong()), reminder.fireTime, now.zone)
                    ?: return null
                // 后台任务可能晚跑（Doze/重启），一格一格推进到未来。
                while (!candidate.isAfter(now)) {
                    candidate = candidate.plusDays(step.toLong())
                }
                candidate.toInstant().toEpochMilli()
            }

            ReminderRepeatType.WEEKLY -> {
                val weekdays = Reminder.decodeWeekdays(reminder.weekdays)
                if (weekdays.isEmpty()) return null
                // 最多扫两周，覆盖任意周几组合。
                (0..13L).asSequence()
                    .map { offset -> at(anchor.plusDays(offset), reminder.fireTime, now.zone) }
                    .filterIndexed { index, candidate ->
                        candidate != null && candidate.isAfter(now) &&
                            weekdays.contains(anchor.plusDays(index.toLong()).dayOfWeek.value)
                    }
                    .firstOrNull()
                    ?.toInstant()
                    ?.toEpochMilli()
            }
        }
    }

    /** 触发时间的展示文案：今天 19:00 / 明天 08:00 / 10/12 20:00。 */
    fun fireAtText(nextFireAt: Long, today: LocalDate = LocalDate.now()): String {
        val zoned = Instant.ofEpochMilli(nextFireAt).atZone(ZoneId.systemDefault())
        val date = zoned.toLocalDate()
        val dayLabel = when (date) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> "${date.monthValue}/${date.dayOfMonth}"
        }
        return "$dayLabel ${zoned.format(TIME_FORMAT)}"
    }

    /** 重复方式的展示文案：一次性 / 每天 / 每 3 天 / 每周一、周四 / 每天 3 次（用药）。 */
    fun describeRepeat(reminder: Reminder): String = when {
        reminder.repeat == ReminderRepeatType.ONCE -> "一次性"
        reminder.isMedication ->
            "每天 ${Reminder.decodeDoseTimes(reminder.doseTimes).size.coerceAtLeast(1)} 次" +
                describeCourse(reminder)
        else -> when (reminder.repeat) {
            ReminderRepeatType.DAILY -> "每天"
            ReminderRepeatType.INTERVAL -> "每 ${reminder.intervalDays} 天"
            ReminderRepeatType.WEEKLY -> "每" + Reminder.decodeWeekdays(reminder.weekdays)
                .joinToString("、") { WEEKDAY_LABELS.getOrElse(it - 1) { "" } }
            else -> "一次性"
        }
    }

    /** 疗程进度文案（F17）：疗程还剩 3 天 / 不限疗程。 */
    fun describeCourse(reminder: Reminder): String {
        val end = medicationCourseEnd(reminder) ?: return " · 不限疗程"
        val today = LocalDate.now()
        val left = ChronoUnit.DAYS.between(today, end)
        return when {
            left < 0 -> " · 疗程已结束"
            left == 0L -> " · 疗程今天结束"
            else -> " · 疗程还剩 $left 天"
        }
    }

    /** 疗程结束日（含当天）；未配疗程返回 null。 */
    fun medicationCourseEnd(reminder: Reminder): LocalDate? {
        val start = reminder.courseStartDate
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val days = reminder.courseDays
        if (days <= 0) return null
        return start.plusDays(days.toLong() - 1)
    }

    /** 今天是疗程第 N 天；未配疗程或已超出返回 null。 */
    fun medicationCourseDay(reminder: Reminder, today: LocalDate = LocalDate.now()): Int? {
        val start = reminder.courseStartDate
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val days = reminder.courseDays
        if (days <= 0) return null
        val day = ChronoUnit.DAYS.between(start, today).toInt() + 1
        return day.takeIf { it in 1..days }
    }

    /**
     * 按疗程用药的下一剂（F17）：疗程内每天按 [Reminder.doseTimes] 的时刻多次服药。
     * 从锚点日逐格推进到第一个晚于 now 的时刻；停机错过的剂量直接跳过不补发，
     * 疗程走完返回 null（调用方会把提醒停用）。
     */
    private fun nextMedicationDose(
        reminder: Reminder,
        anchor: LocalDate,
        now: ZonedDateTime
    ): Long? {
        val times = Reminder.decodeDoseTimes(reminder.doseTimes)
        if (times.isEmpty()) return null
        val end = medicationCourseEnd(reminder) ?: anchor.plusDays(MEDICATION_SCAN_LIMIT_DAYS.toLong())
        var date = anchor
        var guard = 0
        while (!date.isAfter(end) && guard <= MEDICATION_SCAN_LIMIT_DAYS) {
            for (time in times) {
                val candidate = date.atTime(time).atZone(now.zone)
                if (candidate.isAfter(now)) {
                    return candidate.toInstant().toEpochMilli()
                }
            }
            date = date.plusDays(1)
            guard++
        }
        return null
    }

    private const val MEDICATION_SCAN_LIMIT_DAYS = 60

    private fun at(date: LocalDate?, fireTime: String, zone: ZoneId): ZonedDateTime? {
        val time = runCatching { LocalTime.parse(fireTime.trim(), TIME_FORMAT) }.getOrNull()
            ?: return null
        return date?.atTime(time)?.atZone(zone)
    }
}
