package com.goodyaoshi.lemonbox.ui.viewmodel

import android.util.Log
import com.goodyaoshi.lemonbox.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.dao.CategoryDao
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.repository.AnniversaryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.data.repository.LocationRepository
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import com.goodyaoshi.lemonbox.data.repository.WeekMenuRepository
import com.goodyaoshi.lemonbox.data.repository.WeeklyMealDay
import com.goodyaoshi.lemonbox.data.search.LocalSearchParser
import com.goodyaoshi.lemonbox.data.search.SearchCriteria
import com.goodyaoshi.lemonbox.data.search.SearchEngine
import com.goodyaoshi.lemonbox.data.search.SearchIntentKind
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** 首页「本月账」速览条的数据：当月支出与总预算（分，未设总预算时为 null）。 */
data class MonthSpending(
    val spentCents: Long,
    val budgetCents: Long?
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val categoryDao: CategoryDao,
    private val locationRepository: LocationRepository,
    private val reminderRepository: ReminderRepository,
    private val anniversaryRepository: AnniversaryRepository,
    private val ledgerRepository: LedgerRepository,
    private val weekMenuRepository: WeekMenuRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    val activeItems: StateFlow<List<ItemDetail>> = itemRepository.getActiveItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allItems: StateFlow<List<ItemDetail>> = itemRepository.getAllItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 临期物品：在库、有有效期，且剩余天数落在它自己（或全局）提醒阶梯的最大窗口内。 */
    val expiringItems: StateFlow<List<ItemDetail>> =
        combine(activeItems, appPreferences.reminderLadder) { items, ladder ->
            items.filter { isExpiringSoon(it.item, ladder) }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 逐件判定是否临期：用物品自己的阶梯窗口（未设置则跟随全局）。 */
    private fun isExpiringSoon(item: Item, globalLadder: List<Int>): Boolean {
        val expireTime = item.expireTime ?: return false
        if (expireTime <= 0L) return false
        val window = Item.reminderWindowDays(item.reminderDays, globalLadder)
        return DateUtil.daysUntil(expireTime) <= window
    }

    /** 未来 N 天菜单（今天吃什么卡要用今天那天），生成与扣料逻辑都在 WeekMenuRepository。 */
    val weekPlan: StateFlow<List<WeeklyMealDay>> = weekMenuRepository.weekPlan
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 首页「纪念日」速览：最近的 1-2 条（未到的按剩余天数升序，累计的在一起越久越靠前）。 */
    val anniversaryHighlights: StateFlow<List<AnniversaryRow>> =
        anniversaryRepository.getActiveAnniversaries().map { list ->
            val today = LocalDate.now()
            list.map { it.toRow(today) }
                .filter { it.days != null }
                .sortedWith(
                    compareBy(
                        { (it.days ?: 0L) < 0 },
                        { if ((it.days ?: 0L) < 0) -(it.days ?: 0L) else it.days ?: 0L }
                    )
                )
                .take(2)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 一餐做完：仓库内幂等（已做不重复扣料）。 */
    fun markDayCooked(dateKey: String) {
        viewModelScope.launch { weekMenuRepository.markDayCooked(dateKey) }
    }

    /** 今天（含已提醒还没完成）的提醒待办。 */
    val todayReminders: StateFlow<List<Reminder>> = reminderRepository.getActiveReminders()
        .map { list ->
            val endOfToday = LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            list.filter { it.nextFireAt < endOfToday }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 待买清单：今天要去补/要买的东西。 */
    val toBuyItems: StateFlow<List<ItemDetail>> = itemRepository.getToBuyItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 本月账速览：开关关闭时为 null；未设总预算时只显示已花。 */
    val monthSpending: StateFlow<MonthSpending?> = combine(
        ledgerRepository.observeBudgets(),
        appPreferences.ledgerMonthStartDay,
        appPreferences.budgetReminderEnabled
    ) { budgets, startDay, enabled ->
        if (!enabled) {
            null
        } else {
            val now = LocalDate.now()
            val (start, end) = LedgerMath.monthRange(now.year, now.monthValue, startDay)
            val total = budgets.firstOrNull { it.categoryId == null }?.amount?.takeIf { it > 0 }
            Triple(total, start, end)
        }
    }.flatMapLatest { target ->
        if (target == null) {
            flowOf(null)
        } else {
            val (total, start, end) = target
            ledgerRepository.observeRecordsBetween(start, end).map { records ->
                val spent = records
                    .filter { it.type == LedgerRecord.TYPE_EXPENSE }
                    .sumOf { it.amount }
                MonthSpending(spentCents = spent, budgetCents = total)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 「今天的事」：提醒待办 + 临期家当 + 待买，提醒在前（要紧），临期次之，待买在后。 */
    val todayEvents: StateFlow<List<TodayEvent>> = combine(
        todayReminders,
        expiringItems,
        toBuyItems
    ) { reminders, expiring, toBuy ->
        buildList {
            reminders.forEach { reminder ->
                add(
                    TodayEvent(
                        kind = TodayEventKind.TASK,
                        key = "reminder:${reminder.id}",
                        title = reminder.title,
                        detail = reminder.fireTime.takeIf { it.isNotBlank() },
                        target = TodayEventTarget.Reminders,
                        completable = true,
                        reminderId = reminder.id
                    )
                )
            }
            expiring.forEach { detail ->
                add(
                    TodayEvent(
                        kind = TodayEventKind.EXPIRY,
                        key = "expiry:${detail.item.id}",
                        title = detail.item.name,
                        detail = expiryDetail(detail.item.expireTime),
                        target = TodayEventTarget.ItemDetail(detail.item.id)
                    )
                )
            }
            toBuy.forEach { detail ->
                add(
                    TodayEvent(
                        kind = TodayEventKind.TO_BUY,
                        key = "tobuy:${detail.item.id}",
                        title = detail.item.name,
                        detail = "待买 ${detail.item.quantity}${detail.item.unit}",
                        target = TodayEventTarget.ToBuy
                    )
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 在今日清单里勾掉一条提醒待办（一次性记完成，周期提醒推进下一轮）。 */
    fun completeTodayEvent(event: TodayEvent) {
        val id = event.reminderId ?: return
        viewModelScope.launch {
            todayReminders.value.firstOrNull { it.id == id }?.let { reminderRepository.complete(it) }
        }
    }

    /** 临期行的副文案：按剩余天数给出紧迫程度。 */
    private fun expiryDetail(expireTime: Long?): String? {
        val time = expireTime ?: return null
        if (time <= 0L) return null
        val days = DateUtil.daysUntil(time)
        return when {
            days < 0 -> "已过期 ${-days} 天"
            days == 0L -> "今天到期"
            else -> "还有 $days 天到期"
        }
    }

    val categories: StateFlow<List<Category>> = itemRepository.getActiveItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        .let { activeItemsFlow ->
            combine(
                activeItemsFlow,
                categoryDao.getAllCategories()
            ) { items, categories ->
                val activeCategoryIds = items.mapNotNull { it.item.categoryId }.toSet()
                categories.filter { it.id in activeCategoryIds }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locations: StateFlow<List<Location>> = locationRepository.getAllLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val searchHistory: StateFlow<List<String>> = appPreferences.searchHistory

    private val searchEngine = SearchEngine()
    private val localSearchParser = LocalSearchParser()

    private val _searchCriteria = MutableStateFlow<SearchCriteria?>(null)
    val searchCriteria: StateFlow<SearchCriteria?> = _searchCriteria.asStateFlow()

    private val _searchMessage = MutableStateFlow<String?>(null)
    val searchMessage: StateFlow<String?> = _searchMessage.asStateFlow()

    val searchResults: StateFlow<List<ItemDetail>> = combine(
        allItems,
        categories,
        locations,
        _searchQuery,
        _searchCriteria
    ) { items, categories, locations, query, criteria ->
        buildSearchResults(
            query = query,
            items = items,
            categories = categories,
            locations = locations,
            criteria = criteria
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _searchCriteria.value = null
        _searchMessage.value = null
    }

    fun rememberSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        appPreferences.setSearchHistory(
            listOf(normalized) +
                appPreferences.searchHistory.value
                    .filterNot { it.equals(normalized, ignoreCase = true) }
                    .take(MAX_SEARCH_HISTORY - 1)
        )
    }

    fun clearSearchHistory() {
        appPreferences.setSearchHistory(emptyList())
    }

    fun runSmartSearch() {
        val query = _searchQuery.value.trim()
        if (query.isBlank()) return

        rememberSearch(query)
        val criteria = parseCriteria(query, categories.value, locations.value)
        _searchCriteria.value = criteria
        _searchMessage.value = criteria.summary ?: DEFAULT_SEARCH_MESSAGE
    }

    private fun parseCriteria(
        query: String,
        categories: List<Category>,
        locations: List<Location>
    ): SearchCriteria {
        return localSearchParser.parse(
            query = query,
            categoryIdsByName = categories.associate { it.name to it.id },
            locationIdsByName = locations.associate { it.name to it.id },
            expiringWithinDays = appPreferences.reminderDays.value
        ).copy(reminderLadder = appPreferences.reminderLadder.value)
    }

    private fun buildSearchResults(
        query: String,
        items: List<ItemDetail>,
        categories: List<Category>,
        locations: List<Location>,
        criteria: SearchCriteria?
    ): List<ItemDetail> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()
        val resolvedCriteria = criteria ?: parseCriteria(normalizedQuery, categories, locations)
        val results = searchEngine.filter(items, locations, resolvedCriteria)
        val finalResults = if (results.isEmpty() && resolvedCriteria.canFallbackToPlainText()) {
            searchEngine.filter(items, locations, SearchCriteria(text = normalizedQuery))
        } else {
            results
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                SEARCH_UI_LOG_TAG,
                "query=$normalizedQuery items=${items.size} criteria=$resolvedCriteria results=${finalResults.size}"
            )
        }
        return finalResults
    }

    private fun SearchCriteria.canFallbackToPlainText(): Boolean {
        return kind == SearchIntentKind.PLAIN_TEXT
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

    private companion object {
        const val SEARCH_UI_LOG_TAG = "SearchUi"
        const val MAX_SEARCH_HISTORY = 8
        const val DEFAULT_SEARCH_MESSAGE = "已在本地按名称、分类、位置和状态完成搜索"
    }
}
