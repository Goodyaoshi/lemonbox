package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.dao.ItemDao
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LocationRepository
import com.goodyaoshi.lemonbox.data.search.SearchCriteria
import com.goodyaoshi.lemonbox.data.search.SearchEngine
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 结果的分组方式，作为筛选面板里的一个维度。 */
enum class LibraryGrouping(val label: String) {
    NONE("不分组"),
    CATEGORY("按分类"),
    LOCATION("按位置")
}

/**
 * 家当的统一筛选条件：状态维度（进度 / 去向 / 待买）与归属维度（分类 / 位置）自由叠加，
 * 空集合表示该维度不限制。这样就不必再用「视图切换 + 组内二级筛选」的多层入口。
 */
data class LibraryFilter(
    val usageStatuses: Set<Int> = emptySet(),
    val dispositions: Set<Int> = emptySet(),
    val needRestockOnly: Boolean = false,
    val categoryId: Long? = null,
    val locationId: Long? = null,
    val grouping: LibraryGrouping = LibraryGrouping.NONE
) {
    /** 面板入口角标：只统计「当前预设解释不了」的额外条件，避免和预设胶囊重复计数。 */
    val activeConditionCount: Int
        get() {
            var count = 0
            if (LibraryPreset.of(this) == null) {
                if (usageStatuses.isNotEmpty()) count++
                if (dispositions.isNotEmpty()) count++
                if (needRestockOnly) count++
            }
            if (categoryId != null) count++
            if (locationId != null) count++
            if (grouping != LibraryGrouping.NONE) count++
            return count
        }
}

/**
 * 状态维度的快捷预设。「全部 / 在库 / 已用完 / 待买 / 已离手」本质只是给
 * [LibraryFilter] 的状态维度赋值，仍然可以再叠加分类、位置等条件。
 */
enum class LibraryPreset(val label: String) {
    ALL("全部"),
    IN_STOCK("在库"),
    USED_UP("已用完"),
    TO_BUY("待买"),
    OFF_HAND("已离手");

    /** 把预设写进筛选条件；分类、位置、分组等归属维度保持不变。 */
    fun applyTo(filter: LibraryFilter): LibraryFilter = when (this) {
        ALL -> filter.copy(
            usageStatuses = emptySet(),
            dispositions = emptySet(),
            needRestockOnly = false
        )

        IN_STOCK -> filter.copy(
            usageStatuses = setOf(Item.USAGE_UNUSED, Item.USAGE_IN_USE),
            dispositions = setOf(Item.DISPOSITION_IN_STOCK),
            needRestockOnly = false
        )

        USED_UP -> filter.copy(
            usageStatuses = setOf(Item.USAGE_USED_UP),
            dispositions = setOf(Item.DISPOSITION_IN_STOCK),
            needRestockOnly = false
        )

        TO_BUY -> filter.copy(
            usageStatuses = emptySet(),
            dispositions = emptySet(),
            needRestockOnly = true
        )

        OFF_HAND -> filter.copy(
            usageStatuses = emptySet(),
            dispositions = setOf(
                Item.DISPOSITION_LENT_OUT,
                Item.DISPOSITION_GIVEN_AWAY,
                Item.DISPOSITION_DISCARDED
            ),
            needRestockOnly = false
        )
    }

    /** 该预设是否正被当前条件命中（自定义组合不命中任何预设时返回 null）。 */
    fun matches(filter: LibraryFilter): Boolean = when (this) {
        ALL -> !filter.needRestockOnly &&
            filter.usageStatuses.isEmpty() &&
            filter.dispositions.isEmpty()

        IN_STOCK -> !filter.needRestockOnly &&
            filter.usageStatuses == IN_STOCK_USAGE &&
            filter.dispositions == IN_STOCK_DISPOSITIONS

        USED_UP -> !filter.needRestockOnly &&
            filter.usageStatuses == setOf(Item.USAGE_USED_UP) &&
            filter.dispositions == IN_STOCK_DISPOSITIONS

        TO_BUY -> filter.needRestockOnly &&
            filter.usageStatuses.isEmpty() &&
            filter.dispositions.isEmpty()

        OFF_HAND -> !filter.needRestockOnly &&
            filter.usageStatuses.isEmpty() &&
            filter.dispositions == OFF_HAND_DISPOSITIONS
    }

    companion object {
        private val IN_STOCK_USAGE = setOf(Item.USAGE_UNUSED, Item.USAGE_IN_USE)
        private val IN_STOCK_DISPOSITIONS = setOf(Item.DISPOSITION_IN_STOCK)
        private val OFF_HAND_DISPOSITIONS = setOf(
            Item.DISPOSITION_LENT_OUT,
            Item.DISPOSITION_GIVEN_AWAY,
            Item.DISPOSITION_DISCARDED
        )

        fun of(filter: LibraryFilter): LibraryPreset? = entries.firstOrNull { it.matches(filter) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    categoryRepository: CategoryRepository,
    locationRepository: LocationRepository,
    itemDao: ItemDao,
    appPreferences: AppPreferences
) : ViewModel() {

    private val searchEngine = SearchEngine()

    /** 回收站口径：与原先「我的」页一致，30 天内可恢复。 */
    private val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000

    /**
     * 家当域的域入口角标：待买 / 临期 / 回收站。
     * 计数随「家当域」一起从 ProfileViewModel 迁到这里，保证入口与数据同源（原则 3）。
     */
    val toBuyCount: StateFlow<Int> = itemDao
        .getToBuyCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 临期数量：阈值与「到期提醒」口径一致（取设置里的提醒天数）。 */
    val expiringCount: StateFlow<Int> = appPreferences.reminderDays
        .flatMapLatest { days ->
            itemDao.getExpiringCount(
                System.currentTimeMillis() + days * 24L * 60 * 60 * 1000
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val trashCount: StateFlow<Int> = itemDao
        .getRecycleCount(System.currentTimeMillis() - thirtyDaysMs)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 进家当先看「在库」——默认视角只关心家里还有什么，全部物品交给筛选面板。 */
    private val _filter = MutableStateFlow(LibraryPreset.IN_STOCK.applyTo(LibraryFilter()))
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    val allItems: StateFlow<List<ItemDetail>> = itemRepository.getAllItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<Category>> = categoryRepository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locations: StateFlow<List<Location>> = locationRepository.getAllLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 每个快捷预设有多少件物品，用于胶囊上的数量。 */
    val presetCounts: StateFlow<Map<LibraryPreset, Int>> = allItems
        .map { items ->
            LibraryPreset.entries.associateWith { preset ->
                items.count { matchesPreset(it, preset) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val results: StateFlow<List<ItemDetail>> = combine(
        allItems,
        _query,
        _filter,
        categories,
        locations
    ) { items, query, filter, categories, locations ->
        searchEngine.filter(items, locations, filter.toSearchCriteria(query, categories, locations))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateQuery(query: String) {
        _query.value = query
    }

    fun selectPreset(preset: LibraryPreset) {
        _filter.value = preset.applyTo(_filter.value)
    }

    fun toggleUsageStatus(status: Int) {
        _filter.value = _filter.value.let { filter ->
            filter.copy(usageStatuses = filter.usageStatuses.toggle(status))
        }
    }

    fun clearUsageStatuses() {
        _filter.value = _filter.value.copy(usageStatuses = emptySet())
    }

    fun toggleDisposition(disposition: Int) {
        _filter.value = _filter.value.let { filter ->
            filter.copy(dispositions = filter.dispositions.toggle(disposition))
        }
    }

    fun clearDispositions() {
        _filter.value = _filter.value.copy(dispositions = emptySet())
    }

    fun setNeedRestockOnly(enabled: Boolean) {
        _filter.value = _filter.value.copy(needRestockOnly = enabled)
    }

    fun selectCategory(categoryId: Long?) {
        _filter.value = _filter.value.copy(categoryId = categoryId)
    }

    fun selectLocation(locationId: Long?) {
        _filter.value = _filter.value.copy(locationId = locationId)
    }

    fun setGrouping(grouping: LibraryGrouping) {
        _filter.value = _filter.value.copy(grouping = grouping)
    }

    fun resetFilter() {
        // 重置回到默认视角（在库），而不是「全部」。
        _filter.value = LibraryPreset.IN_STOCK.applyTo(LibraryFilter())
    }

    fun moveToTrash(item: Item) {
        viewModelScope.launch {
            itemRepository.moveToTrash(item)
        }
    }

    fun markAsUsed(id: Long) {
        viewModelScope.launch {
            itemRepository.markAsUsed(id)
        }
    }

    /** 「用1件」：数量减 1，减到 0 自动记为已用完。 */
    fun consumeOne(id: Long) {
        viewModelScope.launch {
            itemRepository.consumeOne(id)
        }
    }

    fun setUsageStatus(id: Long, usageStatus: Int) {
        viewModelScope.launch {
            itemRepository.setUsageStatus(id, usageStatus)
        }
    }

    fun setDisposition(id: Long, disposition: Int) {
        viewModelScope.launch {
            itemRepository.setDisposition(id, disposition)
        }
    }

    fun setNeedRestock(id: Long, needRestock: Boolean) {
        viewModelScope.launch {
            itemRepository.setNeedRestock(id, needRestock)
        }
    }

    private fun matchesPreset(itemDetail: ItemDetail, preset: LibraryPreset): Boolean {
        val item = itemDetail.item
        return when (preset) {
            LibraryPreset.ALL -> true
            LibraryPreset.IN_STOCK ->
                item.disposition == Item.DISPOSITION_IN_STOCK && item.usageStatus != Item.USAGE_USED_UP

            LibraryPreset.USED_UP ->
                item.disposition == Item.DISPOSITION_IN_STOCK && item.usageStatus == Item.USAGE_USED_UP

            LibraryPreset.TO_BUY ->
                item.needRestock && item.disposition != Item.DISPOSITION_DISCARDED

            LibraryPreset.OFF_HAND -> item.disposition != Item.DISPOSITION_IN_STOCK
        }
    }

    private fun LibraryFilter.toSearchCriteria(
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
}

private fun Set<Int>.toggle(value: Int): Set<Int> =
    if (value in this) this - value else this + value

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