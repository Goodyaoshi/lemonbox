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
    val quantity: Double = 1.0,
    val unit: String = "件",
    val price: String = "",
    val expireTime: Long? = null,
    val note: String = "",
    /** 图片槽位的内容哈希，顺序即展示顺序（首项为主图）。 */
    val imageHashes: List<String> = emptyList(),
    /** 该物品单独的到期提醒阶梯（天）；为空表示跟随全局默认阶梯。 */
    val reminderDays: List<Int> = emptyList(),
    /** 购买日期，可选；未填为 null。 */
    val purchaseDate: Long? = null,
    /** 生产日期，可选；未填为 null。 */
    val productionDate: Long? = null,
    /** 开始使用时间；持续使用物品可在录入时自定义，为空则默认购买日期。 */
    val startUseTime: Long? = null,
    /** 计量方式：按件消耗 / 持续使用（耐用品）。 */
    val trackMode: Int = Item.TRACK_CONSUMABLE,
    /** 用户是否手动改过计量方式；改过后不再跟随分类智能默认。 */
    val trackModeTouched: Boolean = false,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    /** 写库失败时的就地提示（G-8）：显示在保存键上方，表单内容原样保留可重试。 */
    val errorMessage: String? = null
)

@HiltViewModel
class SaveViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val categoryRepository: CategoryRepository,
    private val locationRepository: LocationRepository,
    private val appPreferences: AppPreferences,
    // 保存成功后把该物品的图片后台送上云（F20）。开关关闭或未配置时内部静默跳过。
    private val imageCloudSync: ImageCloudSyncManager
) : ViewModel() {

    private val _state = MutableStateFlow(SaveItemState())
    val state: StateFlow<SaveItemState> = _state.asStateFlow()

    /** 刚存进去的那件物品的 id（G-8）： Snackbar 撤销时按它移入回收站。 */
    private var savedItemId: Long? = null

    /** 连续录入开关，持久化在偏好设置里。 */
    val continuousEntry: StateFlow<Boolean> = appPreferences.continuousEntry

    fun toggleContinuousEntry(enabled: Boolean) = appPreferences.setContinuousEntry(enabled)

    /** 「更多信息」展开态：默认折叠，用户展开后跨会话记住其选择（I6）。 */
    val advancedExpanded: StateFlow<Boolean> = appPreferences.editorAdvancedExpanded

    fun setAdvancedExpanded(expanded: Boolean) = appPreferences.setEditorAdvancedExpanded(expanded)

    /** 全局默认的提醒阶梯，用于展示「跟随默认」的说明文案。 */
    val defaultReminderLadder: StateFlow<List<Int>> = appPreferences.reminderLadder

    /** 有效期快捷档位（编码列表，形如 7d/3m），可在设置页自定义。 */
    val expiryQuickOptions: StateFlow<List<String>> = appPreferences.expiryQuickOptions

    val categories: StateFlow<List<Category>> = categoryRepository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locations: StateFlow<List<Location>> = locationRepository.getAllLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun initFromPhoto(context: Context, imageUri: Uri?) {
        val current = _state.value
        if (imageUri == null || current.imageHashes.isNotEmpty()) return

        val hash = ImageUtil.saveImageHash(context, imageUri)
        _state.value = current.copy(imageHashes = hash?.let(::listOf).orEmpty())
    }

    fun initFromPhotos(context: Context, imageUris: List<Uri>) {
        val current = _state.value
        if (imageUris.isEmpty() || current.imageHashes.isNotEmpty()) return

        val hashes = imageUris.mapNotNull { ImageUtil.saveImageHash(context, it) }.distinct()
        _state.value = current.copy(imageHashes = hashes)
    }

    fun appendPhotos(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val hashes = uris.mapNotNull { ImageUtil.saveImageHash(context, it) }
        if (hashes.isEmpty()) return
        _state.value = _state.value.copy(
            imageHashes = (_state.value.imageHashes + hashes).distinct()
        )
    }

    fun replacePrimaryPhoto(context: Context, uris: List<Uri>) {
        val hashes = uris.mapNotNull { ImageUtil.saveImageHash(context, it) }
        if (hashes.isEmpty()) return
        val currentHashes = _state.value.imageHashes.toMutableList()
        // 先落盘新图（上面已完成）再删旧图：同内容时新图会复用同一文件，顺序反了会误删。
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
        _state.value = _state.value.copy(imageHashes = currentHashes.distinct())
    }

    fun removePhoto(context: Context, index: Int) {
        val currentHashes = _state.value.imageHashes.toMutableList()
        val removed = currentHashes.getOrNull(index) ?: return
        currentHashes.removeAt(index)
        // 若不再被任何槽位引用，就把本地文件删掉——它是上传前的副本，留着只是垃圾。
        if (removed !in currentHashes) {
            ImageUtil.findLocalFile(context, removed)?.let { ImageUtil.deleteImage(it.absolutePath) }
        }
        _state.value = _state.value.copy(imageHashes = currentHashes)
    }

    fun movePhoto(fromIndex: Int, toIndex: Int) {
        val currentHashes = _state.value.imageHashes.toMutableList()
        if (fromIndex !in currentHashes.indices || toIndex !in currentHashes.indices) return
        if (fromIndex == toIndex) return
        val moved = currentHashes.removeAt(fromIndex)
        currentHashes.add(toIndex, moved)
        _state.value = _state.value.copy(imageHashes = currentHashes)
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

    fun updateQuantity(quantity: Double) {
        // 新建物品允许 0.5 这类小数（半袋、半斤），下限只挡负数。
        _state.value = _state.value.copy(quantity = quantity.coerceAtLeast(0.0))
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

    fun updateProductionDate(time: Long?) {
        _state.value = _state.value.copy(productionDate = time)
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
        // 校验前先 trim：全是空格的名字不算「填了」，也不会被当成长度有效存进库。
        val trimmedName = current.name.trim()
        if (trimmedName.isBlank() || current.isSaving) return

        _state.value = current.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            val now = MonotonicClock.now()
            val normalizedImages = current.imageHashes.distinct()
            val item = Item(
                name = trimmedName,
                barcode = current.barcode,
                categoryId = current.categoryId,
                locationId = current.locationId,
                quantity = current.quantity,
                unit = current.unit,
                price = current.price.toDoubleOrNull(),
                expireTime = current.expireTime,
                note = current.note.trim(),
                imageHashes = Item.encodeImageHashes(normalizedImages),
                reminderDays = Item.encodeReminderDays(current.reminderDays),
                purchaseDate = current.purchaseDate,
                productionDate = current.productionDate,
                trackMode = current.trackMode,
                // 刚录入的物品还没开始用：按件消耗默认「未使用」，持续使用默认「使用中」。
                usageStatus = Item.defaultUsageStatusForNewItem(current.trackMode),
                // 持续使用物品：优先用录入时选定的开始使用时间，否则默认购买日期；
                // 按件消耗物品没有「使用天数」概念，开始使用时间留空。
                startUseTime = if (current.trackMode == Item.TRACK_DURABLE) {
                    current.startUseTime ?: current.purchaseDate ?: now
                } else {
                    null
                }
            )
            // 写库失败不再让表单卡死在「保存中...」（G-8）：就地报错、内容保留、可重试。
            try {
                savedItemId = itemRepository.insert(item)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSaving = false, errorMessage = "没存上，再试一次吧")
                return@launch
            }
            _state.value = _state.value.copy(isSaving = false, isSaved = true)
            // 落库成功后再上传：此刻物品已在库里，同步器能按 id 读到它的图片。
            // 该调用不阻塞、不抛异常、失败静默——云端同步不该干扰「存好了」这件事本身。
            savedItemId?.let(imageCloudSync::syncItem)
        }
    }

    /** 撤销刚保存的那一件（G-8）：软删进回收站，误存的东西不用再跑去家当里删。 */
    fun undoSave() {
        val id = savedItemId ?: return
        savedItemId = null
        viewModelScope.launch {
            itemRepository.getItemById(id)?.let { itemRepository.moveToTrash(it) }
        }
    }

    /** 收起保存失败的就地提示（G-8）。 */
    fun clearError() {
        if (_state.value.errorMessage != null) {
            _state.value = _state.value.copy(errorMessage = null)
        }
    }

    fun reset() {
        // 不清 savedItemId：Snackbar 里的「撤销」晚于 reset 触发，得留着 id 才能撤掉刚存的那件；
        // 下一次保存会自然覆盖它，且本 VM 随页面销毁，不存在串页风险。
        _state.value = SaveItemState()
    }
}