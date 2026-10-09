package com.goodyaoshi.lemonbox.util

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

object DateUtil {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val currencyFormatter = NumberFormat.getCurrencyInstance(Locale.CHINA)
    private val zoneId: ZoneId = ZoneId.systemDefault()

    /**
     * 相对日期词：以 [from] 为「今天」基准，往后数 今天/明天/后天（后天=from+2，即明天的明天），
     * 更远用「周X」。同一天词在不同基准下含义不同，提醒文案必须按触发日为基准换算。
     */
    fun relativeDayLabel(date: LocalDate, from: LocalDate = LocalDate.now()): String = when (date) {
        from -> "今天"
        from.plusDays(1) -> "明天"
        from.plusDays(2) -> "后天"
        else -> ReminderClock.WEEKDAY_LABELS.getOrElse(date.dayOfWeek.value - 1) { date.toString() }
    }

    fun formatDate(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }

    fun formatDateTime(timestamp: Long): String {
        return dateTimeFormat.format(Date(timestamp))
    }

    fun daysUntil(timestamp: Long): Long {
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zoneId).toLocalDate()
        val target = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
        return ChronoUnit.DAYS.between(today, target)
    }

    fun isExpiringSoon(timestamp: Long, days: Int = 7): Boolean {
        val daysLeft = daysUntil(timestamp)
        return daysLeft in 0..days.toLong()
    }

    fun isExpired(timestamp: Long): Boolean {
        return daysUntil(timestamp) < 0
    }

    fun expiryText(timestamp: Long): String {
        val days = daysUntil(timestamp)
        return when {
            days < 0 -> "已过期"
            days == 0L -> "今天到期"
            days <= 30 -> "${days}天后到期"
            else -> formatDate(timestamp)
        }
    }

    fun expiryCountdownText(timestamp: Long): String {
        val days = daysUntil(timestamp)
        return when {
            days < 0 -> "已过期"
            days == 0L -> "今天到期"
            else -> "剩余${days}天"
        }
    }

    fun formatCurrency(price: Double): String {
        return currencyFormatter.format(price)
    }

    fun daysFromNow(days: Long): Long {
        return LocalDate.now(zoneId)
            .plusDays(days)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    fun monthsFromNow(months: Long): Long {
        return LocalDate.now(zoneId)
            .plusMonths(months)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    // ---- 家当「有效期」快捷选项：编码为「数值 + 单位」，如 7d / 2w / 3m / 1y ----

    /** 有效期快捷项候选（设置页展示与勾选）。 */
    val EXPIRY_QUICK_CANDIDATES = listOf(
        "3d", "7d", "15d", "30d", "1w", "2w", "1m", "2m", "3m", "6m", "1y"
    )

    /** 默认的有效期快捷项，与家当录入页出厂档位一致。 */
    val DEFAULT_EXPIRY_QUICK_CODES = listOf("7d", "1m", "3m", "6m", "12m")

    private val expiryCodeRegex = Regex("^(\\d{1,3})([dwmy])$")

    /** 校验并归一化有效期快捷项编码：转小写、剔除非法、去重、保持顺序。 */
    fun normalizeExpiryQuickCodes(codes: List<String>): List<String> = codes
        .map { it.trim().lowercase() }
        .filter { code ->
            val match = expiryCodeRegex.matchEntire(code) ?: return@filter false
            (match.groupValues[1].toIntOrNull() ?: 0) > 0
        }
        .distinct()

    /** 编码 → 展示文案，如 7d →「7天」、3m →「3个月」、1y →「1年」。 */
    fun expiryQuickLabel(code: String): String {
        val match = expiryCodeRegex.matchEntire(code.trim().lowercase()) ?: return code
        val unit = when (match.groupValues[2]) {
            "d" -> "天"
            "w" -> "周"
            "m" -> "个月"
            "y" -> "年"
            else -> ""
        }
        return match.groupValues[1] + unit
    }

    /** 编码 → 到期时间戳（以今天为基准）；非法返回 null。 */
    fun expiryQuickTimestamp(code: String): Long? {
        val match = expiryCodeRegex.matchEntire(code.trim().lowercase()) ?: return null
        val value = match.groupValues[1].toLongOrNull() ?: return null
        if (value <= 0) return null
        return when (match.groupValues[2]) {
            "d" -> daysFromNow(value)
            "w" -> daysFromNow(value * 7)
            "m" -> monthsFromNow(value)
            "y" -> monthsFromNow(value * 12)
            else -> null
        }
    }

    /** 把上次同步时间换算成「还没有同步过/今天同步过/昨天同步过/已 N 天未同步」。 */
    fun relativeSyncText(timestamp: Long?): String {
        if (timestamp == null) return "还没有同步过"
        val today = LocalDate.now(zoneId)
        val last = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
        val days = ChronoUnit.DAYS.between(last, today)
        return when {
            days <= 0L -> "今天同步过"
            days == 1L -> "昨天同步过"
            else -> "已 ${days} 天未同步"
        }
    }
}
