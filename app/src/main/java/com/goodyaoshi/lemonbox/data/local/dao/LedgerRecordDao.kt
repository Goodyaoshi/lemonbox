package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import kotlinx.coroutines.flow.Flow

/** 按类型聚合的金额合计（分）。 */
data class LedgerTypeSum(
    val type: Int,
    val total: Long?
)

/** 按记账分类聚合的支出合计（分）。 */
data class LedgerCategorySum(
    val categoryId: Long?,
    val total: Long?
)

@Dao
interface LedgerRecordDao {

    /** 某时间段的账单流水（时间倒序）；区间为 [start, end)。 */
    @Query(
        "SELECT * FROM ledger_records " +
            "WHERE deletedAt IS NULL AND recordTime >= :start AND recordTime < :end " +
            "ORDER BY recordTime DESC, id DESC"
    )
    fun observeRecordsBetween(start: Long, end: Long): Flow<List<LedgerRecord>>

    @Query("SELECT * FROM ledger_records WHERE id = :id")
    suspend fun getById(id: Long): LedgerRecord?

    /** 某时间段按类型汇总（支出/收入），供本月收支卡使用。 */
    @Query(
        "SELECT type, SUM(amount) AS total FROM ledger_records " +
            "WHERE deletedAt IS NULL AND recordTime >= :start AND recordTime < :end " +
            "GROUP BY type"
    )
    suspend fun sumByTypeBetween(start: Long, end: Long): List<LedgerTypeSum>

    /** 某时间段支出按分类聚合（金额降序），供统计饼图与排行使用。 */
    @Query(
        "SELECT categoryId, SUM(amount) AS total FROM ledger_records " +
            "WHERE deletedAt IS NULL AND type = 0 " +
            "AND recordTime >= :start AND recordTime < :end " +
            "GROUP BY categoryId ORDER BY total DESC"
    )
    suspend fun sumExpenseByCategoryBetween(start: Long, end: Long): List<LedgerCategorySum>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: LedgerRecord): Long

    @Update
    suspend fun update(record: LedgerRecord)

    @Query("UPDATE ledger_records SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long)

    /** 全量快照（含墓碑），供备份/同步合并使用。 */
    @Query("SELECT * FROM ledger_records ORDER BY id ASC")
    suspend fun getAllSnapshot(): List<LedgerRecord>

    @Query("SELECT COUNT(*) FROM ledger_records")
    suspend fun getCount(): Int

    /** 引用某账户的未删除账单数（支出/收入/转账两端都算），删除账户前校验。 */
    @Query(
        "SELECT COUNT(*) FROM ledger_records " +
            "WHERE deletedAt IS NULL AND (assetId = :assetId OR targetAssetId = :assetId)"
    )
    suspend fun countRecordsByAsset(assetId: Long): Int
}
