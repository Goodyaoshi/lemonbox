package com.goodyaoshi.lemonbox.data.search

enum class SearchIntentKind {
    PLAIN_TEXT,
    FIND_ITEM,
    FILTER_ITEMS,
    BROWSE_LOCATION,
    BROWSE_CATEGORY
}

/**
 * 搜索用的状态筛选条件，按「使用进度 / 物品去向 / 补货标记」三个正交维度展开。
 */
enum class SearchItemStatus {
    ANY,

    /** 在库（物品去向 = 在库），用于临期筛选等场景。 */
    IN_STOCK,
    UNUSED,
    USED_UP,

    /** 待买：勾选了「需要补货」且尚未丢弃。 */
    NEED_RESTOCK,
    LENT_OUT,
    GIVEN_AWAY,
    DISCARDED,

    /** 已离手：物品去向不是「在库」（已借出 / 已送人 / 已丢弃）。 */
    OFF_HAND,

    /** 在库 + 未使用（维度交叉，供家当分组筛选使用）。 */
    IN_STOCK_UNUSED,

    /** 在库 + 使用中（维度交叉，供家当分组筛选使用）。 */
    IN_STOCK_IN_USE,

    /** 在库 + 已用完（维度交叉，供家当分组筛选使用）。 */
    IN_STOCK_USED_UP,

    /**
     * 在库且还没用完（未使用 / 使用中），家当「在库」分组的全部。
     * 用完的物品不再算作手上的库存，归入独立的「已用完」分组。
     */
    IN_STOCK_AVAILABLE
}

enum class SearchSort {
    CREATED_DESC,
    EXPIRE_ASC,
    NAME_ASC
}

data class SearchCriteria(
    val kind: SearchIntentKind = SearchIntentKind.PLAIN_TEXT,
    val text: String = "",
    val semanticTerms: List<String> = emptyList(),
    val itemIds: Set<Long> = emptySet(),
    val itemName: String? = null,
    val categoryIds: Set<Long> = emptySet(),
    val categoryName: String? = null,
    val locationIds: Set<Long> = emptySet(),
    val locationName: String? = null,
    val status: SearchItemStatus = SearchItemStatus.ANY,
    /**
     * 可组合维度：与 [status] 并行生效。空集合表示该维度不限制，
     * 多个维度之间是「与」的关系，便于家当自由叠加筛选条件。
     */
    val usageStatuses: Set<Int> = emptySet(),
    val dispositions: Set<Int> = emptySet(),
    /** 只看勾了「需要补货」且尚未丢弃的物品。 */
    val needRestockOnly: Boolean = false,
    val expiringWithinDays: Int? = null,
    /**
     * 全局默认提醒阶梯。临期筛选会优先用每件物品自己的阶梯窗口判定，
     * 未单独设置阶梯的物品再回退到 [expiringWithinDays]。
     */
    val reminderLadder: List<Int> = emptyList(),
    val sort: SearchSort = SearchSort.CREATED_DESC,
    val summary: String? = null
) {
    fun hasStructuredFilters(): Boolean {
        return itemIds.isNotEmpty() ||
            itemName != null ||
            categoryIds.isNotEmpty() ||
            categoryName != null ||
            locationIds.isNotEmpty() ||
            locationName != null ||
            semanticTerms.isNotEmpty() ||
            status != SearchItemStatus.ANY ||
            usageStatuses.isNotEmpty() ||
            dispositions.isNotEmpty() ||
            needRestockOnly ||
            expiringWithinDays != null
    }
}
