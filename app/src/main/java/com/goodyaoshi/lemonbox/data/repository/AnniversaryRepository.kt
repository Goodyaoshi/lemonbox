package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.dao.AnniversaryDao
import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 纪念日保存结果：成功，或与已有同「名称+日期+类型」的有效纪念日重复被守卫拦截。 */
enum class AnniversarySaveResult { SAVED, DUPLICATE_NAME }

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

    /**
     * 新增或更新纪念日；与已有有效纪念日同「名称+日期+类型」（忽略大小写与首尾空格）时
     * 拒绝并返回 [AnniversarySaveResult.DUPLICATE_NAME]，避免手动录入产生重复项。
     */
    suspend fun save(anniversary: Anniversary): AnniversarySaveResult {
        val normalizedName = anniversary.name.trim()
        if (anniversaryDao.countActiveByNameDateType(
                name = normalizedName,
                date = anniversary.date,
                type = anniversary.type,
                excludeId = anniversary.id
            ) > 0
        ) {
            return AnniversarySaveResult.DUPLICATE_NAME
        }
        val target = anniversary.copy(name = normalizedName)
        if (target.id == 0L) create(target) else update(target)
        return AnniversarySaveResult.SAVED
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

    /**
     * 清理同「名称+日期+类型」的重复纪念日（重复录入/旧版合并留下的）。
     * 保留规则与设备无关：取 syncId 字典序最小的一条，其余软删除写入墓碑，
     * 随备份/同步传递给对端，避免对端再次生成副本。返回清理条数。
     */
    suspend fun deduplicateAnniversaries(): Int {
        val active = anniversaryDao.getAllSnapshot().filter { it.deletedAt == null }
        val groups = active.groupBy { anniversaryKey(it) }.values.filter { it.size > 1 }
        if (groups.isEmpty()) return 0
        val now = System.currentTimeMillis()
        var removed = 0
        groups.forEach { group ->
            val keeper = group.minByOrNull { it.syncId ?: "id:${it.id}" }!!
            group.forEach { dup ->
                if (dup.id == keeper.id) return@forEach
                anniversaryDao.softDelete(id = dup.id, deletedAt = now, updatedAt = now)
                removed++
            }
        }
        return removed
    }

    /** 去重键：名称（忽略大小写与首尾空格）+ 日期 + 类型。 */
    private fun anniversaryKey(anniversary: Anniversary): String =
        "${anniversary.name.trim().lowercase()}|${anniversary.date}|${anniversary.type}"

    // ---- 备份/同步 ----

    suspend fun getAllSnapshot(): List<Anniversary> = anniversaryDao.getAllSnapshot()

    /** 活跃快照（后台纪念日检查 Worker 用）。 */
    suspend fun getActiveSnapshot(): List<Anniversary> = anniversaryDao.getActiveSnapshot()

    suspend fun insertSynced(anniversary: Anniversary): Long = anniversaryDao.insert(anniversary)

    suspend fun updateSynced(anniversary: Anniversary) = anniversaryDao.update(anniversary)
}
