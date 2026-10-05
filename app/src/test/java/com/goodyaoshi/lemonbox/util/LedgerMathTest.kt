package com.goodyaoshi.lemonbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class LedgerMathTest {

    // ---------- 分 ↔ 元换算 ----------

    @Test
    fun yuanToCents_roundsHalfUp() {
        assertEquals(1999L, LedgerMath.yuanToCents(19.99))
        assertEquals(3000L, LedgerMath.yuanToCents(29.999999))
        assertEquals(1L, LedgerMath.yuanToCents(0.005))
        assertEquals(0L, LedgerMath.yuanToCents(0.0))
        assertEquals(123L, LedgerMath.yuanToCents(1.234))
    }

    @Test
    fun centsToYuan_convertsExactly() {
        assertEquals(12.34, LedgerMath.centsToYuan(1234), 1e-9)
        assertEquals(0.01, LedgerMath.centsToYuan(1), 1e-9)
    }

    @Test
    fun itemPriceToCents_multipliesQuantity() {
        assertEquals(4998L, LedgerMath.itemPriceToCents(24.99, 2))
        assertEquals(2500L, LedgerMath.itemPriceToCents(25.0, 1))
    }

    // ---------- 输入文本解析与回显 ----------

    @Test
    fun parseCentsInput_parsesValidTexts() {
        assertEquals(1200L, LedgerMath.parseCentsInput("12"))
        assertEquals(1250L, LedgerMath.parseCentsInput("12.5"))
        assertEquals(1205L, LedgerMath.parseCentsInput("12.05"))
        assertEquals(5L, LedgerMath.parseCentsInput(".05"))
        assertEquals(1200L, LedgerMath.parseCentsInput(" 12 "))
    }

    @Test
    fun parseCentsInput_rejectsInvalidTexts() {
        assertNull(LedgerMath.parseCentsInput(""))
        assertNull(LedgerMath.parseCentsInput("."))
        assertNull(LedgerMath.parseCentsInput("12.3.4"))
        assertNull(LedgerMath.parseCentsInput("1.234"))
        assertNull(LedgerMath.parseCentsInput("abc"))
        assertNull(LedgerMath.parseCentsInput("12a"))
    }

    @Test
    fun centsToInputText_trimsTrailingZeros() {
        assertEquals("12.5", LedgerMath.centsToInputText(1250))
        assertEquals("12", LedgerMath.centsToInputText(1200))
        assertEquals("12.05", LedgerMath.centsToInputText(1205))
        assertEquals("", LedgerMath.centsToInputText(0))
    }

    @Test
    fun parseAndFormat_roundTrip() {
        listOf(1L, 5L, 1200L, 1250L, 1205L, 999999L).forEach { cents ->
            assertEquals(cents, LedgerMath.parseCentsInput(LedgerMath.centsToInputText(cents)))
        }
    }

    // ---------- 账期窗口 ----------

    @Test
    fun monthRange_coversNaturalMonth() {
        val zone = ZoneId.of("Asia/Shanghai")
        val (start, end) = LedgerMath.monthRange(2026, 10, zone = zone)
        assertEquals("2026-10-01", Instant.ofEpochMilli(start).atZone(zone).toLocalDate().toString())
        assertEquals("2026-11-01", Instant.ofEpochMilli(end).atZone(zone).toLocalDate().toString())
    }

    @Test
    fun monthRange_supportsCustomStartDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val (start, end) = LedgerMath.monthRange(2026, 10, startDay = 7, zone = zone)
        assertEquals("2026-10-07", Instant.ofEpochMilli(start).atZone(zone).toLocalDate().toString())
        assertEquals("2026-11-07", Instant.ofEpochMilli(end).atZone(zone).toLocalDate().toString())
    }

    @Test
    fun monthRange_clampsInvalidStartDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val (start, _) = LedgerMath.monthRange(2026, 2, startDay = 31, zone = zone)
        assertEquals("2026-02-28", Instant.ofEpochMilli(start).atZone(zone).toLocalDate().toString())
    }

    // ---------- 预算进度 ----------

    @Test
    fun budgetRatio_handlesEdgeCases() {
        assertNull(LedgerMath.budgetRatio(100, null))
        assertNull(LedgerMath.budgetRatio(100, 0))
        assertEquals(0.5f, LedgerMath.budgetRatio(500, 1000)!!)
        assertEquals(1.2f, LedgerMath.budgetRatio(1200, 1000)!!)
    }
}
