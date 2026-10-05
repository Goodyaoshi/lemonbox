package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.dao.AnniversaryDao
import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 纪念日的数据入口：普通写入盖 syncId/updatedAt；同步导入走 insertSynced/updateSynced
 * 原样保留备份里的时间戳，保证 LWW 合并不被打乱。
 */
@Singleton
class AnniversaryRepository @Inject constructor(
    private val anniversaryDao: AnniversaryDao
) {
    fun getActiveAnniversaries(): Flow<List<Anniversary>> =
        anniversaryDao.getActiveAnniversaries()

    /** 全部未删除纪念日（列表页展示，含已停用）。 */
    fun getAllAnniversaries(): Flow<List<Anniversary>> = anniversaryDao.getAllAnniversaries()

    fun observeCount(): Flow<Int> = anniversaryDao.observeCount()

    suspend fun getById(id: Long): Anniversary? = anniversaryDao.getById(id)

    /** 编辑页载入（未删除）。 */
    fun getByIdFlow(id: Long): Flow<Anniversary?> = anniversaryDao.getByIdFlow(id)

    suspend fun create(anniversary: Anniversary): Long {
        val now = System.currentTimeMillis()
        return anniversaryDao.insert(
            anniversary.copy(
                id = 0,
                syncId = anniversary.syncId ?: UUID.randomUUID().toString(),
                createdAt = anniversary.createdAt.takeIf { it > 0 } ?: now,
                updatedAt = now
            )
        )
    }

    suspend fun update(anniversary: Anniversary) {
        anniversaryDao.update(
            anniversary.copy(
                syncId = anniversary.syncId ?: UUID.randomUUID().toString(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setEnabled(anniversary: Anniversary, enabled: Boolean) {
        if (enabled == anniversary.enabled) return
        anniversaryDao.update(
            anniversary.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
        )
    }

    /** 通知去重标记：设备本地状态，不动 updatedAt，避免把对方没发过的通知也按 LWW 顶掉。 */
    suspend fun markNotified(id: Long, dateKey: String) {
        val current = anniversaryDao.getById(id) ?: return
        if (current.lastNotifiedDate == dateKey) return
        anniversaryDao.update(current.copy(lastNotifiedDate = dateKey))
    }

    /** 软删除：墓碑随备份/同步传递，30 天后由启动清理物理清除。 */
    suspend fun softDelete(anniversary: Anniversary) {
        val now = System.currentTimeMillis()
        anniversaryDao.softDelete(id = anniversary.id, deletedAt = now, updatedAt = now)
    }

    suspend fun purgeDeletedOlderThan(cutoff: Long) {
        anniversaryDao.purgeDeletedOlderThan(cutoff)
    }

    // ---- 备份/同步 ----

    suspend fun getAllSnapshot(): List<Anniversary> = anniversaryDao.getAllSnapshot()

    /** 活跃快照（后台纪念日检查 Worker 用）。 */
    suspend fun getActiveSnapshot(): List<Anniversary> = anniversaryDao.getActiveSnapshot()

    suspend fun insertSynced(anniversary: Anniversary): Long = anniversaryDao.insert(anniversary)

    suspend fun updateSynced(anniversary: Anniversary) = anniversaryDao.update(anniversary)
}
