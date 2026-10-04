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

    val activeItems: StateFlow<List<ItemDetail>> = itemRepository.getActiveItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 用户自定义的物品状态（不含内置，含两个维度），与物品详情共用同一份偏好。 */
    val customStatuses: StateFlow<List<ItemStatusOption>> = appPreferences.customStatuses

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

    fun deleteCategory(categoryId: Long?) {
        val id = categoryId ?: return
        viewModelScope.launch {
            categories.value.firstOrNull { it.id == id }?.let { category ->
                categoryRepository.delete(category)
                if (_selectedCategoryId.value == id) {
                    _selectedCategoryId.value = null
                }
            }
        }
    }

    fun addLocation(name: String, parentId: Long? = null) {
        val normalized = name.trim()
        if (normalized.isBlank()) return
        viewModelScope.launch {
            locationRepository.insert(Location(name = normalized, parentId = parentId))
        }
    }

    fun deleteLocation(locationId: Long?) {
        val id = locationId ?: return
        viewModelScope.launch {
            allLocations.value.firstOrNull { it.id == id }?.let { location ->
                locationRepository.delete(location)
                if (_selectedLocationId.value == id) {
                    _selectedLocationId.value = null
                }
            }
        }
    }

    fun updateLocation(locationId: Long?, name: String) {
        val id = locationId ?: return
        val normalized = name.trim()
        if (normalized.isBlank()) return
        viewModelScope.launch {
            allLocations.value.firstOrNull { it.id == id }?.let { location ->
                locationRepository.update(location.copy(name = normalized))
            }
        }
    }

    /** 返回 false 表示同维度内名称重复或为空。 */
    fun addCustomStatus(label: String, dimension: StatusDimension): Boolean =
        appPreferences.addCustomStatus(label, dimension)

    /** 重命名自定义状态；同维度内重名或为空时返回 false。 */
    fun updateCustomStatus(code: Int, label: String): Boolean =
        appPreferences.updateCustomStatus(code, label)

    fun removeCustomStatus(code: Int) {
        appPreferences.removeCustomStatus(code)
    }

    fun getSubLocations(parentId: Long): Flow<List<Location>> {
        return locationRepository.getSubLocations(parentId)
    }

    fun getSubCategories(parentId: Long): Flow<List<Category>> {
        return categoryRepository.getSubCategories(parentId)
    }
}
