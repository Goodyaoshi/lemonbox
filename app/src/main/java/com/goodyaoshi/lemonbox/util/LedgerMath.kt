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

    /**
     * 键盘输入文本 → 分。
     * 除纯数字（最多两位小数）外，也支持由 + - × ÷ 组成的算式，
     * 例如 `12+3.5`、`20×3-5`、`100-8/2`，方便「买多件/有返现/补运费」一次算出净额。
     * 以「分」做整数运算（除法向下取整）；空、非法、除零或存在未完成运算时返回 null。
     */
    fun parseCentsInput(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val normalized = trimmed
            .replace('×', '*')
            .replace('÷', '/')
            .replace('X', '*')
            .replace('x', '*')
        return AmountExpression(normalized).evaluate()
    }

    /** 算式里的单个操作数（纯数字文本）→ 分；非法或超过两位小数返回 null。 */
    private fun parseOperand(text: String): Long? {
        if (text.isEmpty()) return null
        val parts = text.split(".")
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
     * 简易算式求值器：仅支持 + - * /，无括号，乘除优先、同级左结合。
     * 全程以「分」为单位做整数运算；遇到非法字符、缺少操作数或除零时返回 null。
     */
    private class AmountExpression(private val source: String) {
        private var pos = 0

        fun evaluate(): Long? {
            val value = parseSum() ?: return null
            return if (pos == source.length) value else null
        }

        private fun parseSum(): Long? {
            var left = parseProduct() ?: return null
            while (pos < source.length && (source[pos] == '+' || source[pos] == '-')) {
                val op = source[pos++]
                val right = parseProduct() ?: return null
                left = if (op == '+') left + right else left - right
            }
            return left
        }

        private fun parseProduct(): Long? {
            var left = parseNumber() ?: return null
            while (pos < source.length && (source[pos] == '*' || source[pos] == '/')) {
                val op = source[pos++]
                val right = parseNumber() ?: return null
                left = if (op == '*') {
                    left * right
                } else {
                    if (right == 0L) return null
                    left / right
                }
            }
            return left
        }

        private fun parseNumber(): Long? {
            val start = pos
            var dotSeen = false
            var fenDigits = 0
            while (pos < source.length) {
                val c = source[pos]
                when {
                    c in '0'..'9' -> {
                        if (dotSeen) fenDigits++
                        pos++
                    }

                    c == '.' && !dotSeen -> {
                        dotSeen = true
                        pos++
                    }

                    else -> break
                }
            }
            if (pos == start) return null
            if (fenDigits > 2) return null
            return parseOperand(source.substring(start, pos))
        }
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
