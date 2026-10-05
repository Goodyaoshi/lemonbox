package com.goodyaoshi.lemonbox.util

import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import com.tyme.lunar.LunarDay
import com.tyme.lunar.LunarMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 纪念日的日期纯计算口径：公历/农历下一次发生、周期推进（每 N 天/周/月/年）、
 * 闰日/小月/农历三十的就近回退、累计/倒数的天数正负号、提前提醒阶梯命中。
 * 全部用显式 today 参数，时区无关。
 */
class AnniversaryClockTest {

    private val today = LocalDate.of(2026, 10, 5)

    private fun anniversary(
        date: String,
        repeatUnit: String = Anniversary.REPEAT_NONE,
        repeatInterval: Int = 1,
        isLunar: Boolean = false,
        lunarMonth: Int = 0,
        lunarDay: Int = 0,
        remindDays: String = ""
    ) = Anniversary(
        name = "测试",
        date = date,
        repeatUnit = repeatUnit,
        repeatInterval = repeatInterval,
        isLunar = isLunar,
        lunarMonth = lunarMonth,
        lunarDay = lunarDay,
        remindDays = remindDays
    )

    // ---- parseDate ----

    @Test
    fun `解析合法日期`() {
        assertEquals(LocalDate.of(2024, 9, 17), AnniversaryClock.parseDate("2024-09-17"))
    }

    @Test
    fun `非法日期返回null`() {
        assertNull(AnniversaryClock.parseDate("不是日期"))
        assertNull(AnniversaryClock.parseDate(""))
        assertNull(AnniversaryClock.parseDate("2024-13-01"))
    }

    // ---- 公历每年循环 ----

    @Test
    fun `公历每年循环今年已过取明年`() {
        val a = anniversary("2000-03-05", Anniversary.REPEAT_YEAR)
        assertEquals(LocalDate.of(2027, 3, 5), AnniversaryClock.nextOccurrence(a, today))
        assertEquals(151L, AnniversaryClock.daysUntil(a, today))
    }

    @Test
    fun `公历每年循环当天返回今天且天数为0`() {
        val a = anniversary("2000-10-05", Anniversary.REPEAT_YEAR)
        assertEquals(today, AnniversaryClock.nextOccurrence(a, today))
        assertEquals(0L, AnniversaryClock.daysUntil(a, today))
    }

    @Test
    fun `公历二月二十九平年回退二月二十八`() {
        val a = anniversary("2024-02-29", Anniversary.REPEAT_YEAR)
        // 2026 年的 2/29 回退成 2/28 已过，下一次取 2027-02-28
        assertEquals(LocalDate.of(2027, 2, 28), AnniversaryClock.nextOccurrence(a, today))
        assertEquals(146L, AnniversaryClock.daysUntil(a, today))
    }

    // ---- 每 N 天 / 周 ----

    @Test
    fun `每3天不在步点上时取下一个对齐日`() {
        val a = anniversary("2026-09-28", Anniversary.REPEAT_DAY, 3)
        assertEquals(LocalDate.of(2026, 10, 7), AnniversaryClock.nextOccurrence(a, today))
        assertEquals(2L, AnniversaryClock.daysUntil(a, today))
    }

    @Test
    fun `每3天当天正好落在步点上返回今天`() {
        val a = anniversary("2026-10-05", Anniversary.REPEAT_DAY, 3)
        assertEquals(today, AnniversaryClock.nextOccurrence(a, today))
        assertEquals(0L, AnniversaryClock.daysUntil(a, today))
    }

    @Test
    fun `每2周保持锚点的星期几`() {
        // 2026-09-22 是周二，今天 2026-10-05 是周一 → 下一个对齐日 2026-10-06 也是周二
        val a = anniversary("2026-09-22", Anniversary.REPEAT_WEEK, 2)
        assertEquals(LocalDate.of(2026, 10, 6), AnniversaryClock.nextOccurrence(a, today))
    }

    @Test
    fun `锚点在未来时天周循环直接返回锚点`() {
        val a = anniversary("2026-12-01", Anniversary.REPEAT_DAY, 3)
        assertEquals(LocalDate.of(2026, 12, 1), AnniversaryClock.nextOccurrence(a, today))
    }

    @Test
    fun `间隔为0时兜底按1处理`() {
        val a = anniversary("2026-09-28", Anniversary.REPEAT_DAY, 0)
        assertEquals(today, AnniversaryClock.nextOccurrence(a, today))
    }

    // ---- 每 N 月 ----

    @Test
    fun `每月31号落在小月时回退当月最后一天`() {
        val a = anniversary("2020-01-31", Anniversary.REPEAT_MONTH, 1)
        assertEquals(LocalDate.of(2026, 10, 31), AnniversaryClock.nextOccurrence(a, today))
    }

    @Test
    fun `每月31号当月已过时取下月的月末`() {
        val a = anniversary("2020-01-31", Anniversary.REPEAT_MONTH, 1)
        val later = LocalDate.of(2026, 11, 5)
        assertEquals(LocalDate.of(2026, 11, 30), AnniversaryClock.nextOccurrence(a, later))
    }

    @Test
    fun `每2月跳过不在步点上的月份`() {
        val a = anniversary("2026-01-10", Anniversary.REPEAT_MONTH, 2)
        assertEquals(LocalDate.of(2026, 11, 10), AnniversaryClock.nextOccurrence(a, today))
    }

    @Test
    fun `每2月锚点在未来时返回锚点`() {
        val a = anniversary("2026-12-10", Anniversary.REPEAT_MONTH, 2)
        assertEquals(LocalDate.of(2026, 12, 10), AnniversaryClock.nextOccurrence(a, today))
    }

    // ---- 每 N 年 ----

    @Test
    fun `每2年闰日推进到下一个闰年`() {
        val a = anniversary("2020-02-29", Anniversary.REPEAT_YEAR, 2)
        // 2026/2027 都没有 2/29，下一次是 2028-02-29
        assertEquals(LocalDate.of(2028, 2, 29), AnniversaryClock.nextOccurrence(a, today))
    }

    @Test
    fun `未知周期单位兜底按不循环`() {
        val a = anniversary("2020-01-01", "WAT")
        assertEquals(LocalDate.of(2020, 1, 1), AnniversaryClock.nextOccurrence(a, today))
        assertEquals(-2469L, AnniversaryClock.daysUntil(a, today))
    }

    // ---- 倒数与累计（不循环） ----

    @Test
    fun `不循环未来日期是倒数天数`() {
        val a = anniversary("2027-06-01")
        assertEquals(239L, AnniversaryClock.daysUntil(a, today))
    }

    @Test
    fun `不循环过去日期返回负数累计天数`() {
        val a = anniversary("2020-01-01")
        assertEquals(-2469L, AnniversaryClock.daysUntil(a, today))
    }

    // ---- 农历换算 ----

    @Test
    fun `农历循环换算成公历下一次`() {
        // 2024 农历八月十五 = 2024-09-17（中秋）
        val a = anniversary(
            date = "2024-09-17",
            repeatUnit = Anniversary.REPEAT_YEAR,
            isLunar = true,
            lunarMonth = 8,
            lunarDay = 15
        )
        val today = LocalDate.of(2024, 9, 1)
        assertEquals(LocalDate.of(2024, 9, 17), AnniversaryClock.nextOccurrence(a, today))
        assertEquals(16L, AnniversaryClock.daysUntil(a, today))
    }

    @Test
    fun `农历三十在小月回退当月最后一天`() {
        // 用 tyme 自身的月天数校验回退语义：缺三十时选三十等同选月末那天
        (2020..2026).forEach { year ->
            (1..12).forEach { month ->
                val dayCount = LunarMonth.fromYm(year, month).getDayCount()
                if (dayCount < 30) {
                    val with30 = AnniversaryClock.lunarToSolar(year, month, 30)
                    val lastDay = AnniversaryClock.lunarToSolar(year, month, dayCount)
                    assertEquals(lastDay, with30)
                    // 且确实是农历该月最后一天
                    val expected = LunarDay.fromYmd(year, month, dayCount).getSolarDay()
                    assertEquals(
                        LocalDate.of(expected.year, expected.month, expected.day),
                        with30
                    )
                }
            }
        }
    }

    @Test
    fun `公历换算农历年月日`() {
        // 2024-09-17 是农历甲辰年八月十五（中秋）
        assertEquals(Triple(2024, 8, 15), AnniversaryClock.solarToLunar(LocalDate.of(2024, 9, 17)))
        // 2024-02-09 还是癸卯年除夕（腊月三十），次日正月初一才进甲辰年
        assertEquals(Triple(2023, 12, 30), AnniversaryClock.solarToLunar(LocalDate.of(2024, 2, 9)))
    }

    @Test
    fun `农历公历互相换算往返一致`() {
        (2020..2026).forEach { year ->
            val solar = AnniversaryClock.lunarToSolar(year, 8, 15)
            assertEquals(Triple(year, 8, 15), solar?.let { AnniversaryClock.solarToLunar(it) })
        }
    }

    @Test
    fun `农历标签拼接月日名`() {
        val a = anniversary("2024-09-17", isLunar = true, lunarMonth = 8, lunarDay = 15)
        assertEquals("农历八月十五", AnniversaryClock.lunarLabel(a))
    }

    @Test
    fun `非农历无农历标签`() {
        val a = anniversary("2024-09-17")
        assertNull(AnniversaryClock.lunarLabel(a))
    }

    // ---- 提前提醒阶梯 ----

    @Test
    fun `提醒阶梯命中提前7天`() {
        val a = anniversary("2000-10-12", Anniversary.REPEAT_YEAR, remindDays = "0,7")
        assertTrue(AnniversaryClock.isDueOn(a, today))
    }

    @Test
    fun `提醒阶梯未命中不提醒`() {
        val a = anniversary("2000-10-11", Anniversary.REPEAT_YEAR, remindDays = "0,7")
        assertFalse(AnniversaryClock.isDueOn(a, today))
    }

    @Test
    fun `提醒阶梯为空不提醒`() {
        val a = anniversary("2000-10-12", Anniversary.REPEAT_YEAR)
        assertFalse(AnniversaryClock.isDueOn(a, today))
    }

    @Test
    fun `当天提醒在发生日当天命中`() {
        val a = anniversary("2000-10-05", Anniversary.REPEAT_YEAR, remindDays = "0")
        assertTrue(AnniversaryClock.isDueOn(a, today))
    }

    // ---- remindDays 编解码 ----

    @Test
    fun `编码去重排序`() {
        assertEquals("0,3,7", Anniversary.encodeReminderDays(listOf(3, 0, 3, 7)))
        assertEquals("", Anniversary.encodeReminderDays(emptyList()))
    }

    @Test
    fun `解码忽略非法token与负数`() {
        assertEquals(listOf(0, 3), Anniversary.decodeReminderDays("3,abc,-1,0,3"))
        assertTrue(Anniversary.decodeReminderDays("").isEmpty())
    }

    // ---- 周期归一化与展示名 ----

    @Test
    fun `归一化周期单位大小写与非法值`() {
        assertEquals(Anniversary.REPEAT_YEAR, Anniversary.normalizeRepeatUnit("year"))
        assertEquals(Anniversary.REPEAT_DAY, Anniversary.normalizeRepeatUnit(" DAY "))
        assertEquals(Anniversary.REPEAT_NONE, Anniversary.normalizeRepeatUnit("wat"))
        assertEquals(Anniversary.REPEAT_NONE, Anniversary.normalizeRepeatUnit(null))
    }

    @Test
    fun `周期展示名每天与每N天`() {
        assertEquals("每天", Anniversary.repeatLabel(Anniversary.REPEAT_DAY, 1))
        assertEquals("每3天", Anniversary.repeatLabel(Anniversary.REPEAT_DAY, 3))
        assertEquals("每周", Anniversary.repeatLabel(Anniversary.REPEAT_WEEK, 1))
        assertEquals("每月", Anniversary.repeatLabel(Anniversary.REPEAT_MONTH, 1))
        assertEquals("每2个月", Anniversary.repeatLabel(Anniversary.REPEAT_MONTH, 2))
        assertEquals("每年", Anniversary.repeatLabel(Anniversary.REPEAT_YEAR, 1))
        assertEquals("每2年", Anniversary.repeatLabel(Anniversary.REPEAT_YEAR, 2))
        assertEquals("", Anniversary.repeatLabel(Anniversary.REPEAT_NONE, 1))
        assertEquals("", Anniversary.repeatLabel("wat", 5))
    }

    // ---- 生肖 / 星座 / 年龄（生日辅助口径） ----

    @Test
    fun `生肖按出生那天所属农历年推算`() {
        // 2024-02-10 是农历甲辰年正月初一，属龙；前一天还是癸卯兔年
        assertEquals("龙", AnniversaryClock.zodiacLabel(LocalDate.of(2024, 2, 10)))
        assertEquals("兔", AnniversaryClock.zodiacLabel(LocalDate.of(2024, 2, 9)))
        assertEquals("鼠", AnniversaryClock.zodiacLabel(LocalDate.of(2020, 5, 1)))
    }

    @Test
    fun `星座名带座字后缀`() {
        assertEquals("天秤座", AnniversaryClock.constellationLabel(LocalDate.of(2026, 10, 5)))
        assertEquals("白羊座", AnniversaryClock.constellationLabel(LocalDate.of(2026, 3, 25)))
        assertEquals("摩羯座", AnniversaryClock.constellationLabel(LocalDate.of(2026, 12, 25)))
    }

    @Test
    fun `年龄按公历整年差计算`() {
        assertEquals(26, AnniversaryClock.ageAt(LocalDate.of(2000, 3, 5), LocalDate.of(2026, 10, 5)))
        assertEquals(0, AnniversaryClock.ageAt(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 5)))
        // 生日还没到/在未来：ChronoUnit.YEARS 按月差整除截断，不足一年就是 0
        assertEquals(0, AnniversaryClock.ageAt(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun `农历生日年龄按农历年差计算`() {
        // 用户实例：1998 农历九月二十出生，下次发生 2026/10/29，过完这个生日满 28
        // 公历整年差会把 11 月初的出生日与 10/29 的发生日一比少算一岁，必须走农历年差
        val anchor = AnniversaryClock.lunarToSolar(1998, 9, 20)
        assertEquals(28, AnniversaryClock.ageTurnedAt(anchor!!, LocalDate.of(2026, 10, 29), true))
        // 公历口径保持整年差：生日当天满 26
        assertEquals(26, AnniversaryClock.ageTurnedAt(LocalDate.of(2000, 3, 5), LocalDate.of(2026, 3, 5), false))
    }
}
