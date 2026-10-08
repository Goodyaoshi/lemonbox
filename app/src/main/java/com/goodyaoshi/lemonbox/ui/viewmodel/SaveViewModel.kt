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
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.ImageUtil
import com.goodyaoshi.lemonbox.util.MonotonicClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SaveItemState(
    val name: String = "",
    val barcode: String? = null,
    val categoryId: Long? = null,
    val locationId: Long? = null,
    val quantity: Int = 1,
    val unit: String = "件",
    val price: String = "",
    val expireTime: Long? = null,
    val note: String = "",
    val imagePaths: List<String> = emptyList(),
    /** 该物品单独的到期提醒阶梯（天）；为空表示跟随全局默认阶梯。 */
    val reminderDays: List<Int> = emptyList(),
    /** 购买日期，默认今天，可改可清空。 */
    val purchaseDate: Long? = DateUtil.daysFromNow(0),
    /** 开始使用时间；持续使用物品可在录入时自定义，为空则默认购买日期。 */
    val startUseTime: Long? = null,
    /** 计量方式：按件消耗 / 持续使用（耐用品）。 */
    val trackMode: Int = Item.TRACK_CONSUMABLE,
    /** 用户是否手动改过计量方式；改过后不再跟随分类智能默认。 */
    val trackModeTouched: Boolean = false,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false
)

@HiltViewModel
class SaveViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val categoryRepository: CategoryRepository,
    private val locationRepository: LocationRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(SaveItemState())
    val state: StateFlow<SaveItemState> = _state.asStateFlow()

    /** 连续录入开关，持久化在偏好设置里。 */
    val continuousEntry: StateFlow<Boolean> = appPreferences.continuousEntry

    fun toggleContinuousEntry(enabled: Boolean) = appPreferences.setContinuousEntry(enabled)

    /** 全局默认的提醒阶梯，用于展示「跟随默认」的说明文案。 */
    val defaultReminderLadder: StateFlow<List<Int>> = appPreferences.reminderLadder

    val categories: StateFlow<List<Category>> = categoryRepository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locations: StateFlow<List<Location>> = locationRepository.getAllLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun initFromPhoto(context: Context, imageUri: Uri?) {
        val current = _state.value
        if (imageUri == null || current.imagePaths.isNotEmpty()) return

        val savedPath = ImageUtil.saveImageToInternal(context, imageUri)
        _state.value = current.copy(imagePaths = savedPath?.let(::listOf).orEmpty())
    }

    fun initFromPhotos(context: Context, imageUris: List<Uri>) {
        val current = _state.value
        if (imageUris.isEmpty() || current.imagePaths.isNotEmpty()) return

        val savedPaths = imageUris.mapNotNull { ImageUtil.saveImageToInternal(context, it) }.distinct()
        _state.value = current.copy(imagePaths = savedPaths)
    }

    fun appendPhotos(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val savedPaths = uris.mapNotNull { ImageUtil.saveImageToInternal(context, it) }
        if (savedPaths.isEmpty()) return
        _state.value = _state.value.copy(
            imagePaths = (_state.value.imagePaths + savedPaths).distinct()
        )
    }

    fun replacePrimaryPhoto(context: Context, uri: Uri) {
        val savedPath = ImageUtil.saveImageToInternal(context, uri) ?: return
        val currentPaths = _state.value.imagePaths.toMutableList()
        currentPaths.firstOrNull()?.let(ImageUtil::deleteImage)
        if (currentPaths.isEmpty()) {
            currentPaths += savedPath
        } else {
            currentPaths[0] = savedPath
        }
        _state.value = _state.value.copy(imagePaths = currentPaths.distinct())
    }

    fun removePhoto(index: Int) {
        val currentPaths = _state.value.imagePaths.toMutableList()
        val removed = currentPaths.getOrNull(index) ?: return
        currentPaths.removeAt(index)
        ImageUtil.deleteImage(removed)
        _state.value = _state.value.copy(imagePaths = currentPaths)
    }

    fun movePhoto(fromIndex: Int, toIndex: Int) {
        val currentPaths = _state.value.imagePaths.toMutableList()
        if (fromIndex !in currentPaths.indices || toIndex !in currentPaths.indices || fromIndex == toIndex) return
        val moved = currentPaths.removeAt(fromIndex)
        currentPaths.add(toIndex, moved)
        _state.value = _state.value.copy(imagePaths = currentPaths)
    }

    fun updateName(name: String) {
        _state.value = _state.value.copy(name = name)
    }

    fun updateBarcode(barcode: String?) {
        _state.value = _state.value.copy(barcode = barcode?.trim()?.takeIf { it.isNotBlank() })
    }

    fun updateCategory(categoryId: Long?) {
        val current = _state.value
        _state.value = if (current.trackModeTouched) {
            current.copy(categoryId = categoryId)
        } else {
            // 未手动选过计量方式时跟随分类智能默认（家电、数码等耐用品分类预选「持续使用」）。
            val durable = Item.durableTopCategoryNames.contains(topCategoryName(categoryId))
            current.copy(
                categoryId = categoryId,
                trackMode = if (durable) Item.TRACK_DURABLE else Item.TRACK_CONSUMABLE
            )
        }
    }

    fun updateLocation(locationId: Long?) {
        _state.value = _state.value.copy(locationId = locationId)
    }

    fun updateQuantity(quantity: Int) {
        _state.value = _state.value.copy(quantity = quantity.coerceAtLeast(1))
    }

    fun updateUnit(unit: String) {
        _state.value = _state.value.copy(unit = unit)
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

    /** 持续使用物品的开始使用时间；清空后保存时回退为购买日期。 */
    fun updateStartUseTime(time: Long?) {
        _state.value = _state.value.copy(startUseTime = time)
    }

    /** 手动切换计量方式，之后不再跟随分类智能默认。 */
    fun updateTrackMode(mode: Int) {
        _state.value = _state.value.copy(
            trackMode = mode,
            trackModeTouched = true
        )
    }

    /** 沿分类树上溯到顶级分类，取其名称用于耐用品智能默认判断。 */
    private fun topCategoryName(categoryId: Long?): String? {
        if (categoryId == null) return null
        val byId = categories.value.associateBy { it.id }
        var node = byId[categoryId]
        var guard = 0
        while (node?.parentId != null && guard++ < 10) {
            node = byId[node.parentId]
        }
        return node?.name
    }

    fun updateNote(note: String) {
        _state.value = _state.value.copy(note = note)
    }

    fun save() {
        val current = _state.value
        if (current.name.isBlank() || current.isSaving) return

        _state.value = current.copy(isSaving = true)
        viewModelScope.launch {
            val now = MonotonicClock.now()
            val normalizedImages = current.imagePaths.distinct()
            val item = Item(
                name = current.name,
                barcode = current.barcode,
                categoryId = current.categoryId,
                locationId = current.locationId,
                quantity = current.quantity,
                unit = current.unit,
                price = current.price.toDoubleOrNull(),
                expireTime = current.expireTime,
                note = current.note,
                imagePath = normalizedImages.firstOrNull().orEmpty(),
                imagePaths = Item.encodeImagePaths(normalizedImages),
                reminderDays = Item.encodeReminderDays(current.reminderDays),
                purchaseDate = current.purchaseDate,
                trackMode = current.trackMode,
                // 持续使用物品：优先用录入时选定的开始使用时间，否则默认购买日期；
                // 按件消耗物品：开始使用时间即录入时刻，使用周期从此起算。
                startUseTime = if (current.trackMode == Item.TRACK_DURABLE) {
                    current.startUseTime ?: current.purchaseDate ?: now
                } else {
                    now
                }
            )
            itemRepository.insert(item)
            _state.value = _state.value.copy(isSaving = false, isSaved = true)
        }
    }

    fun reset() {
        _state.value = SaveItemState()
    }
}