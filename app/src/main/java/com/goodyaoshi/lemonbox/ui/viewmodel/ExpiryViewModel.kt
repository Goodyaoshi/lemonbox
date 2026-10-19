package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExpiryViewModel @Inject constructor(
    private val itemRepository: ItemRepository
) : ViewModel() {

    val expirableItems: StateFlow<List<ItemDetail>> = itemRepository.getActiveItems()
        .map { items -> items.filter { it.item.expireTime != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 到期列表快捷操作：标记为「已用完」。 */
    fun markAsUsed(id: Long) {
        viewModelScope.launch { itemRepository.markAsUsed(id) }
    }

    /** 到期列表快捷操作：标记为「已丢弃」。 */
    fun markAsDiscarded(id: Long) {
        viewModelScope.launch { itemRepository.markAsDiscarded(id) }
    }

    /**
     * 到期列表快捷操作：把物品加入待买清单（F07）。
     * 只置「需要补货」位，不改使用状态：快到期但还在用，先记着回头买。
     */
    fun addToBuy(id: Long) {
        viewModelScope.launch { itemRepository.setNeedRestock(id, true) }
    }

    /** 撤销「标记为已用完」（P2-18）：按动作前快照还原使用状态、补货标记与使用周期。 */
    fun undoMarkAsUsed(previous: Item) {
        viewModelScope.launch { itemRepository.undoMarkAsUsed(previous) }
    }

    /** 撤销「加入待买清单」（P2-18）：恢复动作前的补货标记。 */
    fun undoAddToBuy(previous: Item) {
        viewModelScope.launch { itemRepository.undoAddToBuy(previous) }
    }
}
