package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 一笔账单：支出 / 收入 / 转账。金额以「分」为单位存储（Long，恒为正），
 * 资金方向由 [type] 与账户字段的角色决定，避免浮点误差。
 */
@Entity(
    tableName = "ledger_records",
    indices = [
        Index("recordTime"),
        Index("assetId"),
        Index("targetAssetId"),
        Index("categoryId")
    ]
)
data class LedgerRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 记账类型：支出 / 收入 / 转账。 */
    val type: Int = TYPE_EXPENSE,
    /** 金额（分），恒为正数。 */
    val amount: Long,
    /** 支出/收入的记账分类；转账为 null。 */
    val categoryId: Long? = null,
    /** 账单所在账户：支出为出账方、收入为进账方；转账时是转出方。 */
    val assetId: Long? = null,
    /** 转账的入账账户；仅转账使用。 */
    val targetAssetId: Long? = null,
    /** 记账时间（毫秒）。 */
    val recordTime: Long = System.currentTimeMillis(),
    val remark: String = "",
    /** 关联的家当物品 id（如「已买到」联动生成的账单）；无关联为 null。 */
    val itemId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** 跨设备合并用的稳定标识。 */
    val syncId: String? = UUID.randomUUID().toString(),
    /** 最近一次修改时间。 */
    val updatedAt: Long? = System.currentTimeMillis(),
    /** 软删除时间戳（墓碑）：非空表示已删除，用于在同步中传递删除。 */
    val deletedAt: Long? = null
) {
    companion object {
        /** 支出（默认）。 */
        const val TYPE_EXPENSE = 0

        /** 收入。 */
        const val TYPE_INCOME = 1

        /** 转账：assetId 转出、targetAssetId 转入。 */
        const val TYPE_TRANSFER = 2
    }
}
