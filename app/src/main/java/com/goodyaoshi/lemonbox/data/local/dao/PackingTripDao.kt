package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.PackingTrip
import kotlinx.coroutines.flow.Flow

/**
 * 写操作一律带 `dirty = 1`（P6）：本机改动要被增量同步捞出来，漏标就会静默不上传。
 */
@Dao
interface PackingTripDao {

    /** 全部未删除行程：新行程靠前。 */
    @Query("SELECT * FROM packing_trips WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PackingTrip>>

    @Query("SELECT * FROM packing_trips WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PackingTrip?

    @Query("SELECT * FROM packing_trips WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun getByIdFlow(id: Long): Flow<PackingTrip?>

    /** 全量快照（含软删除墓碑，备份导出/合并导入用）。 */
    @Query("SELECT * FROM packing_trips")
    suspend fun getAllSnapshot(): List<PackingTrip>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trip: PackingTrip): Long

    @Update
    suspend fun update(trip: PackingTrip)

    @Query("UPDATE packing_trips SET deletedAt = :deletedAt, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long, updatedAt: Long)

    @Query("DELETE FROM packing_trips WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff" + TOMBSTONE_CONFIRMED_SQL)
    suspend fun purgeDeletedOlderThan(cutoff: Long)
}
