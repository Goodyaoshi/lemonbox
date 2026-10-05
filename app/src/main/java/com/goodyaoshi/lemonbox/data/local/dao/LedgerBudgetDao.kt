package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.LedgerBudget
import kotlinx.coroutines.flow.Flow

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

    /** 硬删除；预算不参与墓碑传递，覆盖式更新即可。 */
    @Query("DELETE FROM ledger_budgets WHERE id = :id")
    suspend fun hardDelete(id: Long)

    @Query("SELECT COUNT(*) FROM ledger_budgets")
    suspend fun getCount(): Int
}
