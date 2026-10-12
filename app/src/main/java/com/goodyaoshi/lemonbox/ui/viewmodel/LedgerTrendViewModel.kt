package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.dao.LedgerMonthTypeSum
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_EXPENSE
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_INCOME
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.ui.components.LedgerTrendPoint
import com.goodyaoshi.lemonbox.util.LedgerMath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

/** 趋势图窗口包含的月份数：含当月在内的近 N 个月。 */
internal const val TREND_MONTH_COUNT = 6

/** 收支趋势页的数据：每个账期一个点，[hasData] 用于空态判定。 */
data class LedgerTrendUi(
    val points: List<LedgerTrendPoint> = emptyList(),
    val hasData: Boolean = false
)

/**
 * 收支趋势：近 [TREND_MONTH_COUNT] 个月的收入/支出按账期聚合。
 * 聚合在只读 SQL 里按月分桶（口径与记账页/统计页的「记账月开始日」一致），
 * ViewModel 只补齐缺失月份并在客户端对齐口径。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LedgerTrendViewModel @Inject constructor(
    ledgerRepository: LedgerRepository,
    appPreferences: AppPreferences
) : ViewModel() {

    private data class TrendWindow(
        val months: List<YearMonth>,
        val start: Long,
        val end: Long,
        val monthStartDay: Int
    )

    private val windowFlow = appPreferences.ledgerMonthStartDay.map { startDay ->
        val current = YearMonth.now()
        val months = (TREND_MONTH_COUNT - 1 downTo 0).map { current.minusMonths(it.toLong()) }
        val first = months.first()
        val last = months.last()
        TrendWindow(
            months = months,
            start = LedgerMath.monthRange(first.year, first.monthValue, startDay).first,
            end = LedgerMath.monthRange(last.year, last.monthValue, startDay).second,
            monthStartDay = startDay
        )
    }

    val trend: StateFlow<LedgerTrendUi> = windowFlow.flatMapLatest { window ->
        ledgerRepository
            .observeMonthTypeSumsBetween(window.start, window.end, window.monthStartDay)
            .map { sums -> buildTrendUi(window.months, sums) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerTrendUi())
}

/** 把「月 × 类型」聚合结果补齐到 [months] 上（缺失月份记 0），并给出标签。 */
internal fun buildTrendPoints(
    months: List<YearMonth>,
    sums: List<LedgerMonthTypeSum>
): List<LedgerTrendPoint> {
    // monthKey 与 YearMonth.toString() 同为 yyyy-MM，直接对齐。
    val expenseByMonth = sums
        .filter { it.type == TYPE_EXPENSE }
        .associate { it.monthKey to (it.total ?: 0L) }
    val incomeByMonth = sums
        .filter { it.type == TYPE_INCOME }
        .associate { it.monthKey to (it.total ?: 0L) }
    return months.map { ym ->
        val key = ym.toString()
        LedgerTrendPoint(
            label = "${ym.monthValue}月",
            expenseCents = expenseByMonth[key] ?: 0L,
            incomeCents = incomeByMonth[key] ?: 0L
        )
    }
}

internal fun buildTrendUi(
    months: List<YearMonth>,
    sums: List<LedgerMonthTypeSum>
): LedgerTrendUi {
    val points = buildTrendPoints(months, sums)
    return LedgerTrendUi(
        points = points,
        hasData = points.any { it.expenseCents > 0 || it.incomeCents > 0 }
    )
}
