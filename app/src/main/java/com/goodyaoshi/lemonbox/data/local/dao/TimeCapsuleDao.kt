package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.TimeCapsule
import kotlinx.coroutines.flow.Flow

@Dao
interface TimeCapsuleDao {

    /** 全部未删除胶囊：越早解锁的排越前。 */
    @Query("SELECT * FROM time_capsules WHERE deletedAt IS NULL ORDER BY unlockAt ASC, createdAt ASC")
    fun observeAll(): Flow<List<TimeCapsule>>

    @Query("SELECT * FROM time_capsules WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TimeCapsule?

    @Query("SELECT COUNT(*) FROM time_capsules WHERE deletedAt IS NULL")
    fun observeCount(): Flow<Int>

    /** 全量快照（含软删除墓碑，备份导出/合并导入用）。 */
    @Query("SELECT * FROM time_capsules")
    suspend fun getAllSnapshot(): List<TimeCapsule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(capsule: TimeCapsule): Long

    @Update
    suspend fun update(capsule: TimeCapsule)

    @Query("UPDATE time_capsules SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long, updatedAt: Long)

    @Query("DELETE FROM time_capsules WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeDeletedOlderThan(cutoff: Long)
}
