package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.PackingItem
import com.goodyaoshi.lemonbox.data.local.entity.PackingTrip
import com.goodyaoshi.lemonbox.data.repository.PackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 行程列表行：附带打包进度，列表上直接能看到「已装 3/8」。 */
data class PackingTripRow(
    val trip: PackingTrip,
    val totalCount: Int,
    val checkedCount: Int
)

/**
 * 删除行程的撤销快照：行程本身 + 本次连带软删的条目，撤销时一起复活。
 *
 * [items] 只装删除**之前**还在的条目——这样撤销只会把「跟着行程一起没的」
 * 那批救回来，不会把用户早先自己删掉的条目也顺带翻出来。
 */
data class PackingTripUndo(
    val trip: PackingTrip,
    val items: List<PackingItem>
)

/**
 * 旅行打包清单列表（F16）：所有未删除行程 + 各自打包进度。
 * 进度用条目流在内存里聚合，避免为每个行程单独查一次数据库。
 */
@HiltViewModel
class PackingListViewModel @Inject constructor(
    private val repository: PackingRepository
) : ViewModel() {

    val rows: StateFlow<List<PackingTripRow>> = combine(
        repository.observeTrips(),
        repository.observeAllActiveItems()
    ) { trips, items ->
        val byTrip = items.groupBy { it.tripId }
        trips.map { trip ->
            val tripItems = byTrip[trip.id].orEmpty()
            PackingTripRow(
                trip = trip,
                totalCount = tripItems.size,
                checkedCount = tripItems.count { it.checked }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 新建行程；[onCreated] 拿到新 id，可直接进入清单页。 */
    fun createTrip(
        name: String,
        destination: String,
        startDate: String?,
        endDate: String?,
        onCreated: (Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            val id = repository.createTrip(
                PackingTrip(
                    name = name.trim(),
                    destination = destination.trim(),
                    startDate = startDate,
                    endDate = endDate
                )
            )
            onCreated(id)
        }
    }

    /**
     * 删除行程（软删入回收站语义），并把撤销所需的快照回调出去。
     *
     * [onDeleted] 在删除完成后回调（主线程），带着快照供界面挂「已删除，可撤销」提示条。
     * 行程已经不存在时回传 null，界面据此不弹提示条。
     */
    fun deleteTrip(id: Long, onDeleted: (PackingTripUndo?) -> Unit = {}) {
        viewModelScope.launch {
            val trip = repository.getTrip(id)
            if (trip == null) {
                onDeleted(null)
                return@launch
            }
            val items = repository.getActiveItemsByTrip(id)
            repository.deleteTrip(id)
            onDeleted(PackingTripUndo(trip, items))
        }
    }

    /** 撤销删除行程：按快照把行程与连带删掉的条目一起复活。 */
    fun restoreTrip(undo: PackingTripUndo) {
        viewModelScope.launch { repository.restoreTrip(undo.trip, undo.items) }
    }
}
