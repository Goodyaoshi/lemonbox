package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import kotlinx.coroutines.flow.Flow

@Dao
interface AnniversaryDao {

    /** 活跃纪念日（未停用、未删除）。 */
    @Query("SELECT * FROM anniversaries WHERE enabled = 1 AND deletedAt IS NULL")
    fun getActiveAnniversaries(): Flow<List<Anniversary>>

    /** 全部未删除纪念日（列表页用，含已停用）。 */
    @Query("SELECT * FROM anniversaries WHERE deletedAt IS NULL")
    fun getAllAnniversaries(): Flow<List<Anniversary>>

    /** 全量快照（含软删墓碑，备份导出/合并导入用）；墓碑必须随包传递，否则本机删除不会同步到对端。 */
    @Query("SELECT * FROM anniversaries")
    suspend fun getAllSnapshot(): List<Anniversary>

    /** 活跃快照（后台检查Worker用）。 */
    @Query("SELECT * FROM anniversaries WHERE enabled = 1 AND deletedAt IS NULL")
    suspend fun getActiveSnapshot(): List<Anniversary>

    @Query("SELECT * FROM anniversaries WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Anniversary?

    /** 编辑页载入（未删除）；id 不存在或已删除时发 null。 */
    @Query("SELECT * FROM anniversaries WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun getByIdFlow(id: Long): Flow<Anniversary?>

    @Query("SELECT COUNT(*) FROM anniversaries WHERE deletedAt IS NULL")
    fun observeCount(): Flow<Int>

    /** 同「名称+日期+类型」的有效纪念日数量（忽略大小写与首尾空格）；编辑时排除自身，用于录入同名守卫。 */
    @Query(
        "SELECT COUNT(*) FROM anniversaries " +
            "WHERE deletedAt IS NULL AND type = :type AND date = :date " +
            "AND LOWER(TRIM(name)) = LOWER(:name) AND id != :excludeId"
    )
    suspend fun countActiveByNameDateType(name: String, date: String, type: Int, excludeId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(anniversary: Anniversary): Long

    @Update
    suspend fun update(anniversary: Anniversary)

    @Query("UPDATE anniversaries SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long, updatedAt: Long)

    @Query("DELETE FROM anniversaries WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeDeletedOlderThan(cutoff: Long)
}
