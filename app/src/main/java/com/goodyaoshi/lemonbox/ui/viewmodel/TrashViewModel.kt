package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudPurger
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.util.ImageUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val cloudPurger: ImageCloudPurger
) : ViewModel() {

    private val retentionWindowMs = 30L * 24 * 60 * 60 * 1000
    private val cutoffTime = System.currentTimeMillis() - retentionWindowMs

    private val loading = LoadingState()

    /** 首帧加载标记：回收站列表读完之前显示骨架屏，避免「假空态」（见 [LoadingState]）。 */
    val isLoading: StateFlow<Boolean> = loading.isLoading

    val deletedItems: StateFlow<List<ItemDetail>> = itemRepository
        .getRecycleItems(cutoffTime)
        .onEach { loading.markLoaded() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    fun toggleSelection(itemId: Long) {
        _selectedIds.update { current ->
            if (itemId in current) current - itemId else current + itemId
        }
    }

    fun selectAll(itemIds: Set<Long>) {
        _selectedIds.value = itemIds
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun pruneSelection(validIds: Set<Long>) {
        _selectedIds.update { current -> current.intersect(validIds) }
    }

    fun restore(itemId: Long) {
        viewModelScope.launch {
            itemRepository.restoreFromTrash(itemId)
            _selectedIds.update { it - itemId }
        }
    }

    /**
     * 批量恢复（G-12）：恢复前先按当前回收站列表拍下快照——恢复后这些条目会离开列表，
     * 届时再也查不到，撤销时只能按快照原样移回。完成后清空选择并把快照交还页面，
     * 由页面展示回执并接「撤销」，与删除路径（确认+撤销）对齐，不再让恢复成为单向门。
     */
    fun restoreSelected(onRestored: (List<Item>) -> Unit) {
        val itemIds = _selectedIds.value.toList()
        if (itemIds.isEmpty()) return

        viewModelScope.launch {
            val selectedNow = _selectedIds.value
            val snapshots = deletedItems.value
                .filter { it.item.id in selectedNow }
                .map { it.item }
            itemRepository.restoreFromTrash(itemIds)
            _selectedIds.value = emptySet()
            onRestored(snapshots)
        }
    }

    /** 撤销恢复：把这批条目按原快照重新移入回收站（deletedAt 刷新为当前时刻，重开 30 天窗口）。 */
    fun moveToTrashAgain(items: List<Item>) {
        viewModelScope.launch {
            items.forEach { itemRepository.moveToTrash(it) }
        }
    }

    fun permanentlyDelete(itemIds: List<Long>) {
        if (itemIds.isEmpty()) return

        viewModelScope.launch {
            val purgedKeys = mutableListOf<String>()
            itemRepository.permanentlyDeleteFromTrash(itemIds).forEach { item ->
                if (item.imagePath.isNotBlank()) {
                    ImageUtil.deleteImage(item.imagePath)
                }
                // 永久删除前记下这些物品用过的云端对象键，删除后据此清理孤儿对象。
                purgedKeys += item.imageObjectKeyList()
            }
            _selectedIds.update { it - itemIds.toSet() }
            // 云端清理失败不该回退本地删除（内部已静默处理，且会先排除仍被引用的键）。
            cloudPurger.purgeCloudObjects(purgedKeys)
        }
    }
}
