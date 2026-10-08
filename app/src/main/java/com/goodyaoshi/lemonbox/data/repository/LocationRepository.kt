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

    /** 同一父级下是否已存在同名位置（忽略大小写），供新增去重。 */
    suspend fun existsWithName(name: String, parentId: Long?): Boolean =
        locationDao.countByNameAndParent(name.trim(), parentId) > 0

    /**
     * 清理同名同父级路径的重复位置。
     *
     * 根因：两台设备各自种子化了「家/厨房/冰箱」等默认位置，syncId 各不相同；
     * 备份导入/局域网同步按 syncId 对齐，识别不出它们本是同一条，于是各留一份。
     * 手动新增位置时重复输入也会留下同名项。
     *
     * 处理方式：按「完整路径名称」分组（祖先同时重复时路径名仍能对齐），
     * 每组保留一条（优先受保护，其次 id 最小），把下级位置与物品改挂到保留项后删除多余项。
     * 返回被清理的重复条数。
     */
    suspend fun deduplicateLocations(): Int {
        var removed = 0
        // 祖先可能同时重复，清理后子孙才归并到一起，故循环到无重复为止（有界防意外）。
        repeat(4) {
            val active = locationDao.getAllLocationsSnapshot().filter { it.deletedAt == null }
            val byId = active.associateBy { it.id }
            val groups = active.groupBy { locationPathKey(it, byId) }
            val targets = groups.values.filter { it.size > 1 }
            if (targets.isEmpty()) return removed
            targets.forEach { group ->
                val keeper = group.firstOrNull { it.isProtected } ?: group.minByOrNull { it.id }!!
                group.forEach { dup ->
                    if (dup.id == keeper.id) return@forEach
                    // 必须先改挂子级与物品，再删除：parentId 外键为 CASCADE，直接删会连带删掉子级。
                    locationDao.reassignChildren(fromId = dup.id, toId = keeper.id)
                    locationDao.reassignItemsToLocation(fromId = dup.id, toId = keeper.id)
                    locationDao.deleteById(dup.id)
                    removed++
                }
            }
        }
        return removed
    }

    /** 沿 parentId 上溯拼接「家/厨房/冰箱」路径（按名称、忽略大小写），用于识别同一条位置。 */
    private fun locationPathKey(location: Location, byId: Map<Long, Location>): String {
        val parts = mutableListOf<String>()
        var cursor: Location? = location
        var guard = 0
        while (cursor != null && guard++ < 64) {
            parts += cursor.name.trim().lowercase()
            cursor = cursor.parentId?.let { byId[it] }
        }
        return parts.reversed().joinToString("/")
    }

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
