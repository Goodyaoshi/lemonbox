package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.PackingItem
import com.goodyaoshi.lemonbox.data.local.entity.PackingTrip
import com.goodyaoshi.lemonbox.data.repository.PackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 单个行程的打包清单（F16）：读导航参数 tripId 载入行程与条目，
 * 支持增删条目、勾选「已装好」。
 */
@HiltViewModel
class PackingTripViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PackingRepository
) : ViewModel() {

    private val tripId: Long = savedStateHandle.get<Long>("tripId") ?: -1L

    val trip: StateFlow<PackingTrip?> = if (tripId > 0) {
        repository.getTripFlow(tripId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    } else {
        flowOf<PackingTrip?>(null)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    val items: StateFlow<List<PackingItem>> = if (tripId > 0) {
        repository.observeItems(tripId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    } else {
        flowOf(emptyList<PackingItem>())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun addItem(name: String, quantity: Int, category: String, onDone: () -> Unit = {}) {
        if (tripId <= 0) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.addItem(
                PackingItem(
                    tripId = tripId,
                    name = trimmed,
                    quantity = quantity.coerceAtLeast(1),
                    category = category.trim()
                )
            )
            onDone()
        }
    }

    fun setChecked(item: PackingItem, checked: Boolean) {
        viewModelScope.launch { repository.setChecked(item, checked) }
    }

    fun deleteItem(item: PackingItem) {
        viewModelScope.launch { repository.deleteItem(item) }
    }

    /** 撤销删除（GAP-13）：把刚软删的条目恢复回来。 */
    fun restoreItem(item: PackingItem) {
        viewModelScope.launch { repository.restoreItem(item) }
    }
}
