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

    /** 全量列表：进行中的在前按触发时间排，已停用/完成的垫底。 */
    @Query("SELECT * FROM reminders ORDER BY enabled DESC, nextFireAt ASC, id ASC")
    fun getAllReminders(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE enabled = 1 ORDER BY nextFireAt ASC")
    fun getActiveReminders(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE enabled = 1 ORDER BY nextFireAt ASC")
    suspend fun getActiveRemindersSync(): List<Reminder>

    /** 进行中提醒里最早的一个触发时间，用于排下一个后台任务。 */
    @Query("SELECT MIN(nextFireAt) FROM reminders WHERE enabled = 1")
    suspend fun getEarliestNextFireAt(): Long?

    /** 按来源键查进行中的提醒（菜谱准备提醒一天最多一条）。 */
    @Query("SELECT * FROM reminders WHERE sourceKey = :sourceKey AND enabled = 1 LIMIT 1")
    suspend fun findActiveBySourceKey(sourceKey: String): Reminder?

    /** 按主键查进行中的提醒（通知上的「完成」按钮用）。 */
    @Query("SELECT * FROM reminders WHERE id = :id AND enabled = 1 LIMIT 1")
    suspend fun findActiveById(id: Long): Reminder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Delete
    suspend fun delete(reminder: Reminder)
}
