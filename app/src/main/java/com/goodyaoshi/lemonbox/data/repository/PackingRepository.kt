package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.dao.PackingItemDao
import com.goodyaoshi.lemonbox.data.local.dao.PackingTripDao
import com.goodyaoshi.lemonbox.data.local.entity.PackingItem
import com.goodyaoshi.lemonbox.data.local.entity.PackingTrip
import com.goodyaoshi.lemonbox.util.MonotonicClock
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 旅行打包清单仓库（F16）：行程与打包条目的统一入口。
 * 删除行程时连带软删除其下条目，避免脏数据残留。
 */
@Singleton
class PackingRepository @Inject constructor(
    private val tripDao: PackingTripDao,
    private val itemDao: PackingItemDao
) {

    // ---------- 行程 ----------

    fun observeTrips(): Flow<List<PackingTrip>> = tripDao.observeAll()

    fun getTripFlow(id: Long): Flow<PackingTrip?> = tripDao.getByIdFlow(id)

    /** 新建行程；返回新 id。 */
    suspend fun createTrip(trip: PackingTrip): Long = tripDao.insert(
        trip.copy(
            id = 0,
            syncId = trip.syncId ?: UUID.randomUUID().toString(),
            createdAt = trip.createdAt.takeIf { it > 0 } ?: MonotonicClock.now(),
            updatedAt = MonotonicClock.now(),
            dirty = true
        )
    )

    /** 删除行程：连带其下条目一并软删除。 */
    suspend fun deleteTrip(id: Long) {
        val now = MonotonicClock.now()
        itemDao.softDeleteByTrip(tripId = id, deletedAt = now, updatedAt = now)
        tripDao.softDelete(id = id, deletedAt = now, updatedAt = now)
    }

    // ---------- 条目 ----------

    fun observeItems(tripId: Long): Flow<List<PackingItem>> = itemDao.observeByTrip(tripId)

    /** 全部未删除条目（列表页聚合各行程进度用）。 */
    fun observeAllActiveItems(): Flow<List<PackingItem>> = itemDao.observeAllActive()

    suspend fun addItem(item: PackingItem): Long = itemDao.insert(
        item.copy(
            id = 0,
            syncId = item.syncId ?: UUID.randomUUID().toString(),
            createdAt = item.createdAt.takeIf { it > 0 } ?: MonotonicClock.now(),
            updatedAt = MonotonicClock.now(),
            dirty = true
        )
    )

    /** 勾选 / 取消勾选。 */
    suspend fun setChecked(item: PackingItem, checked: Boolean) {
        if (item.checked == checked) return
        itemDao.update(item.copy(checked = checked, updatedAt = MonotonicClock.now(), dirty = true))
    }

    suspend fun deleteItem(item: PackingItem) {
        val now = MonotonicClock.now()
        itemDao.softDelete(id = item.id, deletedAt = now, updatedAt = now)
    }

    /** 撤销删除（GAP-13）：清掉墓碑让条目复活，与待买/家当的删除撤销同范式。 */
    suspend fun restoreItem(item: PackingItem) {
        itemDao.update(item.copy(deletedAt = null, updatedAt = MonotonicClock.now(), dirty = true))
    }

    suspend fun purgeDeletedOlderThan(cutoff: Long) {
        tripDao.purgeDeletedOlderThan(cutoff)
        itemDao.purgeDeletedOlderThan(cutoff)
    }

    // ---- 备份/同步 ----

    suspend fun getAllTripsSnapshot(): List<PackingTrip> = tripDao.getAllSnapshot()

    suspend fun getAllItemsSnapshot(): List<PackingItem> = itemDao.getAllSnapshot()

    suspend fun insertSyncedTrip(trip: PackingTrip): Long = tripDao.insert(trip.copy(dirty = false))

    suspend fun updateSyncedTrip(trip: PackingTrip) = tripDao.update(trip.copy(dirty = false))

    suspend fun insertSyncedItem(item: PackingItem): Long = itemDao.insert(item.copy(dirty = false))

    suspend fun updateSyncedItem(item: PackingItem) = itemDao.update(item.copy(dirty = false))
}
