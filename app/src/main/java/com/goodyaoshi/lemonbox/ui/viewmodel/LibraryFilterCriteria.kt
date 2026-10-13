package com.goodyaoshi.lemonbox.ui.viewmodel

import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.search.SearchCriteria

/**
 * 把家当页的 [LibraryFilter] 翻译成 [SearchCriteria]。
 *
 * 分类 / 位置都要「含子孙」展开：选了父级也能看到挂在子级下的物品。
 *
 * 家当列表（HouseholdViewModel.results）与物品详情页的翻页范围（DetailViewModel）
 * 共用这一份翻译。两处若各写一套，就会出现「列表里已经按分类筛掉、翻页却能翻出来」
 * 的错位——那正是详情页翻页丢筛选上下文的表现，所以翻译逻辑只留这一个来源。
 */
internal fun LibraryFilter.toSearchCriteria(
    query: String,
    categories: List<Category>,
    locations: List<Location>
): SearchCriteria {
    val categoryIds = categoryId?.let { buildCategoryDescendants(categories)[it].orEmpty() }.orEmpty()
    val locationIds = locationId?.let { buildLocationDescendants(locations)[it].orEmpty() }.orEmpty()
    return SearchCriteria(
        text = query,
        categoryIds = categoryIds,
        locationIds = locationIds,
        usageStatuses = usageStatuses,
        dispositions = dispositions,
        needRestockOnly = needRestockOnly
    )
}

/** 每个节点自身 + 全部后代的 id 集合，用于「选父级也能看到子级内容」的筛选。 */
private fun buildCategoryDescendants(categories: List<Category>): Map<Long, Set<Long>> =
    buildDescendantIds(categories, idOf = { it.id }, parentIdOf = { it.parentId })

private fun buildLocationDescendants(locations: List<Location>): Map<Long, Set<Long>> =
    buildDescendantIds(locations, idOf = { it.id }, parentIdOf = { it.parentId })

private fun <T> buildDescendantIds(
    nodes: List<T>,
    idOf: (T) -> Long,
    parentIdOf: (T) -> Long?
): Map<Long, Set<Long>> {
    val childrenMap = nodes.groupBy { parentIdOf(it) }
    val cache = mutableMapOf<Long, Set<Long>>()

    fun collect(id: Long): Set<Long> = cache.getOrPut(id) {
        val childIds = childrenMap[id].orEmpty().flatMap { collect(idOf(it)) }
        setOf(id) + childIds
    }

    return nodes.associate { idOf(it) to collect(idOf(it)) }
}
