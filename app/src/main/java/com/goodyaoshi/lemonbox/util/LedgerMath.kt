package com.goodyaoshi.lemonbox.util

import java.time.LocalDate
import java.time.ZoneId

/**
 * 记账金额与账期计算的纯函数集合。
 * 金额以「分」为单位存储（Long），展示时换算成元再走
 * [DateUtil.formatCurrency]，与家当价格的展示口径保持一致。
 */
object LedgerMath {

    /** 分 → 元（Double），供既有金额展示工具复用。 */
    fun centsToYuan(cents: Long): Double = cents / 100.0

    /** 元（Double）→ 分；必须四舍五入，禁止浮点直接截断（会差一分钱）。 */
    fun yuanToCents(yuan: Double): Long = Math.round(yuan * 100)

    /** 物品联动金额：单价（元）× 数量 → 分。 */
    fun itemPriceToCents(price: Double, quantity: Int): Long = yuanToCents(price) * quantity

    /**
     * 分 → 键盘输入文本（去掉多余的 0）：1250 → "12.5"，1200 → "12"，
     * 1205 → "12.05"，0 → ""。供编辑页预填金额。
     */
    fun centsToInputText(cents: Long): String {
        if (cents <= 0) return ""
        val yuan = cents / 100
        val fen = cents % 100
        return when {
            fen == 0L -> yuan.toString()
            fen % 10 == 0L -> "$yuan.${fen / 10}"
            else -> "$yuan." + fen.toString().padStart(2, '0')
        }
    }

    /** 键盘输入文本 → 分；空、非法或超过两位小数返回 null。 */
    fun parseCentsInput(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(".")
        if (parts.size > 2) return null
        val yuanPart = parts[0]
        val fenPart = parts.getOrNull(1).orEmpty()
        if (yuanPart.isEmpty() && fenPart.isEmpty()) return null
        if (yuanPart.isNotEmpty() && yuanPart.any { it !in '0'..'9' }) return null
        if (fenPart.length > 2 || fenPart.any { it !in '0'..'9' }) return null
        val yuan = if (yuanPart.isEmpty()) 0L else yuanPart.toLong()
        val fen = when (fenPart.length) {
            0 -> 0L
            1 -> fenPart.toLong() * 10
            else -> fenPart.toLong()
        }
        return yuan * 100 + fen
    }

    /**
     * 账期窗口 [start, end)：默认自然月；startDay 支持 1-28（月起始日），
     * 例如 startDay=7 时「10 月」指 10-07 ~ 11-06。
     */
    fun monthRange(
        year: Int,
        month: Int,
        startDay: Int = 1,
        zone: ZoneId = ZoneId.systemDefault()
    ): Pair<Long, Long> {
        val clamped = startDay.coerceIn(1, 28)
        val start = LocalDate.of(year, month, 1).plusDays((clamped - 1).toLong())
        val end = start.plusMonths(1)
        return start.atStartOfDay(zone).toInstant().toEpochMilli() to
            end.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** 预算使用比例（spent/budget）；未设预算或预算 <= 0 返回 null，不展示进度。 */
    fun budgetRatio(spent: Long, budget: Long?): Float? {
        if (budget == null || budget <= 0) return null
        return spent.toFloat() / budget
    }
}
