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
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecurringRule
import com.goodyaoshi.lemonbox.data.local.entity.LedgerTemplate
import com.goodyaoshi.lemonbox.data.backup.FieldDiff
import com.goodyaoshi.lemonbox.data.repository.LedgerPlanningRepository
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath
import com.goodyaoshi.lemonbox.util.RecurringClock
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
    val isSaved: Boolean = false,
    /** 编辑保存后的字段级变更摘要（GAP-5）：改前 → 改后，供保存回执逐行展示。 */
    val savedDiff: List<FieldDiff> = emptyList()
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
    private val planningRepository: LedgerPlanningRepository,
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

    /** 记账模板（F05）：记一笔页顶部一键套用。 */
    val templates: StateFlow<List<LedgerTemplate>> =
        planningRepository.observeTemplates()
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
                _state.value = _state.value.copy(isSaved = true)
            } else {
                // 编辑：保留原 createdAt，避免抹掉；并算出字段级变更摘要供保存回执（GAP-5）。
                val origin = ledgerRepository.getRecord(current.recordId)
                ledgerRepository.saveRecord(record.copy(createdAt = origin?.createdAt ?: record.recordTime))
                _state.value = _state.value.copy(
                    isSaved = true,
                    savedDiff = origin?.let { buildDiff(it, record) }.orEmpty()
                )
            }
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

    // ---------- 模板与周期规则（F05） ----------

    /** 套用模板：不覆盖当前金额（模板金额为 0 时保留手输值），其余参数一键带出。 */
    fun applyTemplate(template: LedgerTemplate) {
        val current = _state.value
        _state.value = current.copy(
            type = template.type,
            amountText = if (template.amount > 0) {
                LedgerMath.centsToInputText(template.amount)
            } else {
                current.amountText
            },
            categoryId = if (template.type == TYPE_TRANSFER) null else template.categoryId,
            assetId = template.assetId,
            targetAssetId = if (template.type == TYPE_TRANSFER) template.targetAssetId else null,
            remark = template.remark,
            initialized = true
        )
    }

    /** 把当前填写的参数存为模板；名称为空返回 false，不创建。 */
    fun saveAsTemplate(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val current = _state.value
        val template = LedgerTemplate(
            name = trimmed,
            type = current.type,
            amount = current.amountCents ?: 0L,
            categoryId = if (current.type == TYPE_TRANSFER) null else current.categoryId,
            assetId = current.assetId,
            targetAssetId = if (current.type == TYPE_TRANSFER) current.targetAssetId else null,
            remark = current.remark.trim()
        )
        viewModelScope.launch { planningRepository.saveTemplate(template) }
        return true
    }

    /**
     * 把当前填写的参数设为周期记账规则。首次生成时间取记录日期当天 09:00，
     * 若已过去则按周期推进到未来。名称为空或表单不可保存时返回 false。
     */
    fun createRecurringRule(
        name: String,
        unit: LedgerRecurringRule.RepeatUnit,
        interval: Int
    ): Boolean {
        val current = _state.value
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !current.canSave) return false
        val cents = current.amountCents ?: return false
        val step = interval.coerceAtLeast(1)
        val zone = ZoneId.systemDefault()
        var first = current.recordDate.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        var guard = 0
        while (first <= now && guard < 500) {
            first = RecurringClock.advance(first, unit, step)
            guard++
        }
        val rule = LedgerRecurringRule(
            name = trimmed,
            type = current.type,
            amount = cents,
            categoryId = if (current.type == TYPE_TRANSFER) null else current.categoryId,
            assetId = current.assetId,
            targetAssetId = if (current.type == TYPE_TRANSFER) current.targetAssetId else null,
            remark = current.remark.trim(),
            repeatUnit = unit.name,
            repeatInterval = step,
            nextFireAt = first
        )
        viewModelScope.launch { planningRepository.saveRule(rule) }
        return true
    }

    // ---------- GAP-5：编辑保存的字段级回执 ----------

    /** 对比编辑前后的账单，产出「改前 → 改后」的字段差异（只列出真正变了的字段）。 */
    private fun buildDiff(origin: LedgerRecord, updated: LedgerRecord): List<FieldDiff> = buildList {
        if (origin.type != updated.type) {
            add(FieldDiff("type", "类型", recordTypeLabel(origin.type), recordTypeLabel(updated.type)))
        }
        if (origin.amount != updated.amount) {
            add(
                FieldDiff(
                    "amount", "金额",
                    DateUtil.formatCurrency(LedgerMath.centsToYuan(origin.amount)),
                    DateUtil.formatCurrency(LedgerMath.centsToYuan(updated.amount))
                )
            )
        }
        if (origin.categoryId != updated.categoryId) {
            add(FieldDiff("categoryId", "分类", categoryName(origin.categoryId), categoryName(updated.categoryId)))
        }
        if (origin.assetId != updated.assetId) {
            add(FieldDiff("assetId", "账户", assetName(origin.assetId), assetName(updated.assetId)))
        }
        if (origin.targetAssetId != updated.targetAssetId) {
            add(
                FieldDiff(
                    "targetAssetId", "转入账户",
                    assetName(origin.targetAssetId), assetName(updated.targetAssetId)
                )
            )
        }
        if (dayOf(origin.recordTime) != dayOf(updated.recordTime)) {
            add(
                FieldDiff(
                    "recordTime", "日期",
                    dayOf(origin.recordTime).toString(), dayOf(updated.recordTime).toString()
                )
            )
        }
        if (origin.remark != updated.remark) {
            add(
                FieldDiff(
                    "remark", "备注",
                    origin.remark.ifBlank { "（空）" }, updated.remark.ifBlank { "（空）" }
                )
            )
        }
    }

    private fun recordTypeLabel(type: Int): String = when (type) {
        TYPE_INCOME -> "收入"
        TYPE_TRANSFER -> "转账"
        else -> "支出"
    }

    private fun categoryName(id: Long?): String {
        if (id == null) return "（无）"
        return (expenseCategories.value + incomeCategories.value)
            .firstOrNull { it.id == id }?.name ?: "已删除分类"
    }

    private fun assetName(id: Long?): String {
        if (id == null) return "（无）"
        return assets.value.firstOrNull { it.id == id }?.name ?: "已删除账户"
    }

    private fun dayOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
}
