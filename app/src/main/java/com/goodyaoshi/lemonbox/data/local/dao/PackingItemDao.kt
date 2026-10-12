package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.PackingItem
import kotlinx.coroutines.flow.Flow

@Dao
interface PackingItemDao {

    /** 某行程下全部未删除条目：按排序权重与创建时间排列。 */
    @Query(
        "SELECT * FROM packing_items WHERE tripId = :tripId AND deletedAt IS NULL " +
            "ORDER BY sort ASC, createdAt ASC"
    )
    fun observeByTrip(tripId: Long): Flow<List<PackingItem>>

    /** 全部未删除条目（列表页聚合各行程打包进度用）。 */
    @Query("SELECT * FROM packing_items WHERE deletedAt IS NULL")
    fun observeAllActive(): Flow<List<PackingItem>>

    @Query("SELECT * FROM packing_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PackingItem?

    /** 全量快照（含软删除墓碑，备份导出/合并导入用）。 */
    @Query("SELECT * FROM packing_items")
    suspend fun getAllSnapshot(): List<PackingItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PackingItem): Long

    @Update
    suspend fun update(item: PackingItem)

    @Query("UPDATE packing_items SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long, updatedAt: Long)

    /** 随行程一起软删除，避免行程删了条目还挂在库里。 */
    @Query(
        "UPDATE packing_items SET deletedAt = :deletedAt, updatedAt = :updatedAt " +
            "WHERE tripId = :tripId AND deletedAt IS NULL"
    )
    suspend fun softDeleteByTrip(tripId: Long, deletedAt: Long, updatedAt: Long)

    @Query("DELETE FROM packing_items WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeDeletedOlderThan(cutoff: Long)
}
