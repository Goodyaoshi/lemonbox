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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

/** 用户自定义筛选预设（SE1）：点击应用、长按删除，随偏好持久化。 */
data class UserFilterPreset(
    val id: Long,
    val label: String,
    val filter: LibraryFilter
)

/** 用户预设的持久化载荷：字段与 [LibraryFilter] 一一对应，分组存枚举名。 */
@Serializable
private data class UserPresetPayload(
    val id: Long,
    val label: String,
    val usageStatuses: Set<Int> = emptySet(),
    val dispositions: Set<Int> = emptySet(),
    val needRestockOnly: Boolean = false,
    val categoryId: Long? = null,
    val locationId: Long? = null,
    val grouping: String = LibraryGrouping.NONE.name
)

/** 按条件自动生成预设名：归属在前、状态在后，没有可说的就叫「自定义筛选」。 */
private fun labelForFilter(
    filter: LibraryFilter,
    categoryNames: Map<Long, String>,
    locationNames: Map<Long, String>
): String {
    val parts = buildList {
        filter.categoryId?.let { id -> categoryNames[id] }?.let { add(it) }
        filter.locationId?.let { id -> locationNames[id] }?.let { add(it) }
        if (filter.needRestockOnly) add("待买")
        when {
            filter.usageStatuses == setOf(Item.USAGE_USED_UP) -> add("已用完")
            Item.USAGE_USED_UP in filter.usageStatuses -> add("含已用完")
            filter.usageStatuses.isNotEmpty() -> add("使用中")
        }
        if (Item.DISPOSITION_IN_STOCK in filter.dispositions) add("在库")
        if (filter.dispositions.containsAnyOf(
                Item.DISPOSITION_LENT_OUT, Item.DISPOSITION_GIVEN_AWAY, Item.DISPOSITION_DISCARDED
            )
        ) {
            add("已离手")
        }
        if (filter.grouping != LibraryGrouping.NONE) add("分组")
    }
    return parts.distinct().joinToString("·").ifBlank { "自定义筛选" }
}

private fun Set<Int>.containsAnyOf(vararg codes: Int): Boolean = codes.any { it in this }

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

/**
 * 家当域的检索与筛选（IA-5）：原 SearchViewModel 随页面一起按承载职责更名，
 * 覆盖物品搜索、筛选面板、预设与回收站等家当 Tab 的数据逻辑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HouseholdViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    categoryRepository: CategoryRepository,
    locationRepository: LocationRepository,
    itemDao: ItemDao,
    private val appPreferences: AppPreferences
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

    /**
     * 每个快捷预设有多少件物品，用于胶囊上的数量。
     * F20：五条计数全部下沉为 SQL COUNT 直算，不再每次物品变动对全量列表 × 5 个预设重算。
     */
    val presetCounts: StateFlow<Map<LibraryPreset, Int>> = combine(
        itemDao.getTotalCount(),
        itemDao.getInStockNotUsedUpCount(),
        itemDao.getUsedUpCount(),
        itemDao.getToBuyCount(),
        itemDao.getOffHandCount()
    ) { total, inStock, usedUp, toBuy, offHand ->
        mapOf(
            LibraryPreset.ALL to total,
            LibraryPreset.IN_STOCK to inStock,
            LibraryPreset.USED_UP to usedUp,
            LibraryPreset.TO_BUY to toBuy,
            LibraryPreset.OFF_HAND to offHand
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

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

    // ---- SE1：用户自定义筛选预设 ----
    private val presetJson = Json { ignoreUnknownKeys = true }

    /** 用户自定义预设：从偏好里的 JSON 串解码而来，按保存顺序展示。 */
    val userFilterPresets: StateFlow<List<UserFilterPreset>> = appPreferences.userFilterPresets
        .map { stored -> stored.mapNotNull(::decodeUserPreset).sortedBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 把当前筛选条件存成用户预设：名字按条件自动生成，不打断输入。 */
    fun saveCurrentFilterAsPreset() {
        val filter = _filter.value
        if (filter == LibraryFilter()) return
        val existing = appPreferences.userFilterPresets.value.mapNotNull(::decodeUserPreset)
        val payload = UserPresetPayload(
            id = (existing.maxOfOrNull { it.id } ?: 0L) + 1L,
            label = labelForFilter(
                filter = filter,
                categoryNames = categories.value.associate { it.id to it.name },
                locationNames = locations.value.associate { it.id to it.name }
            ),
            usageStatuses = filter.usageStatuses,
            dispositions = filter.dispositions,
            needRestockOnly = filter.needRestockOnly,
            categoryId = filter.categoryId,
            locationId = filter.locationId,
            grouping = filter.grouping.name
        )
        appPreferences.setUserFilterPresets(
            appPreferences.userFilterPresets.value + presetJson.encodeToString(payload)
        )
    }

    /** 应用一个用户预设：整体替换状态与归属维度。 */
    fun applyUserPreset(preset: UserFilterPreset) {
        _filter.value = preset.filter
    }

    /** 长按删除用户预设（SE1）。 */
    fun deleteUserPreset(id: Long) {
        appPreferences.setUserFilterPresets(
            appPreferences.userFilterPresets.value.filterNot { raw ->
                runCatching { presetJson.decodeFromString<UserPresetPayload>(raw).id }.getOrNull() == id
            }.toSet()
        )
    }

    /** 撤销删除：把刚删的预设原样存回。 */
    fun restoreUserPreset(preset: UserFilterPreset) {
        appPreferences.setUserFilterPresets(
            appPreferences.userFilterPresets.value + encodeUserPreset(preset)
        )
    }

    private fun decodeUserPreset(raw: String): UserFilterPreset? = runCatching {
        val payload = presetJson.decodeFromString<UserPresetPayload>(raw)
        UserFilterPreset(
            id = payload.id,
            label = payload.label,
            filter = LibraryFilter(
                usageStatuses = payload.usageStatuses,
                dispositions = payload.dispositions,
                needRestockOnly = payload.needRestockOnly,
                categoryId = payload.categoryId,
                locationId = payload.locationId,
                grouping = runCatching { LibraryGrouping.valueOf(payload.grouping) }
                    .getOrDefault(LibraryGrouping.NONE)
            )
        )
    }.getOrNull()

    private fun encodeUserPreset(preset: UserFilterPreset): String = presetJson.encodeToString(
        UserPresetPayload(
            id = preset.id,
            label = preset.label,
            usageStatuses = preset.filter.usageStatuses,
            dispositions = preset.filter.dispositions,
            needRestockOnly = preset.filter.needRestockOnly,
            categoryId = preset.filter.categoryId,
            locationId = preset.filter.locationId,
            grouping = preset.filter.grouping.name
        )
    )

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

    /** 撤销删除：把刚移入回收站的物品恢复回来。 */
    fun restoreFromTrash(id: Long) {
        viewModelScope.launch {
            itemRepository.restoreFromTrash(id)
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