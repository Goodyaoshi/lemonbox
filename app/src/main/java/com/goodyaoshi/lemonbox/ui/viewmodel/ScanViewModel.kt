package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScanUiState(
    val barcode: String = "",
    val template: Item? = null,
    val activeItems: List<ItemDetail> = emptyList(),
    val historyItems: List<ItemDetail> = emptyList(),
    val message: String? = null
) {
    val hasActive: Boolean get() = activeItems.isNotEmpty()
    val hasHistory: Boolean get() = historyItems.isNotEmpty()

    val productName: String
        get() = template?.name
            ?: historyItems.firstOrNull()?.item?.name
            ?: "未知物品"

    val primaryItemId: Long?
        get() = activeItems.firstOrNull()?.item?.id
            ?: historyItems.firstOrNull()?.item?.id
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val itemRepository: ItemRepository
) : ViewModel() {

    private val barcodeFlow = MutableStateFlow("")
    private val templateFlow = MutableStateFlow<Item?>(null)
    private val messageFlow = MutableStateFlow<String?>(null)

    private val activeItems = barcodeFlow.flatMapLatest { code ->
        if (code.isBlank()) flowOf(emptyList()) else itemRepository.getActiveItemsByBarcode(code)
    }

    private val historyItems = barcodeFlow.flatMapLatest { code ->
        if (code.isBlank()) flowOf(emptyList()) else itemRepository.getItemsByBarcode(code)
    }

    val state: StateFlow<ScanUiState> = combine(
        barcodeFlow,
        templateFlow,
        activeItems,
        historyItems,
        messageFlow
    ) { code, template, active, history, message ->
        ScanUiState(
            barcode = code,
            template = template,
            activeItems = active,
            historyItems = history,
            message = message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScanUiState())

    fun setBarcode(code: String) {
        if (barcodeFlow.value == code) return
        barcodeFlow.value = code
        viewModelScope.launch {
            templateFlow.value = itemRepository.getLatestItemByBarcode(code)
        }
    }

    /** 使用：把最早到期的一个在库批次消耗 1 件；减到 0 自动记为已用完。 */
    fun consumeEarliest() {
        val target = state.value.activeItems.firstOrNull()?.item ?: return
        viewModelScope.launch {
            itemRepository.consumeOne(target.id)
            messageFlow.value = "已使用 1${target.unit}「${target.name}」"
        }
    }

    /** 入库：同条码同有效期则累加数量，否则新增一条携带该有效期的批次记录。 */
    fun checkIn(quantity: Int, expireTime: Long?) {
        val code = barcodeFlow.value
        val template = templateFlow.value ?: return
        if (quantity <= 0) return

        viewModelScope.launch {
            val existing = itemRepository.getActiveItemsByBarcodeSync(code)
                .firstOrNull { it.expireTime == expireTime }
            if (existing != null) {
                itemRepository.incrementQuantity(existing.id, quantity)
                messageFlow.value = "已入库 $quantity${existing.unit}，数量已累加"
            } else {
                itemRepository.insert(
                    template.copy(
                        id = 0,
                        quantity = quantity,
                        expireTime = expireTime,
                        usageStatus = Item.USAGE_IN_USE,
                        disposition = Item.DISPOSITION_IN_STOCK,
                        needRestock = false,
                        rating = null,
                        ratedAt = null,
                        deletedAt = null,
                        createdAt = System.currentTimeMillis()
                    )
                )
                messageFlow.value = "已入库 $quantity${template.unit}"
            }
        }
    }

    fun consumeMessage() {
        messageFlow.value = null
    }
}
