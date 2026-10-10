package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    categoryRepository: CategoryRepository,
    locationRepository: LocationRepository
) : ViewModel() {

    val items: StateFlow<List<ItemDetail>> = itemRepository.getAllItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 分类/存放位置的全量列表，用于把详情页的扁平名称还原成完整层级路径。 */
    val categories: StateFlow<List<Category>> = categoryRepository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locations: StateFlow<List<Location>> = locationRepository.getAllLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun markAsUsed(id: Long) {
        viewModelScope.launch {
            itemRepository.markAsUsed(id)
        }
    }

    fun markAsDiscarded(id: Long) {
        viewModelScope.launch {
            itemRepository.markAsDiscarded(id)
        }
    }

    fun setUsageStatus(id: Long, usageStatus: Int) {
        viewModelScope.launch {
            itemRepository.setUsageStatus(id, usageStatus)
        }
    }

    fun setDisposition(id: Long, disposition: Int) {
        viewModelScope.launch {
            itemRepository.setDisposition(id, disposition)
        }
    }

    fun setNeedRestock(id: Long, needRestock: Boolean) {
        viewModelScope.launch {
            itemRepository.setNeedRestock(id, needRestock)
        }
    }

    fun delete(item: Item) {
        viewModelScope.launch {
            itemRepository.moveToTrash(item)
        }
    }

    /** 撤销删除：把刚移入回收站的物品恢复回来（详情页删除后返回，仍可撤销）。 */
    fun restoreFromTrash(id: Long) {
        viewModelScope.launch {
            itemRepository.restoreFromTrash(id)
        }
    }
}
