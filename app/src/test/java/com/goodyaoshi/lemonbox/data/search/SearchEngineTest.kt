package com.goodyaoshi.lemonbox.data.search

import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.local.entity.Location
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchEngineTest {

    private val engine = SearchEngine()

    @Test
    fun filter_returnsUsedUpItemsWhenCriteriaFiltersUsedUp() {
        val items = listOf(
            itemDetail(id = 1L, name = "充电宝", usageStatus = Item.USAGE_USED_UP),
            itemDetail(id = 2L, name = "键盘", usageStatus = Item.USAGE_USED_UP),
            itemDetail(id = 3L, name = "鼠标", usageStatus = Item.USAGE_IN_USE)
        )

        val result = engine.filter(
            items = items,
            locations = emptyList(),
            criteria = SearchCriteria(
                kind = SearchIntentKind.FILTER_ITEMS,
                status = SearchItemStatus.USED_UP
            )
        )

        assertEquals(listOf(2L, 1L), result.map { it.item.id })
    }

    @Test
    fun filter_returnsToBuyItemsWhenCriteriaFiltersToBuy() {
        val items = listOf(
            itemDetail(id = 1L, name = "牛奶", needRestock = true),
            itemDetail(id = 2L, name = "键盘"),
            itemDetail(id = 3L, name = "纸巾", needRestock = true)
        )

        val result = engine.filter(
            items = items,
            locations = emptyList(),
            criteria = SearchCriteria(
                kind = SearchIntentKind.FILTER_ITEMS,
                status = SearchItemStatus.NEED_RESTOCK
            )
        )

        assertEquals(listOf(3L, 1L), result.map { it.item.id })
    }

    @Test
    fun filter_matchesLocationAncestorsWhenBrowsingLocation() {
        val locations = listOf(
            Location(id = 1L, name = "我的家"),
            Location(id = 2L, name = "书房", parentId = 1L),
            Location(id = 3L, name = "台面", parentId = 2L)
        )
        val items = listOf(
            itemDetail(id = 1L, name = "键盘", locationId = 3L, locationName = "台面"),
            itemDetail(id = 2L, name = "牛奶", locationId = null, locationName = "冰箱")
        )

        val result = engine.filter(
            items = items,
            locations = locations,
            criteria = SearchCriteria(
                kind = SearchIntentKind.BROWSE_LOCATION,
                locationName = "书房"
            )
        )

        assertEquals(listOf(1L), result.map { it.item.id })
    }

    @Test
    fun filter_keepsPlainTextSearchForItemName() {
        val items = listOf(
            itemDetail(id = 1L, name = "充电宝"),
            itemDetail(id = 2L, name = "键盘")
        )

        val result = engine.filter(
            items = items,
            locations = emptyList(),
            criteria = SearchCriteria(text = "我的充电在哪")
        )

        assertEquals(listOf(1L), result.map { it.item.id })
    }

    @Test
    fun filter_matchesAnySemanticTerm() {
        val items = listOf(
            itemDetail(id = 1L, name = "键盘"),
            itemDetail(id = 2L, name = "鼠标"),
            itemDetail(id = 3L, name = "充电宝")
        )

        val result = engine.filter(
            items = items,
            locations = emptyList(),
            criteria = SearchCriteria(
                semanticTerms = listOf("键盘", "鼠标")
            )
        )

        assertEquals(listOf(2L, 1L), result.map { it.item.id })
    }

    @Test
    fun filter_doesNotApplySemanticTermsAgainWhenItemIdsAreExecutablePlan() {
        val items = listOf(
            itemDetail(id = 1L, name = "键盘", categoryName = "数码"),
            itemDetail(id = 2L, name = "鼠标", categoryName = "数码"),
            itemDetail(id = 3L, name = "充电宝", categoryName = "数码")
        )

        val result = engine.filter(
            items = items,
            locations = emptyList(),
            criteria = SearchCriteria(
                kind = SearchIntentKind.BROWSE_CATEGORY,
                itemIds = setOf(1L, 2L),
                categoryIds = setOf(9L),
                semanticTerms = listOf("电脑输入设备")
            )
        )

        assertEquals(listOf(2L, 1L), result.map { it.item.id })
    }

    @Test
    fun filter_returnsAllOffHandDispositionsWhenCriteriaFiltersOffHand() {
        val items = listOf(
            itemDetail(id = 1L, name = "键盘", disposition = Item.DISPOSITION_IN_STOCK),
            itemDetail(id = 2L, name = "充电宝", disposition = Item.DISPOSITION_LENT_OUT),
            itemDetail(id = 3L, name = "牛奶", disposition = Item.DISPOSITION_GIVEN_AWAY),
            itemDetail(id = 4L, name = "纸巾", disposition = Item.DISPOSITION_DISCARDED)
        )

        val result = engine.filter(
            items = items,
            locations = emptyList(),
            criteria = SearchCriteria(
                kind = SearchIntentKind.FILTER_ITEMS,
                status = SearchItemStatus.OFF_HAND
            )
        )

        assertEquals(listOf(4L, 3L, 2L), result.map { it.item.id })
    }

    @Test
    fun filter_matchesInStockUnusedCrossDimension() {
        val items = listOf(
            itemDetail(id = 1L, name = "牛奶", usageStatus = Item.USAGE_UNUSED),
            itemDetail(id = 2L, name = "键盘", usageStatus = Item.USAGE_IN_USE),
            itemDetail(
                id = 3L,
                name = "充电宝",
                usageStatus = Item.USAGE_UNUSED,
                disposition = Item.DISPOSITION_GIVEN_AWAY
            ),
            itemDetail(id = 4L, name = "纸巾", usageStatus = Item.USAGE_USED_UP)
        )

        val result = engine.filter(
            items = items,
            locations = emptyList(),
            criteria = SearchCriteria(
                kind = SearchIntentKind.FILTER_ITEMS,
                status = SearchItemStatus.IN_STOCK_UNUSED
            )
        )

        assertEquals(listOf(1L), result.map { it.item.id })
    }

    @Test
    fun filter_matchesInStockInUseCrossDimension() {
        val items = listOf(
            itemDetail(id = 1L, name = "牛奶", usageStatus = Item.USAGE_UNUSED),
            itemDetail(id = 2L, name = "键盘", usageStatus = Item.USAGE_IN_USE),
            itemDetail(
                id = 3L,
                name = "充电宝",
                usageStatus = Item.USAGE_IN_USE,
                disposition = Item.DISPOSITION_LENT_OUT
            )
        )

        val result = engine.filter(
            items = items,
            locations = emptyList(),
            criteria = SearchCriteria(
                kind = SearchIntentKind.FILTER_ITEMS,
                status = SearchItemStatus.IN_STOCK_IN_USE
            )
        )

        assertEquals(listOf(2L), result.map { it.item.id })
    }

    @Test
    fun filter_matchesInStockUsedUpCrossDimension() {
        val items = listOf(
            itemDetail(id = 1L, name = "牛奶", usageStatus = Item.USAGE_USED_UP),
            itemDetail(id = 2L, name = "键盘", usageStatus = Item.USAGE_IN_USE),
            itemDetail(
                id = 3L,
                name = "纸巾",
                usageStatus = Item.USAGE_USED_UP,
                disposition = Item.DISPOSITION_DISCARDED
            )
        )

        val result = engine.filter(
            items = items,
            locations = emptyList(),
            criteria = SearchCriteria(
                kind = SearchIntentKind.FILTER_ITEMS,
                status = SearchItemStatus.IN_STOCK_USED_UP
            )
        )

        assertEquals(listOf(1L), result.map { it.item.id })
    }

    @Test
    fun filter_matchesInStockAvailableExcludesUsedUp() {
        val items = listOf(
            itemDetail(id = 1L, name = "牛奶", usageStatus = Item.USAGE_UNUSED),
            itemDetail(id = 2L, name = "键盘", usageStatus = Item.USAGE_IN_USE),
            itemDetail(id = 3L, name = "纸巾", usageStatus = Item.USAGE_USED_UP),
            itemDetail(
                id = 4L,
                name = "充电宝",
                usageStatus = Item.USAGE_IN_USE,
                disposition = Item.DISPOSITION_LENT_OUT
            )
        )

        val result = engine.filter(
            items = items,
            locations = emptyList(),
            criteria = SearchCriteria(
                kind = SearchIntentKind.FILTER_ITEMS,
                status = SearchItemStatus.IN_STOCK_AVAILABLE
            )
        )

        assertEquals(listOf(2L, 1L), result.map { it.item.id })
    }

    @Test
    fun filter_usesPerItemReminderLadderWindowWhenFilteringExpiring() {
        val now = System.currentTimeMillis()
        val day = 24L * 60L * 60L * 1000L
        val items = listOf(
            // 未单独设置阶梯 → 用全局 7 天窗口，3 天后到期算临期。
            itemDetail(id = 1L, name = "牛奶", expireTime = now + 3 * day),
            // 单独设了 1 天阶梯 → 3 天后到期不算临期。
            itemDetail(id = 2L, name = "酸奶", expireTime = now + 3 * day, reminderDays = "1"),
            // 单独设了 1 天阶梯，1 天后到期算临期。
            itemDetail(id = 3L, name = "面包", expireTime = now + day, reminderDays = "1")
        )

        val result = engine.filter(
            items = items,
            locations = emptyList(),
            criteria = SearchCriteria(
                kind = SearchIntentKind.FILTER_ITEMS,
                expiringWithinDays = 7,
                reminderLadder = listOf(1, 3, 7)
            )
        )

        assertEquals(listOf(3L, 1L), result.map { it.item.id })
    }

    private fun itemDetail(
        id: Long,
        name: String,
        usageStatus: Int = Item.USAGE_IN_USE,
        disposition: Int = Item.DISPOSITION_IN_STOCK,
        needRestock: Boolean = false,
        locationId: Long? = null,
        locationName: String? = null,
        categoryName: String? = null,
        expireTime: Long? = null,
        reminderDays: String? = null
    ): ItemDetail {
        return ItemDetail(
            item = Item(
                id = id,
                name = name,
                usageStatus = usageStatus,
                disposition = disposition,
                needRestock = needRestock,
                locationId = locationId,
                expireTime = expireTime,
                reminderDays = reminderDays,
                createdAt = id
            ),
            categoryName = categoryName,
            locationName = locationName
        )
    }
}
