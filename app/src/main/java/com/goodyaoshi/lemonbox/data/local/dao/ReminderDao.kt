package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import kotlinx.coroutines.flow.Flow

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

    @Query("UPDATE reminders SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long, updatedAt: Long)

    @Query("DELETE FROM reminders WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeDeletedOlderThan(cutoff: Long)
}
