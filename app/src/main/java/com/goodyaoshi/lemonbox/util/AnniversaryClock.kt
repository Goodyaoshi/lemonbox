package com.goodyaoshi.lemonbox.util

import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import com.tyme.lunar.LunarDay
import com.tyme.lunar.LunarMonth
import com.tyme.solar.SolarDay
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * 纪念日的日期纯计算：下一次发生日、剩余/累计天数、提前提醒命中。
 * 创建、展示、通知三条入口共用同一套规则，也方便单测覆盖农历与闰月等边界。
 *
 * 统一约定：日期不存在时「就近取整」——公历 2/29 在平年取 2/28，
 * 大月 31 号在小月取当月最后一天，农历三十在小月取廿九（当月最后一天），闰月生日按平月过。
 */
object AnniversaryClock {

    /** 解析 yyyy-MM-dd 锚点日期；格式非法返回 null。 */
    fun parseDate(encoded: String): LocalDate? =
        runCatching { LocalDate.parse(encoded.trim()) }.getOrNull()

    /**
     * 下一次发生的公历日期（今天当天返回今天）：
     * - 不循环：就是锚点日（过去返回过去，用于累计）；
     * - 每 N 天/周：锚点 + k·N 步长对齐（周循环保持锚点的星期几）；
     * - 每 N 月：月份推进、日不存在取当月最后一天；
     * - 每年：公历按年推进（2/29 平年取 2/28）；农历按农历年扫描换算。
     */
    fun nextOccurrence(anniversary: Anniversary, today: LocalDate = LocalDate.now()): LocalDate? {
        val anchor = parseDate(anniversary.date) ?: return null
        val interval = anniversary.repeatInterval.coerceAtLeast(1)
        return when (Anniversary.normalizeRepeatUnit(anniversary.repeatUnit)) {
            Anniversary.REPEAT_DAY -> nextByFixedDays(anchor, interval.toLong(), today)
            Anniversary.REPEAT_WEEK -> nextByFixedDays(anchor, 7L * interval, today)
            Anniversary.REPEAT_MONTH -> nextByMonths(anchor, interval, today)
            Anniversary.REPEAT_YEAR -> {
                if (anniversary.isLunar) {
                    // 农历按农历年扫描：农历年 L 的日期横跨公历 L 年初与 L+1 年初，
                    // 扫 L-1..L+1 必然覆盖今天之后的下一次。农历没有「每 N 年」口径，间隔视为 1。
                    nextLunarYearly(anniversary, today)
                } else {
                    nextByYears(anchor, interval, today)
                }
            }
            // 未知单位兜底按不循环
            else -> anchor
        }
    }

    /**
     * 距下一次发生的天数：未来正数、当天 0、过去负数（累计型按负数展示「已 N 天」）。
     * 算不出日期（锚点非法）返回 null。
     */
    fun daysUntil(anniversary: Anniversary, today: LocalDate = LocalDate.now()): Long? {
        val next = nextOccurrence(anniversary, today) ?: return null
        return ChronoUnit.DAYS.between(today, next)
    }

    /** 今天是否应该提醒：提前提醒阶梯非空，且距下一次的天数正好落在阶梯上。 */
    fun isDueOn(anniversary: Anniversary, today: LocalDate = LocalDate.now()): Boolean {
        val ladder = Anniversary.decodeReminderDays(anniversary.remindDays)
        if (ladder.isEmpty()) return false
        val days = daysUntil(anniversary, today) ?: return false
        return ladder.any { it.toLong() == days }
    }

    /** 农历展示名（如「农历八月十五」）；非农历或数据非法返回 null。 */
    fun lunarLabel(anniversary: Anniversary): String? {
        if (!anniversary.isLunar) return null
        val month = anniversary.lunarMonth
        val day = anniversary.lunarDay
        if (month !in 1..12 || day !in 1..30) return null
        // 月/日名与年份无关，任取一个合法年份换名字即可。
        return "农历${LunarMonth.fromYm(2024, month).getName()}${LunarDay.NAMES[day - 1]}"
    }

    /** 生日在指定日期满几岁（按公历年差）；算不出返回 null。 */
    fun ageAt(anchor: LocalDate, on: LocalDate): Int =
        ChronoUnit.YEARS.between(anchor, on).toInt()

    /**
     * 生日在下一次发生时满几岁：公历按整年差；农历按农历年差——农历发生日的
     * 公历月日不与出生那天对齐，整年差会少算一岁（如 1998 农历九月二十出生，
     * 下次 2026/10/29 发生，公历整年差 27，实际过完这个生日满 28）。
     */
    fun ageTurnedAt(anchor: LocalDate, next: LocalDate, isLunar: Boolean): Int? {
        if (!isLunar) {
            return ageAt(anchor, next)
        }
        val birthYear = solarToLunar(anchor)?.first ?: return null
        val occurYear = solarToLunar(next)?.first ?: return null
        return occurYear - birthYear
    }

    /** 公历日期的星座名（如「天秤座」；tyme 星座名不带座字，这里补上）；算不出返回 null。 */
    fun constellationLabel(solar: LocalDate): String? =
        runCatching {
            SolarDay.fromYmd(solar.year, solar.monthValue, solar.dayOfMonth)
                .getConstellation().getName() + "座"
        }.getOrNull()

    /** 公历日期出生的生肖名（按出生那天所属农历年，如「龙」）；算不出返回 null。 */
    fun zodiacLabel(solar: LocalDate): String? =
        runCatching {
            SolarDay.fromYmd(solar.year, solar.monthValue, solar.dayOfMonth)
                .getLunarDay()
                .getLunarMonth()
                .getLunarYear()
                .getSixtyCycle()
                .getEarthBranch()
                .getZodiac()
                .getName()
        }.getOrNull()

    /** 农历月/日在指定农历年对应的公历日期；小月缺三十回退当月月末。 */
    fun lunarToSolar(lunarYear: Int, lunarMonth: Int, lunarDay: Int): LocalDate? {
        if (lunarMonth !in 1..12 || lunarDay !in 1..30) return null
        return try {
            val dayCount = LunarMonth.fromYm(lunarYear, lunarMonth).getDayCount()
            val day = LunarDay.fromYmd(lunarYear, lunarMonth, lunarDay.coerceAtMost(dayCount))
            val solar = day.getSolarDay()
            LocalDate.of(solar.year, solar.month, solar.day)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** 公历日期换算农历年/月/日（闰月返回平月月号）；非法日期返回 null。 */
    fun solarToLunar(solar: LocalDate): Triple<Int, Int, Int>? = runCatching {
        val day = SolarDay.fromYmd(solar.year, solar.monthValue, solar.dayOfMonth).getLunarDay()
        val month = day.getLunarMonth()
        Triple(month.getLunarYear().year, month.month, day.day)
    }.getOrNull()

    /** 每 N 天/周的下一步长对齐：锚点 + k·步长，第一个不早于今天的落点。 */
    private fun nextByFixedDays(anchor: LocalDate, stepDays: Long, today: LocalDate): LocalDate {
        val daysFromAnchor = ChronoUnit.DAYS.between(anchor, today)
        if (daysFromAnchor <= 0) return anchor
        val k = (daysFromAnchor + stepDays - 1) / stepDays
        return anchor.plusDays(k * stepDays)
    }

    /** 每 N 月的推进：以锚点的「月-日」为准做月份算术，日不存在时取当月最后一天。 */
    private fun nextByMonths(anchor: LocalDate, stepMonths: Int, today: LocalDate): LocalDate {
        val anchorMonth = YearMonth.from(anchor)
        var total = monthsBetween(anchorMonth, YearMonth.from(today)) / stepMonths
        if (total < 0) total = 0
        var candidate = solarInMonth(anchor, anchorMonth.plusMonths(total * stepMonths))
        while (candidate.isBefore(today)) {
            total += 1
            candidate = solarInMonth(anchor, anchorMonth.plusMonths(total * stepMonths))
        }
        return candidate
    }

    /** 每 N 年的推进：日不存在（2/29）平年回退 2/28（取当月最后一天）。 */
    private fun nextByYears(anchor: LocalDate, stepYears: Int, today: LocalDate): LocalDate {
        var k = (today.year - anchor.year) / stepYears
        if (k < 0) k = 0
        var candidate = solarInYear(anchor, anchor.year + k * stepYears)
        while (candidate.isBefore(today)) {
            k += 1
            candidate = solarInYear(anchor, anchor.year + k * stepYears)
        }
        return candidate
    }

    /** 农历每年循环在今天的下一次：扫 L-1..L+1 农历年。 */
    private fun nextLunarYearly(anniversary: Anniversary, today: LocalDate): LocalDate? =
        (today.year - 1..today.year + 1).asSequence()
            .mapNotNull { lunarYear -> lunarDateInLunarYear(lunarYear, anniversary) }
            .filter { !it.isBefore(today) }
            .minOrNull()

    /** 两个 YearMonth 之间相差的月数（可正可负）。 */
    private fun monthsBetween(from: YearMonth, to: YearMonth): Long =
        (to.year - from.year) * 12L + (to.monthValue - from.monthValue)

    /** 锚点的「日」落在指定月份：超出月长取月末（1/31 → 2 月落 2/28）。 */
    private fun solarInMonth(anchor: LocalDate, month: YearMonth): LocalDate =
        month.atDay(anchor.dayOfMonth.coerceAtMost(month.lengthOfMonth()))

    /** 锚点的月/日落在指定年份：2/29 平年回退 2/28（取当月最后一天）。 */
    private fun solarInYear(anchor: LocalDate, year: Int): LocalDate {
        val day = anchor.dayOfMonth
            .coerceAtMost(YearMonth.of(year, anchor.month).lengthOfMonth())
        return LocalDate.of(year, anchor.month, day)
    }

    /** 农历在指定农历年的日期；小月缺三十回退当月最后一天。 */
    private fun lunarDateInLunarYear(lunarYear: Int, anniversary: Anniversary): LocalDate? =
        lunarToSolar(lunarYear, anniversary.lunarMonth, anniversary.lunarDay)
}
