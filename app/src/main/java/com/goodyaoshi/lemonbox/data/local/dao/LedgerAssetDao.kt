package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset
import kotlinx.coroutines.flow.Flow

/** 账户及其当前余额（分）；余额 = 初始余额 + 账单聚合。 */
data class LedgerAssetWithBalance(
    val id: Long,
    val name: String,
    val icon: String,
    val sort: Int,
    val initialBalance: Long,
    val type: Int,
    val syncId: String?,
    val updatedAt: Long?,
    val deletedAt: Long?,
    val balance: Long
)

@Dao
interface LedgerAssetDao {

    /**
     * 全部有效账户 + 实时聚合余额：支出扣减、收入增加；转账从转出方扣、向转入方加。
     * 必须过滤软删墓碑（去重合并后的副本仍留在表中），否则记账页的账户胶囊会重复显示。
     */
    @Query(
        """
        SELECT a.*, a.initialBalance + COALESCE(SUM(CASE
            WHEN r.type = 1 THEN r.amount
            WHEN r.type = 0 THEN -r.amount
            WHEN r.type = 2 THEN CASE WHEN a.id = r.assetId THEN -r.amount ELSE r.amount END
            ELSE 0 END), 0) AS balance
        FROM ledger_assets a
        LEFT JOIN ledger_records r
            ON (r.assetId = a.id OR r.targetAssetId = a.id) AND r.deletedAt IS NULL
        WHERE a.deletedAt IS NULL
        GROUP BY a.id
        ORDER BY a.sort ASC, a.id ASC
        """
    )
    fun observeAssetsWithBalance(): Flow<List<LedgerAssetWithBalance>>

    @Query("SELECT * FROM ledger_assets WHERE deletedAt IS NULL ORDER BY sort ASC, id ASC")
    fun observeAssets(): Flow<List<LedgerAsset>>

    @Query("SELECT * FROM ledger_assets WHERE id = :id")
    suspend fun getById(id: Long): LedgerAsset?

    /** 同名有效账户数量（忽略大小写），排除自身，供新增/编辑时的同名守卫使用。 */
    @Query(
        "SELECT COUNT(*) FROM ledger_assets " +
            "WHERE deletedAt IS NULL AND id != :excludeId AND name = :name COLLATE NOCASE"
    )
    suspend fun countActiveByName(name: String, excludeId: Long): Int

    /** 全量快照（含墓碑），供备份/同步合并使用。 */
    @Query("SELECT * FROM ledger_assets ORDER BY id ASC")
    suspend fun getAllSnapshot(): List<LedgerAsset>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(asset: LedgerAsset): Long

    @Update
    suspend fun update(asset: LedgerAsset)

    @Query("UPDATE ledger_assets SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long)

    @Query("SELECT COUNT(*) FROM ledger_assets")
    suspend fun getCount(): Int
}
