package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.LedgerMath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

/** 分类明细页的展示模型：固定分类信息 + 当月该分类流水与合计。 */
data class LedgerCategoryDetailUi(
    val categoryName: String = "",
    val categoryIcon: String? = null,
    val totalCents: Long = 0,
    val records: List<LedgerRecordUi> = emptyList()
)

/**
 * 统计分类明细：某账期某分类（categoryId 为 null 表示「未分类」，
 * 与统计页的聚合口径一致）的账单流水。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LedgerCategoryDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    ledgerRepository: LedgerRepository,
    appPreferences: AppPreferences
) : ViewModel() {

    /** 收支性质：支出 / 收入（对应账单 type）。 */
    private val kind: Int =
        savedStateHandle.get<Int>("kind") ?: LedgerCategory.KIND_EXPENSE

    /** 分类 id；-1 哨兵还原为 null（未分类）。 */
    private val categoryId: Long? =
        savedStateHandle.get<Long>("categoryId")?.takeIf { it > 0 }

    private val yearMonth: YearMonth = savedStateHandle.get<String>("monthKey")
        ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        ?: YearMonth.now()

    /** 月份展示：yyyy年M月。 */
    val monthLabel: String = "${yearMonth.year}年${yearMonth.monthValue}月"

    /** 账期窗口与统计页同口径（含自定义月起始日）。 */
    private val recordsFlow = appPreferences.ledgerMonthStartDay.flatMapLatest { startDay ->
        val (start, end) = LedgerMath.monthRange(yearMonth.year, yearMonth.monthValue, startDay)
        ledgerRepository.observeRecordsByCategoryBetween(kind, categoryId, start, end)
    }

    val detail: StateFlow<LedgerCategoryDetailUi> = combine(
        recordsFlow,
        if (kind == LedgerCategory.KIND_INCOME) {
            ledgerRepository.observeIncomeCategories()
        } else {
            ledgerRepository.observeExpenseCategories()
        },
        ledgerRepository.observeAssets()
    ) { records, categories, assets ->
        val category = categoryId?.let { id -> categories.firstOrNull { it.id == id } }
        val assetById = assets.associateBy { it.id }
        val categoryName = category?.name ?: "未分类"
        LedgerCategoryDetailUi(
            categoryName = categoryName,
            categoryIcon = category?.icon,
            totalCents = records.sumOf { it.amount },
            records = records.map { record ->
                LedgerRecordUi(
                    record = record,
                    categoryName = categoryName,
                    categoryIcon = category?.icon,
                    assetName = record.assetId?.let { assetById[it]?.name },
                    targetAssetName = record.targetAssetId?.let { assetById[it]?.name },
                    linkedItemName = null
                )
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerCategoryDetailUi())
}
