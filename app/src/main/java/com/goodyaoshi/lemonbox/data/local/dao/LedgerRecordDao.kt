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

/** 按月 × 类型聚合的金额合计（分）；monthKey 形如 yyyy-MM。 */
data class LedgerMonthTypeSum(
    val monthKey: String,
    val type: Int,
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

    /**
     * 按 syncId 查一条账单（含墓碑）。
     *
     * 周期记账用它做「同一期是否已生成」的判断：deterministic syncId 让本机在
     * 规则从别的设备同步过来后，不会把对方已生成的那一期再生成一遍。
     */
    @Query("SELECT * FROM ledger_records WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): LedgerRecord?

    /** 某时间段按类型汇总（支出/收入），供本月收支卡使用。 */
    @Query(
        "SELECT type, SUM(amount) AS total FROM ledger_records " +
            "WHERE deletedAt IS NULL AND recordTime >= :start AND recordTime < :end " +
            "GROUP BY type"
    )
    suspend fun sumByTypeBetween(start: Long, end: Long): List<LedgerTypeSum>

    /** 某时间段某分类的账单流水（时间倒序）；categoryId 为 null 表示未分类。 */
    @Query(
        "SELECT * FROM ledger_records " +
            "WHERE deletedAt IS NULL AND type = :kind " +
            "AND recordTime >= :start AND recordTime < :end " +
            "AND ((:categoryId IS NULL AND categoryId IS NULL) OR categoryId = :categoryId) " +
            "ORDER BY recordTime DESC, id DESC"
    )
    fun observeRecordsByCategoryBetween(
        kind: Int,
        categoryId: Long?,
        start: Long,
        end: Long
    ): Flow<List<LedgerRecord>>

    /** 某时间段支出按分类聚合（金额降序），供统计饼图与排行使用。 */
    @Query(
        "SELECT categoryId, SUM(amount) AS total FROM ledger_records " +
            "WHERE deletedAt IS NULL AND type = 0 " +
            "AND recordTime >= :start AND recordTime < :end " +
            "GROUP BY categoryId ORDER BY total DESC"
    )
    suspend fun sumExpenseByCategoryBetween(start: Long, end: Long): List<LedgerCategorySum>

    /**
     * 某时间段按月 × 类型聚合（分），供趋势图与年度回顾。
     * 账期键按 [monthStartDay] 位移后再取 yyyy-MM：与 LedgerMath.monthRange 的
     * 「记账月开始日」口径一致（startDay=1 即自然月）。
     */
    @Query(
        "SELECT strftime('%Y-%m', recordTime / 1000 - (:monthStartDay - 1) * 86400, 'unixepoch', 'localtime') " +
            "AS monthKey, type, SUM(amount) AS total FROM ledger_records " +
            "WHERE deletedAt IS NULL AND recordTime >= :start AND recordTime < :end " +
            "GROUP BY monthKey, type ORDER BY monthKey ASC"
    )
    fun observeMonthTypeSumsBetween(
        start: Long,
        end: Long,
        monthStartDay: Int
    ): Flow<List<LedgerMonthTypeSum>>

    /** 某时间段的记账笔数，供年度回顾展示与「有无数据」判定。 */
    @Query(
        "SELECT COUNT(*) FROM ledger_records " +
            "WHERE deletedAt IS NULL AND recordTime >= :start AND recordTime < :end"
    )
    fun observeRecordCountBetween(start: Long, end: Long): Flow<Int>

    /** 某时间段支出按分类聚合（金额降序）的响应式版本，供年度回顾取支出最高分类。 */
    @Query(
        "SELECT categoryId, SUM(amount) AS total FROM ledger_records " +
            "WHERE deletedAt IS NULL AND type = 0 " +
            "AND recordTime >= :start AND recordTime < :end " +
            "GROUP BY categoryId ORDER BY total DESC"
    )
    fun observeExpenseByCategoryBetween(start: Long, end: Long): Flow<List<LedgerCategorySum>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: LedgerRecord): Long

    @Update
    suspend fun update(record: LedgerRecord)

    @Query("UPDATE ledger_records SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long)

    /** 从回收站恢复一条账单（清空 deletedAt），配合删除后的「撤销」使用。 */
    @Query("UPDATE ledger_records SET deletedAt = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun restoreFromTrash(id: Long, updatedAt: Long)

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

    /** 把挂到重复分类上的账单改挂到保留项，供去重清理使用。 */
    @Query("UPDATE ledger_records SET categoryId = :toId WHERE categoryId = :fromId")
    suspend fun reassignCategory(fromId: Long, toId: Long)

    /** 把挂到重复账户上的账单改挂到保留项（支出/收入端），供去重清理使用。 */
    @Query("UPDATE ledger_records SET assetId = :toId WHERE assetId = :fromId")
    suspend fun reassignAsset(fromId: Long, toId: Long)

    /** 转账账单的转入端账户改挂到保留项，供去重清理使用。 */
    @Query("UPDATE ledger_records SET targetAssetId = :toId WHERE targetAssetId = :fromId")
    suspend fun reassignTargetAsset(fromId: Long, toId: Long)
}
