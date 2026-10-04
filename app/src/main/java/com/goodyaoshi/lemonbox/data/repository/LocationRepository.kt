package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.dao.LocationDao
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.util.MonotonicClock
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationRepository @Inject constructor(
    private val locationDao: LocationDao
) {
    fun getAllLocations(): Flow<List<Location>> = locationDao.getAllLocations()

    fun getRootLocations(): Flow<List<Location>> = locationDao.getRootLocations()

    fun getSubLocations(parentId: Long): Flow<List<Location>> = locationDao.getSubLocations(parentId)

    suspend fun getLocationById(id: Long): Location? = locationDao.getLocationById(id)

    suspend fun getAllLocationsSnapshot(): List<Location> = locationDao.getAllLocationsSnapshot()

    /** 取某个位置及其全部下级位置的 id（不含墓碑）。 */
    suspend fun getSubtreeIds(rootId: Long): List<Long> {
        val all = locationDao.getAllLocationsSnapshot().filter { it.deletedAt == null }
        return collectDescendantIds(rootId, all) + rootId
    }

    suspend fun insert(location: Location): Long = locationDao.insert(location)

    suspend fun update(location: Location) {
        // 内置保护位置（冰箱）是「今天吃什么」取材规则的基础，不允许改写。
        if (location.isProtected) return
        locationDao.update(location.copy(updatedAt = MonotonicClock.now()))
    }

    /**
     * 软删除（写墓碑），并连带其所有下级位置，保持与旧版 CASCADE 删除一致的效果。
     * 记录会保留以便把删除同步给其他设备。受保护位置直接跳过。
     */
    suspend fun delete(location: Location) {
        if (location.isProtected) return
        val now = MonotonicClock.now()
        val all = locationDao.getAllLocationsSnapshot()
        val targets = collectDescendantIds(location.id, all) + location.id
        targets.forEach { id ->
            all.firstOrNull { it.id == id }?.let { entity ->
                locationDao.update(entity.copy(deletedAt = now, updatedAt = now))
            }
        }
    }

    private fun collectDescendantIds(rootId: Long, all: List<Location>): List<Long> {
        val result = mutableListOf<Long>()
        val pending = ArrayDeque<Long>().apply { add(rootId) }
        while (pending.isNotEmpty()) {
            val parentId = pending.removeFirst()
            all.filter { it.parentId == parentId }.forEach { child ->
                if (result.add(child.id)) {
                    pending.add(child.id)
                }
            }
        }
        return result
    }

    /** 合并导入专用：保留备份中的 id 与 updatedAt。 */
    suspend fun insertSynced(location: Location): Long = locationDao.insert(location)

    suspend fun updateSynced(location: Location) = locationDao.update(location)
}
