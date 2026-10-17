package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.LedgerBudget
import kotlinx.coroutines.flow.Flow

/**
 * 写操作一律带 `dirty = 1`（P6）：本机改动要被增量同步捞出来，漏标就会静默不上传。
 */
@Dao
interface LedgerBudgetDao {

    @Query("SELECT * FROM ledger_budgets WHERE deletedAt IS NULL")
    fun observeAll(): Flow<List<LedgerBudget>>

    @Query("SELECT * FROM ledger_budgets")
    suspend fun getAllSnapshot(): List<LedgerBudget>

    /** 按 categoryId 查找（含墓碑）；categoryId 为 null 表示总预算。 */
    @Query(
        "SELECT * FROM ledger_budgets " +
            "WHERE (categoryId IS NULL AND :categoryId IS NULL) OR categoryId = :categoryId " +
            "LIMIT 1"
    )
    suspend fun getByCategoryId(categoryId: Long?): LedgerBudget?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(budget: LedgerBudget): Long

    @Update
    suspend fun update(budget: LedgerBudget)

    /** 软删除（写墓碑），取消预算时用；保留记录以便把「取消」同步给其他设备。 */
    @Query("UPDATE ledger_budgets SET deletedAt = :deletedAt, updatedAt = :deletedAt, dirty = 1 WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long)

    @Query("SELECT COUNT(*) FROM ledger_budgets")
    suspend fun getCount(): Int
}
