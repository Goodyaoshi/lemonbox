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
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
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
    val initialized: Boolean = false,
    /** 保存成功标记，供界面做收尾（连续录入则清空留在本页）。 */
    val isSaved: Boolean = false
) {
    val amountCents: Long?
        get() = LedgerMath.parseCentsInput(amountText)

    /** 金额输入里是否含运算符（用于展示算式结果预览）。 */
    val isExpression: Boolean
        get() = amountText.any { it in OPERATOR_CHARS }

    val canSave: Boolean
        get() = (amountCents ?: 0) > 0 && when (type) {
            TYPE_TRANSFER -> assetId != null && targetAssetId != null && assetId != targetAssetId
            else -> assetId != null
        }

    companion object {
        val OPERATOR_CHARS = setOf('+', '-', '×', '÷')
    }
}

@HiltViewModel
class RecordEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ledgerRepository: LedgerRepository,
    private val itemDao: ItemDao,
    assetDao: LedgerAssetDao,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val recordIdArg: Long = savedStateHandle.get<Long>("recordId") ?: -1L
    private val itemIdArg: Long = savedStateHandle.get<Long>("itemId") ?: -1L
    private val amountArg: Long = savedStateHandle.get<Long>("amount") ?: -1L
    private val remarkArg: String = savedStateHandle.get<String>("remark").orEmpty()

    private val _state = MutableStateFlow(RecordEditState())
    val state: StateFlow<RecordEditState> = _state.asStateFlow()

    /** 连续录入开关，与家当录入共用同一份偏好设置。 */
    val continuousEntry: StateFlow<Boolean> = appPreferences.continuousEntry

    fun toggleContinuousEntry(enabled: Boolean) = appPreferences.setContinuousEntry(enabled)

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

    /**
     * 金额键盘输入。支持数字、小数点、四则运算符（+ - × ÷）与退格，
     * 输入内容允许是一个算式（如 `20×3-5`），保存时按 [LedgerMath.parseCentsInput] 求值。
     */
    fun onAmountKey(key: String) {
        val current = _state.value.amountText
        val next = when {
            key == "⌫" -> current.dropLast(1)
            key == "." -> appendDecimal(current)
            key.length == 1 && key[0] in RecordEditState.OPERATOR_CHARS -> appendOperator(current, key)
            key.length == 1 && key[0] in '0'..'9' -> appendDigit(current, key)
            else -> current
        }
        _state.value = _state.value.copy(amountText = next)
    }

    /** 当前正在输入的操作数（最后一个运算符之后的部分）。 */
    private fun currentOperand(text: String): String =
        text.substring(text.indexOfLast { it in RecordEditState.OPERATOR_CHARS } + 1)

    private fun appendDigit(current: String, digit: String): String {
        val operand = currentOperand(current)
        val dotIndex = operand.indexOf('.')
        val fenLen = if (dotIndex >= 0) operand.length - dotIndex - 1 else 0
        if (fenLen > 2) return current
        val intLen = if (dotIndex >= 0) dotIndex else operand.length
        if (intLen >= 9) return current
        val base = if (operand == "0") current.dropLast(1) else current
        return base + digit
    }

    private fun appendDecimal(current: String): String {
        val operand = currentOperand(current)
        if (operand.contains('.')) return current
        return if (operand.isEmpty()) current + "0." else current + "."
    }

    private fun appendOperator(current: String, op: String): String {
        if (current.isEmpty()) return current
        val last = current.last()
        return when {
            // 连着按运算符：直接替换成最后一个。
            last in RecordEditState.OPERATOR_CHARS -> current.dropLast(1) + op
            // 「12.」视作 12，再按运算符时丢掉尾部小数点。
            last == '.' -> current.dropLast(1) + op
            else -> current + op
        }
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

    /** 保存账单；成功后置 [RecordEditState.isSaved]，由界面决定返回或连续录入。 */
    fun save() {
        val current = _state.value
        val cents = current.amountCents ?: return
        if (!current.canSave) return
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
            _state.value = _state.value.copy(isSaved = true)
        }
    }

    /**
     * 连续录入：清空表单留在本页，保留账单类型与记账日期，并重新带出默认分类/账户。
     */
    fun resetForNext() {
        val current = _state.value
        _state.value = RecordEditState(
            type = current.type,
            recordDate = current.recordDate
        )
        ensureDefaults(expenseCategories.value, incomeCategories.value)
    }
}
