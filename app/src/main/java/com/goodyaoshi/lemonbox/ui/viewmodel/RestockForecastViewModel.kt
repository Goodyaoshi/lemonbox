package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.forecast.RestockForecast
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 常买物品预测（F08）：按消耗速度推算补货时机。 */
@HiltViewModel
class RestockForecastViewModel @Inject constructor(
    private val itemRepository: ItemRepository
) : ViewModel() {

    val forecasts: StateFlow<List<RestockForecast>> =
        itemRepository.getAllItems()
            .map { RestockForecast.compute(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 把某个物品加进待买清单。 */
    fun addToBuy(itemId: Long) {
        viewModelScope.launch { itemRepository.setNeedRestock(itemId, true) }
    }
}
