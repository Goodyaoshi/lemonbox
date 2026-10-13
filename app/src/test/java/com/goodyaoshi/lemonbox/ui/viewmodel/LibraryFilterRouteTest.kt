package com.goodyaoshi.lemonbox.ui.viewmodel

import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.search.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 家当筛选条件经导航参数往返后不丢语义（第 5 条）。
 *
 * 背景：详情页的左右翻页范围原先只能由少数几个 scope 常量表达，分类 / 位置 / 关键词
 * 这类组合条件在跳转时被丢弃，翻页会翻出当前筛选之外的物品。现在整份 [LibraryFilter]
 * 随路由带上，本测试锁住两件事：
 *   1. 编码 / 解码往返不丢字段，非法输入安全降级为 null（调用方据此回退旧路径）；
 *   2. 用往返后的条件过滤，得到的集合与家当列表逐条一致。
 */
class LibraryFilterRouteTest {

    // ---------- 编码 / 解码往返 ----------

    @Test
    fun roundTrip_preservesStateAndOwnershipFields() {
        val filter = LibraryFilter(
            usageStatuses = setOf(Item.USAGE_UNUSED, Item.USAGE_IN_USE),
            dispositions = setOf(Item.DISPOSITION_IN_STOCK),
            needRestockOnly = true,
            categoryId = 7L,
            locationId = 3L
        )

        val decoded = decodeLibraryFilter(encodeLibraryFilter(filter))

        assertEquals(filter, decoded)
    }

    @Test
    fun roundTrip_preservesBlankFilter() {
        val decoded = decodeLibraryFilter(encodeLibraryFilter(LibraryFilter()))

        assertEquals(LibraryFilter(), decoded)
    }

    @Test
    fun roundTrip_dropsGroupingOnPurpose() {
        // grouping 只影响家当列表怎么分组展示，不影响「翻页范围」这个集合，
        // 因此刻意不参与传递：往返后回到默认 NONE，这是设计意图而非缺陷。
        val filter = LibraryFilter(
            usageStatuses = setOf(Item.USAGE_UNUSED),
            grouping = LibraryGrouping.CATEGORY
        )

        val decoded = decodeLibraryFilter(encodeLibraryFilter(filter))

        assertEquals(
            LibraryFilter(usageStatuses = setOf(Item.USAGE_UNUSED)),
            decoded
        )
    }

    @Test
    fun decode_returnsNullForMissingOrMalformedInput() {
        // 非家当入口不带该参数、或参数损坏时都必须回退到按 scope 过滤的老路径，
        // 而不是让详情页打不开。
        assertNull(decodeLibraryFilter(null))
        assertNull(decodeLibraryFilter(""))
        assertNull(decodeLibraryFilter("   "))
        assertNull(decodeLibraryFilter("not-json"))
        assertNull(decodeLibraryFilter("{") )
    }

    // ---------- 分类 / 位置的「含子孙」展开 ----------

    @Test
    fun criteria_expandsCategoryToItsDescendants() {
        val categories = listOf(
            Category(id = 1, name = "食品"),
            Category(id = 2, name = "零食", parentId = 1),
            Category(id = 3, name = "饮料", parentId = 1),
            Category(id = 4, name = "工具")
        )

        val criteria = LibraryFilter(categoryId = 1)
            .toSearchCriteria(query = "", categories = categories, locations = emptyList())

        assertEquals(setOf(1L, 2L, 3L), criteria.categoryIds)
    }

    @Test
    fun criteria_expandsLocationToItsDescendants() {
        val locations = listOf(
            Location(id = 10, name = "厨房"),
            Location(id = 11, name = "冰箱", parentId = 10)
        )

        val criteria = LibraryFilter(locationId = 10)
            .toSearchCriteria(query = "", categories = emptyList(), locations = locations)

        assertEquals(setOf(10L, 11L), criteria.locationIds)
    }

    // ---------- 详情页翻页范围 == 家当列表 ----------

    @Test
    fun detailPagerScope_matchesHouseholdList_afterRouteRoundTrip() {
        val categories = listOf(
            Category(id = 1, name = "食品"),
            Category(id = 2, name = "零食", parentId = 1)
        )
        val locations = emptyList<Location>()
        val items = listOf(
            detail(id = 10, name = "薯片", categoryId = 2, createdAt = 100L),
            detail(id = 11, name = "牛奶", categoryId = 1, createdAt = 200L),
            detail(id = 12, name = "螺丝刀", categoryId = 9, createdAt = 300L)
        )
        // 选了父级「食品」：子级「零食」下的薯片也必须算进来。
        val filter = LibraryFilter(categoryId = 1)
        val engine = SearchEngine()

        // 家当列表用的调用（HouseholdViewModel.results 同款）。
        val householdIds = engine
            .filter(items, locations, filter.toSearchCriteria("", categories, locations))
            .map { it.item.id }

        // 详情页用的调用：条件先经路由编码再解码。
        val routed = requireNotNull(decodeLibraryFilter(encodeLibraryFilter(filter)))
        val detailIds = engine
            .filter(items, locations, routed.toSearchCriteria("", categories, locations))
            .map { it.item.id }

        assertEquals(setOf(10L, 11L), householdIds.toSet())
        // 逐条且同序：详情页翻页看到的就是家当列表那一组。
        assertEquals(householdIds, detailIds)
    }

    @Test
    fun detailPagerScope_matchesHouseholdList_forStateAndQueryCombination() {
        val items = listOf(
            detail(id = 20, name = "洗衣液", usageStatus = Item.USAGE_UNUSED, createdAt = 100L),
            detail(id = 21, name = "洗衣粉", usageStatus = Item.USAGE_IN_USE, createdAt = 200L),
            detail(id = 22, name = "酱油", usageStatus = Item.USAGE_UNUSED, createdAt = 300L)
        )
        val filter = LibraryFilter(usageStatuses = setOf(Item.USAGE_UNUSED))
        val engine = SearchEngine()

        val householdIds = engine
            .filter(items, emptyList(), filter.toSearchCriteria("洗衣", emptyList(), emptyList()))
            .map { it.item.id }
        val routed = requireNotNull(decodeLibraryFilter(encodeLibraryFilter(filter)))
        val detailIds = engine
            .filter(items, emptyList(), routed.toSearchCriteria("洗衣", emptyList(), emptyList()))
            .map { it.item.id }

        // 状态维度与关键词同时生效：只留下「未使用」且名字含「洗衣」的那件。
        assertEquals(listOf(20L), householdIds)
        assertEquals(householdIds, detailIds)
    }

    /** 状态维度不含分类 / 位置时，两者都不施加限制。 */
    @Test
    fun criteria_withoutOwnershipFilter_leavesOwnershipUnrestricted() {
        val criteria = LibraryFilter(usageStatuses = setOf(Item.USAGE_UNUSED))
            .toSearchCriteria("", emptyList(), emptyList())

        assertEquals(emptySet<Long>(), criteria.categoryIds)
        assertEquals(emptySet<Long>(), criteria.locationIds)
    }

    private fun detail(
        id: Long,
        name: String,
        categoryId: Long? = null,
        usageStatus: Int = Item.USAGE_IN_USE,
        createdAt: Long = 0L
    ): ItemDetail = ItemDetail(
        item = Item(
            id = id,
            name = name,
            categoryId = categoryId,
            usageStatus = usageStatus,
            createdAt = createdAt
        )
    )
}
