package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 记账分类管理：支出 / 收入两套分类的增删改。 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LedgerCategoryManageViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ledgerRepository: LedgerRepository
) : ViewModel() {

    private val _kind = MutableStateFlow(
        (savedStateHandle.get<Int>("kind") ?: LedgerCategory.KIND_EXPENSE)
            .let { if (it == LedgerCategory.KIND_INCOME) LedgerCategory.KIND_INCOME else LedgerCategory.KIND_EXPENSE }
    )

    /** 当前管理的分类性质（页内可用分段标签切换）。 */
    val kind: StateFlow<Int> = _kind.asStateFlow()

    val categories: StateFlow<List<LedgerCategory>> = _kind.flatMapLatest { kind ->
        if (kind == LedgerCategory.KIND_INCOME) {
            ledgerRepository.observeIncomeCategories()
        } else {
            ledgerRepository.observeExpenseCategories()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setKind(kind: Int) {
        _kind.value = kind
    }

    /** 新增或更新记账分类；名称为空时忽略。 */
    fun saveCategory(id: Long?, name: String, iconKey: String, onDone: () -> Unit) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) return
        viewModelScope.launch {
            val origin = id?.let { ledgerRepository.getCategory(it) }
            ledgerRepository.saveCategory(
                LedgerCategory(
                    id = id ?: 0L,
                    name = normalizedName,
                    icon = iconKey,
                    kind = _kind.value,
                    sort = origin?.sort ?: ((categories.value.maxOfOrNull { it.sort } ?: 0) + 1),
                    isProtected = origin?.isProtected ?: false,
                    syncId = origin?.syncId
                )
            )
            onDone()
        }
    }

    /** 删除分类；内置保护分类返回 false，由界面提示。 */
    fun deleteCategory(id: Long, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(ledgerRepository.deleteCategory(id))
        }
    }
}
