package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 待买清单：把"用完"的物品集中起来，买到后一键归位。 */
@HiltViewModel
class ToBuyViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    /** 全部待买项（未筛选）。 */
    val items: StateFlow<List<ItemDetail>> = itemRepository.getToBuyItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 分类树：供「关联分类」选择与列表筛选使用。 */
    val categories: StateFlow<List<Category>> = categoryRepository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _filterCategoryId = MutableStateFlow<Long?>(null)

    /** 当前筛选的分类；null 表示「全部」。选中父分类时会连同其子树一起显示。 */
    val filterCategoryId: StateFlow<Long?> = _filterCategoryId.asStateFlow()

    /** 按分类筛选后的待买项，未选分类时即全部。 */
    val filteredItems: StateFlow<List<ItemDetail>> =
        combine(items, categories, _filterCategoryId) { list, categories, filterId ->
            if (filterId == null) {
                list
            } else {
                val parentOf = categories.associate { it.id to it.parentId }
                list.filter { detail ->
                    detail.item.categoryId?.let { categoryId ->
                        categoryId == filterId || isDescendantOf(categoryId, filterId, parentOf)
                    } == true
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setFilterCategory(categoryId: Long?) {
        _filterCategoryId.value = categoryId
    }

    /** 勾选"已买到"：回到在用状态。 */
    fun markBought(id: Long) {
        viewModelScope.launch {
            itemRepository.restoreToInUse(id)
        }
    }

    fun moveToTrash(item: Item) {
        viewModelScope.launch {
            itemRepository.moveToTrash(item)
        }
    }

    /** 手动添加待买项：名称 + 数量 + 单位，可选关联分类。 */
    fun addToBuy(name: String, quantity: Int, unit: String, categoryId: Long?) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return
        viewModelScope.launch {
            itemRepository.addToBuyItem(normalizedName, quantity, unit, categoryId)
        }
    }

    /** [categoryId] 是否位于 [ancestorId] 之下（用于父分类筛选命中子分类的物品）。 */
    private fun isDescendantOf(
        categoryId: Long,
        ancestorId: Long,
        parentOf: Map<Long, Long?>
    ): Boolean {
        var cursor = parentOf[categoryId]
        var guard = 0
        while (cursor != null && guard++ < MAX_CATEGORY_DEPTH) {
            if (cursor == ancestorId) return true
            cursor = parentOf[cursor]
        }
        return false
    }

    private companion object {
        /** 分类链回溯的深度上限，防御异常数据成环。 */
        const val MAX_CATEGORY_DEPTH = 64
    }
}