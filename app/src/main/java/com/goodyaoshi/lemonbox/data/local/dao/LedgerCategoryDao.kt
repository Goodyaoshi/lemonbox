package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerCategoryDao {

    @Query("SELECT * FROM ledger_categories WHERE deletedAt IS NULL ORDER BY sort ASC, id ASC")
    fun observeAll(): Flow<List<LedgerCategory>>

    @Query(
        "SELECT * FROM ledger_categories " +
            "WHERE deletedAt IS NULL AND kind = :kind ORDER BY sort ASC, id ASC"
    )
    fun observeByKind(kind: Int): Flow<List<LedgerCategory>>

    @Query("SELECT * FROM ledger_categories WHERE id = :id")
    suspend fun getById(id: Long): LedgerCategory?

    /** 全量快照（含墓碑），供备份/同步合并使用。 */
    @Query("SELECT * FROM ledger_categories ORDER BY id ASC")
    suspend fun getAllSnapshot(): List<LedgerCategory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: LedgerCategory): Long

    @Update
    suspend fun update(category: LedgerCategory)

    @Query(
        "UPDATE ledger_categories SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id"
    )
    suspend fun softDelete(id: Long, deletedAt: Long)

    @Query("SELECT COUNT(*) FROM ledger_categories")
    suspend fun getCount(): Int
}
