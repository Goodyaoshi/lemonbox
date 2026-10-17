package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import kotlinx.coroutines.flow.Flow

/**
 * 写操作一律带 `dirty = 1`（P6）：本机改动要被增量同步捞出来，漏标就会静默不上传。
 */
@Dao
interface ReminderDao {

    /** 全量列表（不含软删除）：进行中的在前按触发时间排，已停用/完成的垫底。 */
    @Query(
        "SELECT * FROM reminders WHERE deletedAt IS NULL " +
            "ORDER BY enabled DESC, nextFireAt ASC, id ASC"
    )
    fun getAllReminders(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE enabled = 1 AND deletedAt IS NULL ORDER BY nextFireAt ASC")
    fun getActiveReminders(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE enabled = 1 AND deletedAt IS NULL ORDER BY nextFireAt ASC")
    suspend fun getActiveRemindersSync(): List<Reminder>

    /** 进行中提醒里最早的一个触发时间，用于排下一个后台任务。 */
    @Query("SELECT MIN(nextFireAt) FROM reminders WHERE enabled = 1 AND deletedAt IS NULL")
    suspend fun getEarliestNextFireAt(): Long?

    /** 按来源键查进行中的提醒（菜谱准备提醒一天最多一条）。 */
    @Query(
        "SELECT * FROM reminders WHERE sourceKey = :sourceKey AND enabled = 1 " +
            "AND deletedAt IS NULL LIMIT 1"
    )
    suspend fun findActiveBySourceKey(sourceKey: String): Reminder?

    /** 按主键查进行中的提醒（通知上的「完成」按钮用）。 */
    @Query(
        "SELECT * FROM reminders WHERE id = :id AND enabled = 1 AND deletedAt IS NULL LIMIT 1"
    )
    suspend fun findActiveById(id: Long): Reminder?

    /** 全量快照（含软删除墓碑，备份导出/合并导入用）。 */
    @Query("SELECT * FROM reminders")
    suspend fun getAllSnapshot(): List<Reminder>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Delete
    suspend fun delete(reminder: Reminder)

    @Query("UPDATE reminders SET deletedAt = :deletedAt, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long, updatedAt: Long)

    /** 永久删除改写成墓碑（F21，同 [Item.purgedAt]）：不物理删行，否则其他设备无从得知删除。 */
    @Query(
        "UPDATE reminders SET deletedAt = :timestamp, purgedAt = :timestamp, updatedAt = :timestamp, " +
            "dirty = 1 WHERE id IN (:ids)"
    )
    suspend fun markPurged(ids: List<Long>, timestamp: Long)

    /** 只清软删墓碑；永久删除墓碑保留更久，见 [purgePurgedOlderThan]。 */
    @Query(
        "DELETE FROM reminders WHERE deletedAt IS NOT NULL AND purgedAt IS NULL AND deletedAt < :cutoff" + TOMBSTONE_CONFIRMED_SQL
    )
    suspend fun purgeDeletedOlderThan(cutoff: Long)

    /** 清到期的永久删除墓碑，保留期更长：墓碑要活到其他设备都拉到过它为止。 */
    @Query("DELETE FROM reminders WHERE purgedAt IS NOT NULL AND purgedAt < :cutoff" + TOMBSTONE_CONFIRMED_SQL)
    suspend fun purgePurgedOlderThan(cutoff: Long)
}
