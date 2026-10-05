package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import java.time.YearMonth
import javax.inject.Inject

/** 统计页的单个分类切片：金额、占比与展示信息。 */
data class LedgerStatSlice(
    val categoryId: Long?,
    val name: String,
    val icon: String?,
    val amountCents: Long,
    val ratio: Float
)

/** 记账统计页的当月数据：支出/收入各一套切片。 */
data class LedgerStatsUi(
    val expenseSlices: List<LedgerStatSlice> = emptyList(),
    val incomeSlices: List<LedgerStatSlice> = emptyList(),
    val expenseCents: Long = 0,
    val incomeCents: Long = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LedgerStatsViewModel @Inject constructor(
    ledgerRepository: LedgerRepository,
    appPreferences: AppPreferences
) : ViewModel() {

    private val _yearMonth = MutableStateFlow(YearMonth.now())

    /** 当前查看的月份。 */
    val yearMonth: StateFlow<YearMonth> = _yearMonth.asStateFlow()

    private val range = combine(_yearMonth, appPreferences.ledgerMonthStartDay) { ym, startDay ->
        LedgerMath.monthRange(ym.year, ym.monthValue, startDay)
    }

    private val recordsFlow = range.flatMapLatest { (start, end) ->
        ledgerRepository.observeRecordsBetween(start, end)
    }

    /** 用当月流水客户端聚合，增删账单后统计即时刷新。 */
    val stats: StateFlow<LedgerStatsUi> = combine(
        recordsFlow,
        ledgerRepository.observeExpenseCategories(),
        ledgerRepository.observeIncomeCategories()
    ) { records, expenseCategories, incomeCategories ->
        val expenses = records.filter { it.type == TYPE_EXPENSE }
        val incomes = records.filter { it.type == TYPE_INCOME }
        LedgerStatsUi(
            expenseSlices = buildSlices(expenses, expenseCategories),
            incomeSlices = buildSlices(incomes, incomeCategories),
            expenseCents = expenses.sumOf { it.amount },
            incomeCents = incomes.sumOf { it.amount }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerStatsUi())

    fun prevMonth() {
        _yearMonth.value = _yearMonth.value.minusMonths(1)
    }

    fun nextMonth() {
        _yearMonth.value = _yearMonth.value.plusMonths(1)
    }

    /** 按分类聚合金额（含「未分类」），金额降序并计算占比。 */
    private fun buildSlices(
        records: List<LedgerRecord>,
        categories: List<LedgerCategory>
    ): List<LedgerStatSlice> {
        if (records.isEmpty()) return emptyList()
        val categoryById = categories.associateBy { it.id }
        val totals = records
            .groupBy { it.categoryId }
            .mapKeys { (categoryId, _) -> categoryId }
            .mapValues { (_, group) -> group.sumOf { it.amount } }
        val total = totals.values.sum().coerceAtLeast(1L)
        return totals
            .entries
            .sortedByDescending { it.value }
            .map { (categoryId, amount) ->
                LedgerStatSlice(
                    categoryId = categoryId,
                    name = categoryId?.let { categoryById[it]?.name } ?: "未分类",
                    icon = categoryId?.let { categoryById[it]?.icon },
                    amountCents = amount,
                    ratio = amount.toFloat() / total
                )
            }
    }
}
