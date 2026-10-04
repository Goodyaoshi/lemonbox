package com.goodyaoshi.lemonbox.data.search

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSearchParserTest {

    private val parser = LocalSearchParser()

    @Test
    fun parse_returnsStructuredUsedUpCriteriaForPureStatusQuery() {
        val criteria = parser.parse("已用完的物品")

        assertEquals(SearchIntentKind.FILTER_ITEMS, criteria.kind)
        assertEquals(SearchItemStatus.USED_UP, criteria.status)
        assertEquals("", criteria.text)
    }

    @Test
    fun parse_returnsStructuredExpiringCriteriaForPureStatusQuery() {
        val criteria = parser.parse("快过期的物品")

        assertEquals(SearchIntentKind.FILTER_ITEMS, criteria.kind)
        assertEquals(SearchItemStatus.IN_STOCK, criteria.status)
        assertEquals(7, criteria.expiringWithinDays)
        assertEquals("", criteria.text)
    }

    @Test
    fun parse_combinesLocationScopeWithStatusWhenLocationNameIsKnown() {
        val criteria = parser.parse(
            query = "书房里快过期的东西",
            locationIdsByName = mapOf("书房" to 7L)
        )

        assertEquals(SearchIntentKind.BROWSE_LOCATION, criteria.kind)
        assertEquals(setOf(7L), criteria.locationIds)
        assertEquals(SearchItemStatus.IN_STOCK, criteria.status)
        assertEquals(7, criteria.expiringWithinDays)
        assertEquals("", criteria.text)
    }

    @Test
    fun parse_keepsUnknownLocationQueryAsPlainText() {
        val criteria = parser.parse("书房里有什么东西")

        assertEquals(SearchIntentKind.PLAIN_TEXT, criteria.kind)
        assertEquals("书房", criteria.text)
    }

    @Test
    fun parse_stripsFillerWordsForPlainTextQuery() {
        val criteria = parser.parse("我的充电器在哪")

        assertEquals(SearchIntentKind.PLAIN_TEXT, criteria.kind)
        assertEquals("充电器", criteria.text)
    }

    @Test
    fun parse_matchesCategoryNameWhenCategoryIsKnown() {
        val criteria = parser.parse(
            query = "厨房里的咖啡",
            locationIdsByName = mapOf("厨房" to 3L)
        )

        assertEquals(SearchIntentKind.BROWSE_LOCATION, criteria.kind)
        assertEquals("咖啡", criteria.text)
    }

    @Test
    fun parse_usesLongestMatchedCategoryName() {
        val criteria = parser.parse(
            query = "数码配件",
            categoryIdsByName = mapOf("数码" to 1L, "数码配件" to 2L)
        )

        assertEquals(SearchIntentKind.BROWSE_CATEGORY, criteria.kind)
        assertEquals(setOf(2L), criteria.categoryIds)
    }
}
