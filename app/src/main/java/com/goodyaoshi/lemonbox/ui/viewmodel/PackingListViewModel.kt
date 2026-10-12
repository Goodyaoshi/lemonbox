package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    fun deleteTrip(id: Long) {
        viewModelScope.launch { repository.deleteTrip(id) }
    }
}
