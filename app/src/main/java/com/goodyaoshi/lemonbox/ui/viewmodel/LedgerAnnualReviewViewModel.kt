package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.dao.LedgerCategorySum
import com.goodyaoshi.lemonbox.data.local.dao.LedgerMonthTypeSum
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_EXPENSE
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.ui.components.LedgerTrendPoint
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
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** 年度回顾的账期窗口：本年与上一年各自的 [start, end)，口径与记账页一致。 */
internal data class AnnualWindow(
    val year: Int,
    val thisStart: Long,
    val thisEnd: Long,
    val prevStart: Long,
    val prevEnd: Long,
    val startDay: Int
)

/**
 * 年度回顾页的数据。
 * [prevYearExpenseCents] 为 null 表示上一年没有任何收支（不做无意义的同比）；
 * [expenseDeltaRatio] 只在去年支出 > 0 时给出，否则为 null。
 */
data class LedgerAnnualReviewUi(
    val year: Int = 0,
    val hasData: Boolean = false,
    val expenseCents: Long = 0,
    val incomeCents: Long = 0,
    val balanceCents: Long = 0,
    val recordCount: Int = 0,
    val dailyAverageCents: Long = 0,
    val topMonthLabel: String? = null,
    val topMonthExpenseCents: Long = 0,
    val topCategoryName: String? = null,
    val topCategoryIcon: String? = null,
    val topCategoryCents: Long = 0,
    val prevYearExpenseCents: Long? = null,
    val expenseDeltaRatio: Float? = null,
    val monthlyPoints: List<LedgerTrendPoint> = emptyList(),
    val canGoNext: Boolean = false
)

/**
 * 年度回顾：把某一年（默认当年）的收支与笔数汇总成一张「这一年过得怎么样」的答卷。
 * 只读聚合按月分桶（口径与记账页/统计页的「记账月开始日」一致），
 * ViewModel 补齐月份、算最高项与同比，页面只负责排版。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LedgerAnnualReviewViewModel @Inject constructor(
    ledgerRepository: LedgerRepository,
    appPreferences: AppPreferences
) : ViewModel() {

    private val currentYear = YearMonth.now().year

    private val _year = MutableStateFlow(currentYear)

    /** 当前查看的年份。 */
    val year: StateFlow<Int> = _year.asStateFlow()

    private val yearWindow = combine(_year, appPreferences.ledgerMonthStartDay) { year, startDay ->
        AnnualWindow(
            year = year,
            thisStart = LedgerMath.monthRange(year, 1, startDay).first,
            thisEnd = LedgerMath.monthRange(year, 12, startDay).second,
            prevStart = LedgerMath.monthRange(year - 1, 1, startDay).first,
            prevEnd = LedgerMath.monthRange(year - 1, 12, startDay).second,
            startDay = startDay
        )
    }

    val review: StateFlow<LedgerAnnualReviewUi> = yearWindow.flatMapLatest { window ->
        combine(
            ledgerRepository.observeMonthTypeSumsBetween(window.thisStart, window.thisEnd, window.startDay),
            ledgerRepository.observeMonthTypeSumsBetween(window.prevStart, window.prevEnd, window.startDay),
            ledgerRepository.observeRecordCountBetween(window.thisStart, window.thisEnd),
            ledgerRepository.observeExpenseByCategoryBetween(window.thisStart, window.thisEnd),
            ledgerRepository.observeExpenseCategories()
        ) { thisSums, prevSums, count, categorySums, categories ->
            buildAnnualReview(window, thisSums, prevSums, count, categorySums, categories)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LedgerAnnualReviewUi(year = currentYear)
    )

    fun prevYear() {
        _year.value = _year.value - 1
    }

    /** 只允许往回看，不允许翻到未来年份。 */
    fun nextYear() {
        if (_year.value < currentYear) _year.value = _year.value + 1
    }
}

/**
 * 汇总年度回顾。纯函数便于单测；[today] 可注入以固定「日均」与「能否向后翻」的口径。
 * 日均支出 = 全年支出 ÷ 已过天数（当年按今天，往年按整年），除零保护。
 */
internal fun buildAnnualReview(
    window: AnnualWindow,
    thisSums: List<LedgerMonthTypeSum>,
    prevSums: List<LedgerMonthTypeSum>,
    recordCount: Int,
    categorySums: List<LedgerCategorySum>,
    categories: List<LedgerCategory>,
    today: LocalDate = LocalDate.now()
): LedgerAnnualReviewUi {
    val months = (1..12).map { YearMonth.of(window.year, it) }
    val points = buildTrendPoints(months, thisSums)
    val expenseCents = points.sumOf { it.expenseCents }
    val incomeCents = points.sumOf { it.incomeCents }

    val topMonth = points.maxByOrNull { it.expenseCents }?.takeIf { it.expenseCents > 0 }

    val categoryById = categories.associateBy { it.id }
    val topCategory = categorySums.firstOrNull { (it.total ?: 0L) > 0 }
    val topCategoryName = topCategory?.let { sum ->
        sum.categoryId?.let { id -> categoryById[id]?.name } ?: "未分类"
    }
    val topCategoryIcon = topCategory?.let { sum ->
        sum.categoryId?.let { id -> categoryById[id]?.icon }
    }

    val prevExpense = prevSums.filter { it.type == TYPE_EXPENSE }.sumOf { it.total ?: 0L }
    val prevHasData = prevSums.any { (it.total ?: 0L) > 0 }
    val deltaRatio = if (prevExpense > 0) {
        (expenseCents - prevExpense).toFloat() / prevExpense
    } else {
        null
    }

    val elapsedDays = if (window.year == today.year) {
        today.dayOfYear
    } else {
        LocalDate.of(window.year, 12, 31).dayOfYear
    }
    val dailyAverage = if (expenseCents > 0 && elapsedDays > 0) expenseCents / elapsedDays else 0L

    return LedgerAnnualReviewUi(
        year = window.year,
        hasData = recordCount > 0,
        expenseCents = expenseCents,
        incomeCents = incomeCents,
        balanceCents = incomeCents - expenseCents,
        recordCount = recordCount,
        dailyAverageCents = dailyAverage,
        topMonthLabel = topMonth?.label,
        topMonthExpenseCents = topMonth?.expenseCents ?: 0L,
        topCategoryName = topCategoryName,
        topCategoryIcon = topCategoryIcon,
        topCategoryCents = topCategory?.total ?: 0L,
        prevYearExpenseCents = prevExpense.takeIf { prevHasData },
        expenseDeltaRatio = deltaRatio,
        monthlyPoints = points,
        canGoNext = window.year < today.year
    )
}
