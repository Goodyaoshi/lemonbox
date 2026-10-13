package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LocationRepository
import com.goodyaoshi.lemonbox.data.search.SearchEngine
import com.goodyaoshi.lemonbox.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val itemRepository: ItemRepository,
    categoryRepository: CategoryRepository,
    locationRepository: LocationRepository
) : ViewModel() {

    private val loading = LoadingState()

    /**
     * 导航 scope（F20）：详情页的横向滑动需要同 scope 的物品列表，
     * 常用 scope（全部 / 待买 / 未使用 / 已用完）直接下沉为对应的 DAO 查询，
     * 不再全量拉表后内存筛；其余 scope 语义无对应 SQL，回退全量由页面过滤。
     */
    private val scope: String = savedStateHandle.get<String>("scope") ?: Screen.Detail.ScopeAll

    /** 手动重试触发器：自增即让 [items] 重新订阅上游（P2，配合 [ErrorState] 使用）。 */
    private val refreshTrigger = MutableStateFlow(0)

    /** 首帧加载标记：物品详情读完之前显示骨架屏，避免「假空态」（见 [LoadingState]）。 */
    val isLoading: StateFlow<Boolean> = loading.isLoading

    /** 读取失败时的错误文案；为 null 表示正常（P2）。 */
    val error: StateFlow<String?> = loading.error

    /** 分类/存放位置的全量列表，用于把详情页的扁平名称还原成完整层级路径。 */
    val categories: StateFlow<List<Category>> = categoryRepository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locations: StateFlow<List<Location>> = locationRepository.getAllLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 从家当页点入时随路由携带的完整筛选条件（状态 / 分类 / 位置 / 关键词）。
     * 为 null 表示不是从家当页进来、或参数无法解析，此时沿用按 [scope] 的旧路径。
     */
    private val entryFilter: LibraryFilter? =
        decodeLibraryFilter(savedStateHandle.get<String>("filterJson"))
    private val query: String = savedStateHandle.get<String>("query").orEmpty()
    private val searchEngine = SearchEngine()

    val items: StateFlow<List<ItemDetail>> = refreshTrigger
        .flatMapLatest {
            filteredItems()
                .onEach { loading.markLoaded() }
                .catch { loading.markError(it.message) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 详情页左右翻页所依据的同组物品。
     *
     * 家当入口带着完整筛选条件进来，这里就用与家当列表同一套 [SearchEngine] 复现来源集合：
     * [categories]/[locations] 必须参与分类与位置的「含子孙」展开，所以用 combine 等它们就绪。
     * 其余入口只按 [scope] 取数，语义与本改动之前逐字一致。
     */
    private fun filteredItems(): Flow<List<ItemDetail>> {
        val filter = entryFilter ?: return scopedItems()
        return combine(itemRepository.getAllItems(), categories, locations) { all, cats, locs ->
            searchEngine.filter(all, locs, filter.toSearchCriteria(query, cats, locs))
        }
    }

    private fun scopedItems(): Flow<List<ItemDetail>> = when (scope) {
        Screen.Detail.ScopeAll -> itemRepository.getAllItems()
        Screen.Detail.ScopeToBuy -> itemRepository.getToBuyItems()
        Screen.Detail.ScopeUnused -> itemRepository.getItemsByUsageStatus(Item.USAGE_UNUSED)
        Screen.Detail.ScopeUsedUp -> itemRepository.getItemsByUsageStatus(Item.USAGE_USED_UP)
        else -> itemRepository.getAllItems()
    }

    /** 重试：复位加载态并重新订阅上游（P2，供错误态上的「重试」按钮调用）。 */
    fun retry() {
        loading.beginRetry()
        refreshTrigger.value++
    }

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
