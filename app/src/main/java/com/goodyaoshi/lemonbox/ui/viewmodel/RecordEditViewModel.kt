package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.dao.ItemDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerAssetDao
import com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_EXPENSE
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_INCOME
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_TRANSFER
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.util.LedgerMath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** 记一笔的编辑状态。 */
data class RecordEditState(
    val type: Int = TYPE_EXPENSE,
    /** 金额输入文本（元），由数字键盘拼装。 */
    val amountText: String = "",
    val categoryId: Long? = null,
    val assetId: Long? = null,
    val targetAssetId: Long? = null,
    val recordDate: LocalDate = LocalDate.now(),
    val remark: String = "",
    /** 家当联动：关联的物品 id（仅展示与保存，不在本页修改）。 */
    val linkedItemId: Long? = null,
    val linkedItemName: String? = null,
    /** 编辑模式下的原记录 id。 */
    val recordId: Long = 0L,
    /** 首次进入时按类型自动选中首个分类/账户，减少点击。 */
    val initialized: Boolean = false
) {
    val amountCents: Long?
        get() = LedgerMath.parseCentsInput(amountText)

    val canSave: Boolean
        get() = (amountCents ?: 0) > 0 && when (type) {
            TYPE_TRANSFER -> assetId != null && targetAssetId != null && assetId != targetAssetId
            else -> assetId != null
        }
}

@HiltViewModel
class RecordEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ledgerRepository: LedgerRepository,
    private val itemDao: ItemDao,
    assetDao: LedgerAssetDao
) : ViewModel() {

    private val recordIdArg: Long = savedStateHandle.get<Long>("recordId") ?: -1L
    private val itemIdArg: Long = savedStateHandle.get<Long>("itemId") ?: -1L
    private val amountArg: Long = savedStateHandle.get<Long>("amount") ?: -1L
    private val remarkArg: String = savedStateHandle.get<String>("remark").orEmpty()

    private val _state = MutableStateFlow(RecordEditState())
    val state: StateFlow<RecordEditState> = _state.asStateFlow()

    val assets: StateFlow<List<LedgerAsset>> = assetDao.observeAssets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val expenseCategories: StateFlow<List<LedgerCategory>> =
        ledgerRepository.observeExpenseCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val incomeCategories: StateFlow<List<LedgerCategory>> =
        ledgerRepository.observeIncomeCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (recordIdArg > 0) {
            viewModelScope.launch { loadRecord(recordIdArg) }
        } else {
            _state.value = RecordEditState(
                amountText = amountArg.takeIf { it > 0 }?.let { LedgerMath.centsToInputText(it) } ?: "",
                remark = remarkArg,
                linkedItemId = itemIdArg.takeIf { it > 0 },
                linkedItemName = remarkArg.takeIf { itemIdArg > 0 }
            )
        }
    }

    private suspend fun loadRecord(id: Long) {
        val record = ledgerRepository.getRecord(id) ?: return
        val zone = ZoneId.systemDefault()
        _state.value = RecordEditState(
            type = record.type,
            amountText = LedgerMath.centsToInputText(record.amount),
            categoryId = record.categoryId,
            assetId = record.assetId,
            targetAssetId = record.targetAssetId,
            recordDate = Instant.ofEpochMilli(record.recordTime).atZone(zone).toLocalDate(),
            remark = record.remark,
            linkedItemId = record.itemId,
            linkedItemName = record.itemId?.let { itemDao.getItemById(it)?.name },
            recordId = record.id,
            initialized = true
        )
    }

    fun updateType(type: Int) {
        _state.value = _state.value.copy(
            type = type,
            categoryId = _state.value.categoryId.takeIf { type != TYPE_TRANSFER },
            initialized = false
        )
    }

    /** 类型或可用分类变化后，未手动选过时自动带默认分类/账户，减少点击。 */
    fun ensureDefaults(expense: List<LedgerCategory>, income: List<LedgerCategory>) {
        val current = _state.value
        if (current.initialized) return
        if (current.type == TYPE_TRANSFER) {
            _state.value = current.copy(
                assetId = current.assetId ?: assets.value.firstOrNull()?.id,
                targetAssetId = current.targetAssetId ?: assets.value.getOrNull(1)?.id,
                initialized = true
            )
            return
        }
        if (current.categoryId != null && current.assetId != null) {
            _state.value = current.copy(initialized = true)
            return
        }
        val defaultCategory = (if (current.type == TYPE_INCOME) income else expense)
            .firstOrNull()?.id
        _state.value = current.copy(
            categoryId = current.categoryId ?: defaultCategory,
            assetId = current.assetId ?: assets.value.firstOrNull()?.id,
            targetAssetId = current.targetAssetId ?: assets.value.getOrNull(1)?.id,
            initialized = true
        )
    }

    fun onAmountKey(key: String) {
        val current = _state.value.amountText
        val next = when (key) {
            "." -> if (current.contains('.')) current else {
                if (current.isEmpty()) "0." else "$current."
            }

            "⌫" -> current.dropLast(1)
            else -> {
                if (current == "0") key else {
                    val candidate = current + key
                    val fenPart = candidate.substringAfter('.', "")
                    if (fenPart.length > 2 || candidate.length > 11) current else candidate
                }
            }
        }
        _state.value = _state.value.copy(amountText = next)
    }

    fun selectCategory(id: Long?) {
        _state.value = _state.value.copy(categoryId = id)
    }

    fun selectAsset(id: Long) {
        _state.value = _state.value.copy(assetId = id)
    }

    fun selectTargetAsset(id: Long) {
        _state.value = _state.value.copy(targetAssetId = id)
    }

    fun selectDate(date: LocalDate) {
        _state.value = _state.value.copy(recordDate = date)
    }

    fun updateRemark(text: String) {
        _state.value = _state.value.copy(remark = text)
    }

    /** 保存账单；成功返回 true（由界面收尾导航）。 */
    fun save(onDone: () -> Unit): Boolean {
        val current = _state.value
        val cents = current.amountCents ?: return false
        if (!current.canSave) return false
        val zone = ZoneId.systemDefault()
        val recordTime = current.recordDate
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
            .let { startOfDay ->
                if (current.recordDate == LocalDate.now(zone)) {
                    System.currentTimeMillis().coerceAtLeast(startOfDay)
                } else {
                    startOfDay + 12 * 60 * 60 * 1000L
                }
            }
        val record = LedgerRecord(
            id = current.recordId,
            type = current.type,
            amount = cents,
            categoryId = if (current.type == TYPE_TRANSFER) null else current.categoryId,
            assetId = current.assetId,
            targetAssetId = if (current.type == TYPE_TRANSFER) current.targetAssetId else null,
            recordTime = recordTime,
            remark = current.remark.trim(),
            itemId = current.linkedItemId,
            createdAt = if (current.recordId == 0L) System.currentTimeMillis() else 0L
        )
        viewModelScope.launch {
            if (current.recordId == 0L) {
                ledgerRepository.saveRecord(record.copy(createdAt = System.currentTimeMillis()))
            } else {
                // 编辑：保留原 createdAt，避免抹掉。
                val origin = ledgerRepository.getRecord(current.recordId)
                ledgerRepository.saveRecord(record.copy(createdAt = origin?.createdAt ?: record.recordTime))
            }
            onDone()
        }
        return true
    }
}
