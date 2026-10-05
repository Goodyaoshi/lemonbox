package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.LedgerBudget
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_EXPENSE
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.LedgerMath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

/** 预算页的单条预算展示：预算金额与本月已花。 */
data class LedgerBudgetItemUi(
    /** null 表示月度总预算。 */
    val categoryId: Long?,
    val categoryName: String?,
    val categoryIcon: String?,
    val budgetCents: Long,
    val spentCents: Long
) {
    val ratio: Float
        get() = if (budgetCents <= 0) 0f else spentCents.toFloat() / budgetCents

    val over: Boolean
        get() = budgetCents > 0 && spentCents > budgetCents
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LedgerBudgetViewModel @Inject constructor(
    private val ledgerRepository: LedgerRepository,
    appPreferences: AppPreferences
) : ViewModel() {

    /** 预算按「当前账期」展示，跟随月起始日设置。 */
    private val range = appPreferences.ledgerMonthStartDay.map { startDay ->
        val now = YearMonth.now()
        LedgerMath.monthRange(now.year, now.monthValue, startDay)
    }

    private val recordsFlow = range.flatMapLatest { (start, end) ->
        ledgerRepository.observeRecordsBetween(start, end)
    }

    private val expenseCategories: StateFlow<List<LedgerCategory>> =
        ledgerRepository.observeExpenseCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 全部预算（含总预算），总预算排最前。 */
    val budgetItems: StateFlow<List<LedgerBudgetItemUi>> = combine(
        ledgerRepository.observeBudgets(),
        recordsFlow,
        expenseCategories
    ) { budgets, records, categories ->
        val spentByCategory = records.filter { it.type == TYPE_EXPENSE }
            .groupBy({ it.categoryId }, { it.amount })
            .mapValues { (_, amounts) -> amounts.sum() }
        val categoryById = categories.associateBy { it.id }
        budgets.sortedWith(compareBy { it.categoryId })
            .map { budget ->
                LedgerBudgetItemUi(
                    categoryId = budget.categoryId,
                    categoryName = budget.categoryId?.let { categoryById[it]?.name ?: "已删分类" },
                    categoryIcon = budget.categoryId?.let { categoryById[it]?.icon },
                    budgetCents = budget.amount,
                    spentCents = spentByCategory[budget.categoryId] ?: 0L
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 尚未设置预算的支出分类，供「添加分类预算」选择。 */
    val categoriesWithoutBudget: StateFlow<List<LedgerCategory>> = combine(
        ledgerRepository.observeBudgets(),
        expenseCategories
    ) { budgets, categories ->
        val budgetIds = budgets.mapNotNull { it.categoryId }.toSet()
        categories.filter { it.id !in budgetIds }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 保存预算：金额解析失败或 <= 0 视为取消该预算。
     * [categoryId] 为 null 表示总预算。
     */
    fun setBudget(categoryId: Long?, yuanText: String) {
        val cents = LedgerMath.parseCentsInput(yuanText) ?: 0L
        viewModelScope.launch {
            ledgerRepository.setBudget(categoryId, cents)
        }
    }
}
