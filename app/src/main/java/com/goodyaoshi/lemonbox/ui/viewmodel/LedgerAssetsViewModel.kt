package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.dao.LedgerAssetWithBalance
import com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 账户性质分组：0 普通账户计入资产，1 信用卡计入负债。 */
const val LEDGER_ASSET_TYPE_NORMAL = 0
const val LEDGER_ASSET_TYPE_CREDIT = 1

/** 资产负债汇总（分）：净资产 = 总资产 - 总负债。 */
data class LedgerAssetSummary(
    val totalAssetCents: Long = 0,
    val totalLiabilityCents: Long = 0,
    val netAssetCents: Long = 0
)

/** 资产负债登记页：账户余额列表 + 汇总 + 增删改。 */
@HiltViewModel
class LedgerAssetsViewModel @Inject constructor(
    private val ledgerRepository: LedgerRepository
) : ViewModel() {

    /** 全部有效账户（过滤软删墓碑），余额口径与记账页的账户胶囊同源。 */
    val assetsWithBalance: StateFlow<List<LedgerAssetWithBalance>> =
        ledgerRepository.observeAssetsWithBalance()
            .map { assets -> assets.filter { it.deletedAt == null } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 汇总：信用卡余额（欠款为负）计入负债。 */
    val summary: StateFlow<LedgerAssetSummary> = assetsWithBalance.map { assets ->
        val assetCents = assets
            .filter { it.type == LEDGER_ASSET_TYPE_NORMAL }
            .sumOf { it.balance }
        val liabilityCents = -assets
            .filter { it.type == LEDGER_ASSET_TYPE_CREDIT }
            .sumOf { it.balance }
        LedgerAssetSummary(
            totalAssetCents = assetCents,
            totalLiabilityCents = liabilityCents,
            netAssetCents = assetCents - liabilityCents
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerAssetSummary())

    /** 新增或更新账户；名称为空时忽略。 */
    fun saveAsset(
        id: Long?,
        name: String,
        iconKey: String,
        initialBalanceCents: Long,
        type: Int,
        onDone: () -> Unit
    ) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) return
        viewModelScope.launch {
            val origin = id?.let { ledgerRepository.getAsset(it) }
            ledgerRepository.saveAsset(
                LedgerAsset(
                    id = id ?: 0L,
                    name = normalizedName,
                    icon = iconKey,
                    sort = origin?.sort
                        ?: ((assetsWithBalance.value.maxOfOrNull { it.sort } ?: 0) + 1),
                    initialBalance = initialBalanceCents,
                    type = type,
                    syncId = origin?.syncId
                )
            )
            onDone()
        }
    }

    /** 删除账户；仍有账单引用时返回 false，由界面提示。 */
    fun deleteAsset(id: Long, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(ledgerRepository.deleteAsset(id))
        }
    }
}
