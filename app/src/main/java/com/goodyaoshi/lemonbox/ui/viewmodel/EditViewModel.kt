package com.goodyaoshi.lemonbox.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LocationRepository
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncManager
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.ImageUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditItemState(
    val itemId: Long = 0,
    val name: String = "",
    val barcode: String? = null,
    val categoryId: Long? = null,
    val locationId: Long? = null,
    val quantity: Int = 1,
    val unit: String = "件",
    val price: String = "",
    val expireTime: Long? = null,
    val note: String = "",
    /**
     * 图片槽位的内容哈希，顺序即展示顺序（首项为主图）。
     *
     * 编辑时必须原样维护：`Item` 是整体覆盖写入的，漏带就等于把图片全清空，
     * 之后列表与详情都不再有图。
     */
    val imageHashes: List<String> = emptyList(),
    /** 该物品单独的到期提醒阶梯（天）；为空表示跟随全局默认阶梯。 */
    val reminderDays: List<Int> = emptyList(),
    /** 购买日期；未记录为 null。 */
    val purchaseDate: Long? = null,
    /** 生产日期；未记录为 null，仅作参考。 */
    val productionDate: Long? = null,
    /** 开始使用时间；可手动补录，为 null 表示还没开始用。 */
    val startUseTime: Long? = null,
    /** 计量方式：按件消耗 / 持续使用（耐用品）。 */
    val trackMode: Int = Item.TRACK_CONSUMABLE,
    val rating: Int? = null,
    val ratedAt: Long? = null,
    /** 状态三维字段不在编辑表单里修改，仅原样带过，避免编辑后丢失。 */
    val usageStatus: Int = Item.USAGE_IN_USE,
    val disposition: Int = Item.DISPOSITION_IN_STOCK,
    val needRestock: Boolean = false,
    /** 保留原有同步标识，避免编辑后被换成新的 UUID 导致跨设备合并错位。 */
    val syncId: String? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val isLoaded: Boolean = false
)

@HiltViewModel
class EditViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val categoryRepository: CategoryRepository,
    private val locationRepository: LocationRepository,
    private val appPreferences: AppPreferences,
    // 编辑保存后把该物品的图片后台送上云（F20）；开关关闭或未配置时静默跳过。
    private val imageCloudSync: ImageCloudSyncManager
) : ViewModel() {

    private val _state = MutableStateFlow(EditItemState())
    val state: StateFlow<EditItemState> = _state.asStateFlow()

    /** 全局默认的提醒阶梯，用于展示「跟随默认」的说明文案。 */
    val defaultReminderLadder: StateFlow<List<Int>> = appPreferences.reminderLadder

    /** 有效期快捷档位（设置页自定义的 x天/x周/x月/x年），与录入页保持一致。 */
    val expiryQuickOptions: StateFlow<List<String>> = appPreferences.expiryQuickOptions

    /** 「更多信息」展开态：默认折叠，用户展开后跨会话记住其选择（I6），与录入页共用一份。 */
    val advancedExpanded: StateFlow<Boolean> = appPreferences.editorAdvancedExpanded

    fun setAdvancedExpanded(expanded: Boolean) = appPreferences.setEditorAdvancedExpanded(expanded)

    val categories: StateFlow<List<Category>> = categoryRepository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locations: StateFlow<List<Location>> = locationRepository.getAllLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadItem(itemId: Long) {
        if (_state.value.isLoaded && _state.value.itemId == itemId) return
        viewModelScope.launch {
            val item = itemRepository.getItemById(itemId) ?: return@launch
            _state.value = EditItemState(
                itemId = item.id,
                name = item.name,
                barcode = item.barcode,
                categoryId = item.categoryId,
                locationId = item.locationId,
                quantity = item.quantity,
                unit = item.unit,
                price = item.price?.toString() ?: "",
                expireTime = item.expireTime,
                note = item.note,
                imageHashes = item.imageHashList(),
                reminderDays = Item.decodeReminderDays(item.reminderDays),
                purchaseDate = item.purchaseDate,
                productionDate = item.productionDate,
                startUseTime = item.startUseTime,
                trackMode = item.trackMode,
                rating = item.rating,
                ratedAt = item.ratedAt,
                usageStatus = item.usageStatus,
                disposition = item.disposition,
                needRestock = item.needRestock,
                syncId = item.syncId,
                isLoaded = true
            )
        }
    }

    fun appendPhotos(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val hashes = uris.mapNotNull { ImageUtil.saveImageHash(context, it) }
        if (hashes.isEmpty()) return
        val current = _state.value
        _state.value = current.copy(imageHashes = (current.imageHashes + hashes).distinct())
    }

    fun replacePrimaryPhoto(context: Context, uris: List<Uri>) {
        val hashes = uris.mapNotNull { ImageUtil.saveImageHash(context, it) }
        if (hashes.isEmpty()) return
        val current = _state.value
        val currentHashes = current.imageHashes.toMutableList()
        // 先落盘新图再删旧图：同内容会复用同一文件，顺序反了会把新图一起删掉。
        currentHashes.firstOrNull()?.let { old ->
            if (old !in hashes) {
                ImageUtil.findLocalFile(context, old)?.let { ImageUtil.deleteImage(it.absolutePath) }
            }
        }
        if (currentHashes.isEmpty()) {
            currentHashes += hashes
        } else {
            // 一次连拍多张：第一张替换原主图，其余顺延为细节图。
            currentHashes[0] = hashes.first()
            currentHashes += hashes.drop(1)
        }
        _state.value = current.copy(imageHashes = currentHashes.distinct())
    }

    fun removePhoto(context: Context, index: Int) {
        val current = _state.value
        val currentHashes = current.imageHashes.toMutableList()
        val removed = currentHashes.getOrNull(index) ?: return
        currentHashes.removeAt(index)
        if (removed !in currentHashes) {
            ImageUtil.findLocalFile(context, removed)?.let { ImageUtil.deleteImage(it.absolutePath) }
        }
        _state.value = current.copy(imageHashes = currentHashes)
    }

    fun movePhoto(fromIndex: Int, toIndex: Int) {
        val current = _state.value
        val currentHashes = current.imageHashes.toMutableList()
        if (fromIndex !in currentHashes.indices || toIndex !in currentHashes.indices) return
        if (fromIndex == toIndex) return
        val moved = currentHashes.removeAt(fromIndex)
        currentHashes.add(toIndex, moved)
        _state.value = current.copy(imageHashes = currentHashes)
    }

    fun updateName(name: String) {
        _state.value = _state.value.copy(name = name)
    }

    fun updateBarcode(barcode: String?) {
        _state.value = _state.value.copy(barcode = barcode?.takeIf { it.isNotBlank() })
    }

    fun updateCategory(categoryId: Long?) {
        _state.value = _state.value.copy(categoryId = categoryId)
    }

    fun updateLocation(locationId: Long?) {
        _state.value = _state.value.copy(locationId = locationId)
    }

    fun updateQuantity(quantity: Int) {
        _state.value = _state.value.copy(quantity = quantity.coerceAtLeast(1))
    }

    fun updateUnit(unit: String) {
        _state.value = _state.value.copy(unit = unit.trim().ifBlank { "件" })
    }

    fun updatePrice(price: String) {
        _state.value = _state.value.copy(price = price)
    }

    fun updateExpireTime(time: Long?) {
        _state.value = _state.value.copy(expireTime = time)
    }

    /** 更新该物品单独的提醒阶梯；传空列表即恢复「跟随默认」。 */
    fun updateReminderDays(days: List<Int>) {
        _state.value = _state.value.copy(reminderDays = days.distinct().sorted())
    }

    fun updatePurchaseDate(time: Long?) {
        _state.value = _state.value.copy(purchaseDate = time)
    }

    fun updateProductionDate(time: Long?) {
        _state.value = _state.value.copy(productionDate = time)
    }

    /** 补录/修改开始使用时间；清空表示还没开始用。 */
    fun updateStartUseTime(time: Long?) {
        _state.value = _state.value.copy(startUseTime = time)
    }

    fun updateTrackMode(mode: Int) {
        _state.value = _state.value.copy(trackMode = mode)
    }

    fun updateNote(note: String) {
        _state.value = _state.value.copy(note = note)
    }

    fun save() {
        val current = _state.value
        // 校验前先 trim：全是空格的名字不算「填了」，也不会被当成长度有效存进库。
        val trimmedName = current.name.trim()
        if (trimmedName.isBlank() || current.isSaving) return

        _state.value = current.copy(isSaving = true)
        viewModelScope.launch {
            val original = itemRepository.getItemById(current.itemId)
            // 去重时同一张图（同一哈希）只留一次，顺序保持不变。
            val normalizedHashes = current.imageHashes.distinct()
            val item = Item(
                id = current.itemId,
                name = trimmedName,
                barcode = current.barcode,
                categoryId = current.categoryId,
                locationId = current.locationId,
                quantity = current.quantity,
                consumedQuantity = original?.consumedQuantity ?: 0,
                unit = current.unit,
                price = current.price.toDoubleOrNull(),
                expireTime = current.expireTime,
                reminderDays = Item.encodeReminderDays(current.reminderDays),
                purchaseDate = current.purchaseDate,
                productionDate = current.productionDate,
                // 只有持续使用型才有「开始使用/使用天数」的概念，按件消耗型清空该字段。
                startUseTime = if (current.trackMode == Item.TRACK_DURABLE) {
                    current.startUseTime
                } else {
                    null
                },
                usageEndedAt = original?.usageEndedAt,
                trackMode = current.trackMode,
                usageStatus = current.usageStatus,
                disposition = current.disposition,
                needRestock = current.needRestock,
                rating = current.rating,
                ratedAt = current.ratedAt,
                deletedAt = original?.deletedAt,
                note = current.note.trim(),
                imageHashes = Item.encodeImageHashes(normalizedHashes),
                createdAt = original?.createdAt ?: System.currentTimeMillis(),
                syncId = current.syncId ?: original?.syncId
            )
            itemRepository.update(item)
            _state.value = _state.value.copy(isSaving = false, isSaved = true)
            // 编辑时可能刚补了照片，同样给一次上传机会（幂等：已传过的会被跳过）。
            imageCloudSync.syncItem(current.itemId)
        }
    }
}
