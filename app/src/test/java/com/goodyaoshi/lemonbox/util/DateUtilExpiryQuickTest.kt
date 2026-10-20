package com.goodyaoshi.lemonbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 有效期快捷档位的起算日：
 * 生产日期 / 购买日期存在时必须从它们往后算，而不是从今天算。
 */
class DateUtilExpiryQuickTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun millisOf(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun dateOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    @Test
    fun expiryQuickTimestamp_usesProductionDateAsBase() {
        val production = LocalDate.of(2026, 10, 17)
        val expiry = DateUtil.expiryQuickTimestamp("7d", millisOf(production))
        assertEquals(LocalDate.of(2026, 10, 24), dateOf(expiry!!))
    }

    @Test
    fun expiryQuickTimestamp_usesPurchaseDateAsBase() {
        val purchase = LocalDate.of(2026, 1, 31)
        val expiry = DateUtil.expiryQuickTimestamp("1m", millisOf(purchase))
        // LocalDate.plusMonths 会收拢到 2 月最后一天，不能溢出到 3 月。
        assertEquals(LocalDate.of(2026, 2, 28), dateOf(expiry!!))
    }

    @Test
    fun expiryQuickTimestamp_withoutBase_fallsBackToToday() {
        val expiry = DateUtil.expiryQuickTimestamp("3d")
        assertEquals(LocalDate.now(zone).plusDays(3), dateOf(expiry!!))
    }

    @Test
    fun expiryQuickTimestamp_rejectsInvalidCode() {
        assertNull(DateUtil.expiryQuickTimestamp("0d", millisOf(LocalDate.of(2026, 10, 17))))
        assertNull(DateUtil.expiryQuickTimestamp("7x", millisOf(LocalDate.of(2026, 10, 17))))
    }
}
