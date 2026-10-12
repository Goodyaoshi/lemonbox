package com.goodyaoshi.lemonbox.util

import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecurringRule
import java.time.Instant
import java.time.ZoneId

/**
 * 周期记账规则的纯时间计算：把下一次触发时间按周期往前推一步。
 *
 * 用 java.time 做月份/年份加法，天然处理月末回退（1/31 + 1 月 = 2/28），
 * 避免手写月份算术的边界 bug。
 */
object RecurringClock {

    /** 从 [from] 起推进 [interval] 个单位后的时间戳；[interval] 至少为 1。 */
    fun advance(
        from: Long,
        unit: LedgerRecurringRule.RepeatUnit,
        interval: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long {
        val step = interval.coerceAtLeast(1)
        val zoned = Instant.ofEpochMilli(from).atZone(zone)
        val next = when (unit) {
            LedgerRecurringRule.RepeatUnit.DAY -> zoned.plusDays(step.toLong())
            LedgerRecurringRule.RepeatUnit.WEEK -> zoned.plusWeeks(step.toLong())
            LedgerRecurringRule.RepeatUnit.MONTH -> zoned.plusMonths(step.toLong())
            LedgerRecurringRule.RepeatUnit.YEAR -> zoned.plusYears(step.toLong())
        }
        return next.toInstant().toEpochMilli()
    }

    /** 周期单位的中文名（规则列表展示用）。 */
    fun unitLabel(unit: LedgerRecurringRule.RepeatUnit): String = when (unit) {
        LedgerRecurringRule.RepeatUnit.DAY -> "天"
        LedgerRecurringRule.RepeatUnit.WEEK -> "周"
        LedgerRecurringRule.RepeatUnit.MONTH -> "月"
        LedgerRecurringRule.RepeatUnit.YEAR -> "年"
    }
}
