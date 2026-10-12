package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.LedgerTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerTemplateDao {

    /** 全部未删除模板：按排序权重与创建时间排列。 */
    @Query(
        "SELECT * FROM ledger_templates WHERE deletedAt IS NULL " +
            "ORDER BY sort ASC, createdAt ASC"
    )
    fun observeAll(): Flow<List<LedgerTemplate>>

    @Query("SELECT * FROM ledger_templates WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LedgerTemplate?

    /** 全量快照（含软删除墓碑，备份导出/合并导入用）。 */
    @Query("SELECT * FROM ledger_templates")
    suspend fun getAllSnapshot(): List<LedgerTemplate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: LedgerTemplate): Long

    @Update
    suspend fun update(template: LedgerTemplate)

    @Delete
    suspend fun delete(template: LedgerTemplate)

    @Query("UPDATE ledger_templates SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long, updatedAt: Long)

    @Query("DELETE FROM ledger_templates WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeDeletedOlderThan(cutoff: Long)
}
