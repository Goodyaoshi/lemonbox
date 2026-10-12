package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.forecast.RestockForecast
import com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 待买批量结账的一行（GAP-6）：物品、预填金额（分）、是否同时记一笔。 */
data class CheckoutRow(
    val item: Item,
    val amountCents: Long,
    val record: Boolean
)

/** 待买清单：把"用完"的物品集中起来，买到后一键归位。 */
@HiltViewModel
class ToBuyViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val categoryRepository: CategoryRepository,
    private val ledgerRepository: LedgerRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val loading = LoadingState()

    /** 她的称呼：「给谁的」标签里的「给她」显示当前昵称。 */
    val nickname: StateFlow<String> = appPreferences.nickname

    /** 首帧加载标记：待买列表读完之前显示骨架屏，避免「假空态」（见 [LoadingState]）。 */
    val isLoading: StateFlow<Boolean> = loading.isLoading

    /** 全部待买项（未筛选）。 */
    val items: StateFlow<List<ItemDetail>> = itemRepository.getToBuyItems()
        .onEach { loading.markLoaded() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 分类树：供「关联分类」选择与列表筛选使用。 */
    val categories: StateFlow<List<Category>> = categoryRepository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _filterCategoryId = MutableStateFlow<Long?>(null)

    /** 当前筛选的分类；null 表示「全部」。选中父分类时会连同其子树一起显示。 */
    val filterCategoryId: StateFlow<Long?> = _filterCategoryId.asStateFlow()

    // 批量选择状态（G-4）：与回收站同一套 selectedIds 范式，超市一次买回多件时不用逐条点圆圈。
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)

    /** 批量模式开关：进入后行首圆圈换成勾选框，侧滑与编辑按钮暂时隐藏。 */
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    fun enterSelectionMode() {
        _isSelectionMode.value = true
    }

    /** 退出批量模式：同时清空勾选，恢复单条交互。 */
    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedIds.value = emptySet()
    }

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

    /**
     * 建议补货（IA-2）：按消耗速度推算该补的常买物品，剔掉已经在待买清单里的，
     * 作为待买列表顶部的「建议补货」分组，点一下即加入待买——把消耗分析放回执行清单里，
     * 而不是让「智能补货」以独立页头的形式与「我要买」并列。
     */
    val restockSuggestions: StateFlow<List<RestockForecast>> = combine(
        itemRepository.getAllItems(),
        items
    ) { all, toBuy ->
        val toBuyIds = toBuy.map { it.item.id }.toSet()
        RestockForecast.compute(all)
            .filter { it.detail.item.id !in toBuyIds }
            .take(MAX_RESTOCK_SUGGESTIONS)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 把「建议补货」里的一条加入待买（IA-2）。 */
    fun addSuggestionToBuy(itemId: Long) {
        viewModelScope.launch { itemRepository.setNeedRestock(itemId, true) }
    }

    /** 可用账户（GAP-6）：批量结账时要选一个账户记账。 */
    val assets: StateFlow<List<LedgerAsset>> = ledgerRepository.observeAssets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 批量结账（GAP-6）：为勾选入账的条目各建一笔支出，并把这些物品统一归位到家当。
     * 回执交还归位前的物品快照与新建的账目 id，供「撤销」把两边一起回滚。
     */
    fun checkout(
        rows: List<CheckoutRow>,
        assetId: Long,
        onDone: (items: List<Item>, recordIds: List<Long>) -> Unit
    ) {
        if (rows.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val recordIds = rows
                .filter { it.record && it.amountCents > 0 }
                .map { row ->
                    ledgerRepository.saveRecord(
                        LedgerRecord(
                            type = LedgerRecord.TYPE_EXPENSE,
                            amount = row.amountCents,
                            assetId = assetId,
                            recordTime = now,
                            remark = row.item.name,
                            itemId = row.item.id
                        )
                    )
                }
            val snapshots = rows.map { it.item }
            snapshots.forEach { itemRepository.restoreToInUse(it.id) }
            exitSelectionMode()
            onDone(snapshots, recordIds)
        }
    }

    /** 撤销批量结账（GAP-6）：删掉刚建的账目并把物品退回待买。 */
    fun undoCheckout(snapshots: List<Item>, recordIds: List<Long>) {
        viewModelScope.launch {
            recordIds.forEach { ledgerRepository.deleteRecord(it) }
            snapshots.forEach { itemRepository.undoRestoreToInUse(it) }
        }
    }

    /** 勾选"已买到"：回到在用状态。 */
    fun markBought(id: Long) {
        viewModelScope.launch {
            itemRepository.restoreToInUse(id)
        }
    }

    /** 撤销「已买到」：按 Snackbar 提示里点撤销前的快照恢复。 */
    fun undoMarkBought(previous: Item) {
        viewModelScope.launch {
            itemRepository.undoRestoreToInUse(previous)
        }
    }

    /**
     * 批量归位（G-4）：归位前先从全量待买列表里取选中条目的快照（归位会改写使用状态与
     * 周期字段，撤销必须按原样写回），逐条恢复后在回调里交还快照供页面提示与整体撤销。
     * 批量场景刻意不联动「记一笔」：一次买回多件时记账意图不明确，先保持克制。
     */
    fun markSelectedBought(onDone: (List<Item>) -> Unit) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val snapshots = items.value.filter { it.item.id in ids }.map { it.item }
            snapshots.forEach { itemRepository.restoreToInUse(it.id) }
            _selectedIds.value = emptySet()
            onDone(snapshots)
        }
    }

    fun moveToTrash(item: Item) {
        viewModelScope.launch {
            itemRepository.moveToTrash(item)
        }
    }

    /** 撤销删除：把刚移入回收站的待买项恢复回来。 */
    fun restoreFromTrash(id: Long) {
        viewModelScope.launch {
            itemRepository.restoreFromTrash(id)
        }
    }

    /** 手动添加待买项：名称 + 数量 + 单位，可选关联分类与「给谁的」归属。 */
    fun addToBuy(
        name: String,
        quantity: Int,
        unit: String,
        categoryId: Long?,
        forWhom: String?
    ) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return
        viewModelScope.launch {
            itemRepository.addToBuyItem(normalizedName, quantity, unit, categoryId, forWhom)
        }
    }

    /** 编辑待买项：改名字 / 数量 / 单位 / 分类 / 归属（买到后仍会归位到家当）。 */
    fun updateToBuy(
        item: Item,
        name: String,
        quantity: Int,
        unit: String,
        categoryId: Long?,
        forWhom: String?
    ) {
        viewModelScope.launch {
            itemRepository.updateToBuyItem(item, name, quantity, unit, categoryId, forWhom)
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

        /** 待买页顶部「建议补货」最多展示的条数：够用即可，不淹没真正的待买清单。 */
        const val MAX_RESTOCK_SUGGESTIONS = 5
    }
}