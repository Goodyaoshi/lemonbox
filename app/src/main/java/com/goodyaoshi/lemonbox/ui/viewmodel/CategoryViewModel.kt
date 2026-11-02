package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusOption
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LocationRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 删除自定义状态前的快照（P1-2）：状态本体 + 仍在使用它的物品 id。
 * 撤销时据此重新登记状态并把物品回挂，避免它们退化成「状态 N」。
 */
data class RemovedStatusSnapshot(
    val option: ItemStatusOption,
    val affectedItemIds: List<Long>
)

@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val locationRepository: LocationRepository,
    private val itemRepository: ItemRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    val categories: StateFlow<List<Category>> = categoryRepository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rootCategories: StateFlow<List<Category>> = categoryRepository.getRootCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allLocations: StateFlow<List<Location>> = locationRepository.getAllLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rootLocations: StateFlow<List<Location>> = locationRepository.getRootLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 真实顶层位置（不拍平）：管理页要能操作唯一顶级本身；选择器仍用拍平后的 [rootLocations]。 */
    val topLocations: StateFlow<List<Location>> = locationRepository.getTopLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeItems: StateFlow<List<ItemDetail>> = itemRepository.getActiveItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 用户自定义的物品状态（不含内置，含两个维度），与物品详情共用同一份偏好。 */
    val customStatuses: StateFlow<List<ItemStatusOption>> = appPreferences.customStatuses

    /** 到期提醒阶梯（提前 N 天），未单独设置的物品都跟随这份默认阶梯。 */
    val reminderLadder: StateFlow<List<Int>> = appPreferences.reminderLadder

    /** 家当录入页的有效期快捷档位（编码列表），可自定义。 */
    val expiryQuickOptions: StateFlow<List<String>> = appPreferences.expiryQuickOptions

    /** 更新到期提醒阶梯。 */
    fun setReminderLadder(days: List<Int>) {
        appPreferences.setReminderLadder(days)
    }

    /** 更新有效期快捷档位。 */
    fun setExpiryQuickOptions(codes: List<String>) {
        appPreferences.setExpiryQuickOptions(codes)
    }

    private val _selectedCategoryId = MutableStateFlow<Long?>(null)
    val selectedCategoryId: StateFlow<Long?> = _selectedCategoryId.asStateFlow()

    private val _selectedLocationId = MutableStateFlow<Long?>(null)
    val selectedLocationId: StateFlow<Long?> = _selectedLocationId.asStateFlow()

    fun selectCategory(id: Long?) {
        _selectedCategoryId.value = id
        _selectedLocationId.value = null
    }

    fun selectLocation(id: Long?) {
        _selectedLocationId.value = id
        _selectedCategoryId.value = null
    }

    fun addCategory(name: String, icon: String = "", parentId: Long? = null) {
        val normalized = name.trim()
        if (normalized.isBlank()) return
        viewModelScope.launch {
            categoryRepository.insert(Category(name = normalized, icon = icon, parentId = parentId))
        }
    }

    fun updateCategory(categoryId: Long?, name: String, icon: String) {
        val id = categoryId ?: return
        val normalized = name.trim()
        if (normalized.isBlank()) return
        viewModelScope.launch {
            categories.value.firstOrNull { it.id == id }?.let { category ->
                categoryRepository.update(category.copy(name = normalized, icon = icon))
            }
        }
    }

    /** 删除分类，返回被软删（含下级）的分类 id，供撤销恢复（P2-4）。 */
    suspend fun deleteCategory(categoryId: Long?): List<Long> {
        val id = categoryId ?: return emptyList()
        val category = categories.value.firstOrNull { it.id == id } ?: return emptyList()
        val removedIds = categoryRepository.delete(category)
        if (_selectedCategoryId.value == id) {
            _selectedCategoryId.value = null
        }
        return removedIds
    }

    /** 撤销删除分类：清掉墓碑，恢复到原归属（P2-4）。 */
    suspend fun restoreCategory(ids: List<Long>) {
        categoryRepository.restoreDeleted(ids)
    }

    /** 返回 false 表示同名位置已存在（同一父级下）。调用方需要把结果告诉用户。 */
    fun addLocation(name: String, parentId: Long? = null): Boolean {
        val normalized = name.trim()
        if (normalized.isBlank()) return false
        // 在内存快照上先判重，避免「点保存后界面无任何变化」的静默失败。
        if (allLocations.value.any { it.parentId == parentId && it.name == normalized }) return false
        viewModelScope.launch {
            locationRepository.insert(Location(name = normalized, parentId = parentId))
        }
        return true
    }

    /** 删除位置，返回被软删（含下级）的位置 id，供撤销恢复（P2-4）。 */
    suspend fun deleteLocation(locationId: Long?): List<Long> {
        val id = locationId ?: return emptyList()
        val location = allLocations.value.firstOrNull { it.id == id } ?: return emptyList()
        val removedIds = locationRepository.delete(location)
        if (_selectedLocationId.value == id) {
            _selectedLocationId.value = null
        }
        return removedIds
    }

    /** 撤销删除位置：清掉墓碑，恢复到原归属（P2-4）。 */
    suspend fun restoreLocation(ids: List<Long>) {
        locationRepository.restoreDeleted(ids)
    }

    /** 返回 false 表示同一层级下已有同名位置（或目标不存在）。 */
    fun updateLocation(locationId: Long?, name: String): Boolean {
        val id = locationId ?: return false
        val normalized = name.trim()
        if (normalized.isBlank()) return false
        val target = allLocations.value.firstOrNull { it.id == id } ?: return false
        // 改名同样要判重：否则两个兄弟位置可以改成一模一样，选择器里无法区分。
        if (allLocations.value.any {
                it.id != id && it.parentId == target.parentId && it.name == normalized
            }
        ) {
            return false
        }
        viewModelScope.launch {
            locationRepository.update(target.copy(name = normalized))
        }
        return true
    }

    /** 返回 false 表示同维度内名称重复或为空。 */
    fun addCustomStatus(label: String, dimension: StatusDimension): Boolean =
        appPreferences.addCustomStatus(label, dimension)

    /** 重命名自定义状态；同维度内重名或为空时返回 false。 */
    fun updateCustomStatus(code: Int, label: String): Boolean =
        appPreferences.updateCustomStatus(code, label)

    /** 统计仍在使用某自定义状态的物品数（P1-2：删除前告知影响面）。 */
    suspend fun countItemsUsingStatus(option: ItemStatusOption): Int = when (option.dimension) {
        StatusDimension.USAGE -> itemRepository.countItemsByUsageStatus(option.code)
        StatusDimension.DISPOSITION -> itemRepository.countItemsByDisposition(option.code)
    }

    /** 删除自定义状态并返回快照（状态本体 + 受影响物品 id），供撤销恢复（P1-2）。 */
    suspend fun removeCustomStatusWithSnapshot(code: Int): RemovedStatusSnapshot? {
        val option = customStatuses.value.firstOrNull { it.code == code } ?: return null
        val affectedItemIds = when (option.dimension) {
            StatusDimension.USAGE -> itemRepository.getItemIdsByUsageStatus(code)
            StatusDimension.DISPOSITION -> itemRepository.getItemIdsByDisposition(code)
        }
        appPreferences.removeCustomStatus(code)
        return RemovedStatusSnapshot(option, affectedItemIds)
    }

    /**
     * 撤销删除状态（P1-2）：重新登记该状态，并把仍在使用它的物品回挂到新编号。
     * 重新登记会分配一个新的编号，故必须同步回挂物品，否则它们仍显示成「状态 N」。
     */
    suspend fun restoreCustomStatus(snapshot: RemovedStatusSnapshot) {
        val option = snapshot.option
        if (!appPreferences.addCustomStatus(option.label, option.dimension)) return
        val restoredCode = customStatuses.value.lastOrNull {
            it.dimension == option.dimension && it.label == option.label
        }?.code ?: return
        when (option.dimension) {
            StatusDimension.USAGE ->
                itemRepository.setUsageStatusForIds(snapshot.affectedItemIds, restoredCode)

            StatusDimension.DISPOSITION ->
                itemRepository.setDispositionForIds(snapshot.affectedItemIds, restoredCode)
        }
    }

    fun getSubLocations(parentId: Long): Flow<List<Location>> {
        return locationRepository.getSubLocations(parentId)
    }

    fun getSubCategories(parentId: Long): Flow<List<Category>> {
        return categoryRepository.getSubCategories(parentId)
    }
}
