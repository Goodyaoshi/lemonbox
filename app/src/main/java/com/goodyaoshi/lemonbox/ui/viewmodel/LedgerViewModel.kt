package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.dao.ItemDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerAssetWithBalance
import com.goodyaoshi.lemonbox.data.local.entity.LedgerBudget
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_EXPENSE
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_INCOME
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.LedgerMath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

/** 流水行的展示模型：账单 + 分类/账户/关联物品的名称图标。 */
data class LedgerRecordUi(
    val record: LedgerRecord,
    val categoryName: String?,
    val categoryIcon: String?,
    val assetName: String?,
    val targetAssetName: String?,
    val linkedItemName: String?
)

/** 记账主页的当月总览：流水、收支汇总、预算进度。 */
data class LedgerOverview(
    val records: List<LedgerRecordUi> = emptyList(),
    val expenseCents: Long = 0,
    val incomeCents: Long = 0,
    val totalBudgetCents: Long? = null,
    val categoryBudgets: Map<Long, Long> = emptyMap(),
    val categoryBudgetSpent: Map<Long, Long> = emptyMap()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val ledgerRepository: LedgerRepository,
    private val itemDao: ItemDao,
    private val appPreferences: AppPreferences
) : ViewModel() {

    /** 账期起算日（ST1）：口径外显到流水页，让「这个月」从几号算起一目了然。 */
    val monthStartDay: StateFlow<Int> = appPreferences.ledgerMonthStartDay

    /**
     * 设置账期起算日（1-28）。改完账期窗口即时重算，统计 / 预算 / 趋势等页共用同一份口径。
     * 上限 28 是刻意的：29-31 在二月不存在，任一月都能落到同一天才不会出现「某月账期消失」。
     */
    fun setMonthStartDay(day: Int) = appPreferences.setLedgerMonthStartDay(day)

    private val _yearMonth = MutableStateFlow(YearMonth.now())

    private val loading = LoadingState()

    /** 首帧加载标记：当月总览读完之前显示骨架屏，避免「假空态」（见 [LoadingState]）。 */
    val isLoading: StateFlow<Boolean> = loading.isLoading

    /** 当前查看的月份。 */
    val yearMonth: StateFlow<YearMonth> = _yearMonth.asStateFlow()

    private val range = combine(_yearMonth, monthStartDay) { ym, startDay ->
        LedgerMath.monthRange(ym.year, ym.monthValue, startDay)
    }

    private val recordsFlow = range.flatMapLatest { (start, end) ->
        ledgerRepository.observeRecordsBetween(start, end)
    }

    private val categoriesFlow = combine(
        ledgerRepository.observeExpenseCategories(),
        ledgerRepository.observeIncomeCategories()
    ) { expense, income -> expense + income }

    val assetsWithBalance: StateFlow<List<LedgerAssetWithBalance>> =
        ledgerRepository.observeAssetsWithBalance()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当月总览：流水 UI 模型（补齐名称）+ 收支汇总 + 预算进度。 */
    val overview: StateFlow<LedgerOverview> = combine(
        recordsFlow,
        categoriesFlow,
        ledgerRepository.observeBudgets(),
        ledgerRepository.observeAssets()
    ) { records, categories, budgets, assets ->
        buildOverview(records, categories, budgets, assets.associateBy { it.id })
    }.onEach { loading.markLoaded() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerOverview())

    private suspend fun buildOverview(
        records: List<LedgerRecord>,
        categories: List<LedgerCategory>,
        budgets: List<LedgerBudget>,
        assetById: Map<Long, com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset>
    ): LedgerOverview {
        val categoryById = categories.associateBy { it.id }
        val recordUis = records.map { record ->
            LedgerRecordUi(
                record = record,
                categoryName = record.categoryId?.let { categoryById[it]?.name },
                categoryIcon = record.categoryId?.let { categoryById[it]?.icon },
                assetName = record.assetId?.let { assetById[it]?.name },
                targetAssetName = record.targetAssetId?.let { assetById[it]?.name },
                linkedItemName = record.itemId?.let { itemDao.getItemById(it)?.name }
            )
        }
        val expense = records.filter { it.type == TYPE_EXPENSE }.sumOf { it.amount }
        val income = records.filter { it.type == TYPE_INCOME }.sumOf { it.amount }
        val spentByCategory = records.filter { it.type == TYPE_EXPENSE }
            .mapNotNull { r -> r.categoryId?.let { it to r.amount } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.sum() }
        return LedgerOverview(
            records = recordUis,
            expenseCents = expense,
            incomeCents = income,
            totalBudgetCents = budgets.firstOrNull { it.categoryId == null }?.amount,
            categoryBudgets = budgets
                .mapNotNull { b -> b.categoryId?.let { it to b.amount } }
                .toMap(),
            categoryBudgetSpent = spentByCategory
        )
    }

    fun prevMonth() {
        _yearMonth.value = _yearMonth.value.minusMonths(1)
    }

    fun nextMonth() {
        _yearMonth.value = _yearMonth.value.plusMonths(1)
    }

    fun deleteRecord(id: Long) {
        viewModelScope.launch { ledgerRepository.deleteRecord(id) }
    }

    // ---- 批量管理（B1）：与回收站同一套多选范式 ----

    private val _selectedRecordIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedRecordIds: StateFlow<Set<Long>> = _selectedRecordIds.asStateFlow()

    fun toggleRecordSelection(id: Long) {
        _selectedRecordIds.value = if (id in _selectedRecordIds.value) {
            _selectedRecordIds.value - id
        } else {
            _selectedRecordIds.value + id
        }
    }

    fun selectAllRecords(ids: Set<Long>) {
        _selectedRecordIds.value = ids
    }

    fun clearRecordSelection() {
        _selectedRecordIds.value = emptySet()
    }

    /** 批量删除（软删入回收站），与单条删除走同一通道，之后都能撤销。 */
    fun deleteRecords(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { ledgerRepository.deleteRecord(it) }
            _selectedRecordIds.value = emptySet()
        }
    }

    /** 撤销删除：把刚移入回收站的账单恢复回来。 */
    fun restoreRecord(id: Long) {
        viewModelScope.launch { ledgerRepository.restoreRecord(id) }
    }
}
