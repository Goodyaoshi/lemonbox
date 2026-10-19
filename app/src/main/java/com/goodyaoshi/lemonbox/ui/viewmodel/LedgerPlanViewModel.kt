package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecurringRule
import com.goodyaoshi.lemonbox.data.local.entity.LedgerTemplate
import com.goodyaoshi.lemonbox.data.repository.LedgerPlanningRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 记账计划（F05）：记账模板与周期记账规则的管理。 */
@HiltViewModel
class LedgerPlanViewModel @Inject constructor(
    private val planningRepository: LedgerPlanningRepository
) : ViewModel() {

    val templates: StateFlow<List<LedgerTemplate>> =
        planningRepository.observeTemplates()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rules: StateFlow<List<LedgerRecurringRule>> =
        planningRepository.observeRules()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteTemplate(id: Long) {
        viewModelScope.launch { planningRepository.deleteTemplate(id) }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch { planningRepository.deleteRule(id) }
    }

    /** 撤销删除：把已软删的模板恢复（清空 deletedAt）。 */
    fun restoreTemplate(id: Long) {
        viewModelScope.launch { planningRepository.restoreTemplate(id) }
    }

    /** 撤销删除：把已软删的周期规则恢复（清空 deletedAt）。 */
    fun restoreRule(id: Long) {
        viewModelScope.launch { planningRepository.restoreRule(id) }
    }

    fun setRuleEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { planningRepository.setRuleEnabled(id, enabled) }
    }

    /** 立即补生成到点账单，回传本次生成的条数。 */
    fun fireNow(onResult: (Int) -> Unit) {
        viewModelScope.launch { onResult(planningRepository.fireDueRules()) }
    }
}
