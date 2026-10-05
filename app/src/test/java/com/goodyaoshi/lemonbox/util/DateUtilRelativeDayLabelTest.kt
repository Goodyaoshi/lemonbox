package com.goodyaoshi.lemonbox.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * relativeDayLabel 的相对词口径：必须以传入基准日为「今天」换算，
 * 提醒文案按触发日为基准才不会让读者数错日子（后天=明天的明天，不是明天的后天）。
 */
class DateUtilRelativeDayLabelTest {

    private val from = LocalDate.of(2026, 10, 5)

    @Test
    fun `基准当天是今天`() {
        assertEquals("今天", DateUtil.relativeDayLabel(from, from))
    }

    @Test
    fun `基准加一天是明天`() {
        assertEquals("明天", DateUtil.relativeDayLabel(from.plusDays(1), from))
    }

    @Test
    fun `后天是基准的加两天`() {
        assertEquals("后天", DateUtil.relativeDayLabel(from.plusDays(2), from))
    }

    @Test
    fun `触发日在明天时 后天的菜要说明天`() {
        // 菜谱排在 10/7（今天视角的后天），提醒 10/6 触发：从触发日看 10/7 是明天。
        val cooking = LocalDate.of(2026, 10, 7)
        val trigger = LocalDate.of(2026, 10, 6)
        assertEquals("明天", DateUtil.relativeDayLabel(cooking, trigger))
    }

    @Test
    fun `触发日当天做菜要说今天`() {
        val cooking = LocalDate.of(2026, 10, 7)
        assertEquals("今天", DateUtil.relativeDayLabel(cooking, cooking))
    }

    @Test
    fun `更远的日期用周几表示`() {
        assertEquals("周一", DateUtil.relativeDayLabel(LocalDate.of(2026, 10, 12), from))
    }
}
