package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecurringRule
import kotlinx.coroutines.flow.Flow

/**
 * 写操作一律带 `dirty = 1`（P6）：本机改动要被增量同步捞出来，漏标就会静默不上传。
 */
@Dao
interface LedgerRecurringRuleDao {

    /** 全部未删除规则：启用的在前，按下次生成时间升序。 */
    @Query(
        "SELECT * FROM ledger_recurring_rules WHERE deletedAt IS NULL " +
            "ORDER BY enabled DESC, nextFireAt ASC, id ASC"
    )
    fun observeAll(): Flow<List<LedgerRecurringRule>>

    @Query("SELECT * FROM ledger_recurring_rules WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LedgerRecurringRule?

    /** 到点待生成账单的启用规则（[nextFireAt] <= now）。 */
    @Query(
        "SELECT * FROM ledger_recurring_rules WHERE enabled = 1 AND deletedAt IS NULL " +
            "AND nextFireAt <= :now ORDER BY nextFireAt ASC"
    )
    suspend fun getDueRules(now: Long): List<LedgerRecurringRule>

    /** 全量快照（含软删除墓碑，备份导出/合并导入用）。 */
    @Query("SELECT * FROM ledger_recurring_rules")
    suspend fun getAllSnapshot(): List<LedgerRecurringRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: LedgerRecurringRule): Long

    @Update
    suspend fun update(rule: LedgerRecurringRule)

    @Delete
    suspend fun delete(rule: LedgerRecurringRule)

    @Query(
        "UPDATE ledger_recurring_rules SET deletedAt = :deletedAt, updatedAt = :updatedAt, " +
            "dirty = 1 WHERE id = :id"
    )
    suspend fun softDelete(id: Long, deletedAt: Long, updatedAt: Long)

    @Query("DELETE FROM ledger_recurring_rules WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff" + TOMBSTONE_CONFIRMED_SQL)
    suspend fun purgeDeletedOlderThan(cutoff: Long)
}
