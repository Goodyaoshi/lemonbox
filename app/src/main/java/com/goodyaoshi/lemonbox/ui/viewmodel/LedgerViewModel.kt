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
    appPreferences: AppPreferences
) : ViewModel() {

    private val monthStartDay = appPreferences.ledgerMonthStartDay

    private val _yearMonth = MutableStateFlow(YearMonth.now())

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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerOverview())

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
}
