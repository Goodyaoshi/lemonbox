package com.goodyaoshi.lemonbox.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 使用周期统计的纯函数测试：使用天数、平均每天花费、结束判定与开始时间兜底链。
 */
class ItemUsageMathTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun millisOf(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun isUsageEnded_trueForUsedUpGivenAwayDiscarded() {
        assertTrue(Item.isUsageEnded(Item.USAGE_USED_UP, Item.DISPOSITION_IN_STOCK))
        assertTrue(Item.isUsageEnded(Item.USAGE_IN_USE, Item.DISPOSITION_GIVEN_AWAY))
        assertTrue(Item.isUsageEnded(Item.USAGE_IN_USE, Item.DISPOSITION_DISCARDED))
    }

    @Test
    fun isUsageEnded_falseForInUseLentOutUnused() {
        // 借出是暂时的，会还回来，不算使用结束。
        assertFalse(Item.isUsageEnded(Item.USAGE_IN_USE, Item.DISPOSITION_IN_STOCK))
        assertFalse(Item.isUsageEnded(Item.USAGE_IN_USE, Item.DISPOSITION_LENT_OUT))
        assertFalse(Item.isUsageEnded(Item.USAGE_UNUSED, Item.DISPOSITION_IN_STOCK))
    }

    @Test
    fun effectiveStartUseTime_fallsBackToPurchaseDateThenCreatedAt() {
        assertEquals(
            30L,
            Item.effectiveStartUseTime(startUseTime = 30L, purchaseDate = 20L, createdAt = 10L)
        )
        assertEquals(
            20L,
            Item.effectiveStartUseTime(startUseTime = null, purchaseDate = 20L, createdAt = 10L)
        )
        assertEquals(
            10L,
            Item.effectiveStartUseTime(startUseTime = null, purchaseDate = null, createdAt = 10L)
        )
    }

    @Test
    fun usageDays_countsInclusiveFromStartToTodayWhileInUse() {
        val today = LocalDate.now()
        val started = today.minusDays(9)
        val now = millisOf(today)

        val days = Item.usageDays(
            startUseTime = millisOf(started),
            purchaseDate = null,
            createdAt = millisOf(today),
            usageEndedAt = null,
            usageStatus = Item.USAGE_IN_USE,
            disposition = Item.DISPOSITION_IN_STOCK,
            nowMillis = now
        )

        // 含首尾两天：9 天前开始用到今天 = 10 天。
        assertEquals(10, days)
    }

    @Test
    fun usageDays_countsFromStartToEndWhenEnded() {
        val start = LocalDate.now().minusDays(29)
        val end = LocalDate.now()

        val days = Item.usageDays(
            startUseTime = millisOf(start),
            purchaseDate = null,
            createdAt = millisOf(start),
            usageEndedAt = millisOf(end),
            usageStatus = Item.USAGE_USED_UP,
            disposition = Item.DISPOSITION_IN_STOCK,
            nowMillis = millisOf(LocalDate.now().plusDays(5))
        )

        // 已结束的按结束日封口，不随后续时间增长。
        assertEquals(30, days)
    }

    @Test
    fun usageDays_zeroForUnusedItems() {
        val days = Item.usageDays(
            startUseTime = null,
            purchaseDate = null,
            createdAt = 0L,
            usageEndedAt = null,
            usageStatus = Item.USAGE_UNUSED,
            disposition = Item.DISPOSITION_IN_STOCK,
            nowMillis = millisOf(LocalDate.now())
        )

        assertEquals(0, days)
    }

    @Test
    fun usageDays_zeroForEndedItemWithoutEndTimestamp() {
        // 待买占位记录（已用完 + 无结束时间）不参与使用统计。
        val days = Item.usageDays(
            startUseTime = null,
            purchaseDate = null,
            createdAt = 0L,
            usageEndedAt = null,
            usageStatus = Item.USAGE_USED_UP,
            disposition = Item.DISPOSITION_IN_STOCK,
            nowMillis = millisOf(LocalDate.now())
        )

        assertEquals(0, days)
    }

    @Test
    fun usageDays_fallsBackToPurchaseDateWhenStartMissing() {
        val purchased = LocalDate.now().minusDays(4)
        val days = Item.usageDays(
            startUseTime = null,
            purchaseDate = millisOf(purchased),
            createdAt = millisOf(LocalDate.now()),
            usageEndedAt = null,
            usageStatus = Item.USAGE_IN_USE,
            disposition = Item.DISPOSITION_IN_STOCK,
            nowMillis = millisOf(LocalDate.now())
        )

        assertEquals(5, days)
    }

    @Test
    fun averageDailyCost_dividesTotalPriceByDays() {
        assertEquals(0.5, Item.averageDailyCost(price = 15.0, quantity = 1, days = 30)!!, 1e-9)
        // 多件时按总价（单价×数量）均摊。
        assertEquals(0.5, Item.averageDailyCost(price = 5.0, quantity = 3, days = 30)!!, 1e-9)
    }

    @Test
    fun averageDailyCost_nullWithoutPriceOrFullDay() {
        assertNull(Item.averageDailyCost(price = null, quantity = 1, days = 30))
        assertNull(Item.averageDailyCost(price = 15.0, quantity = 1, days = 0))
    }
}
