package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 月度预算：categoryId 为 null 表示月度总预算，非空表示对应支出分类的月度预算。
 * 每个分类最多一条预算（categoryId 唯一索引），覆盖式更新。
 */
@Entity(
    tableName = "ledger_budgets",
    indices = [Index(value = ["categoryId"], unique = true)]
)
data class LedgerBudget(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 预算归属：null = 月度总预算；非空 = 支出分类 id。 */
    val categoryId: Long? = null,
    /** 预算金额（分）。 */
    val amount: Long,
    /** 预算周期，预留扩展；v1 恒为 monthly。 */
    val period: String = "monthly",
    /** 跨设备合并用的稳定标识。 */
    val syncId: String? = UUID.randomUUID().toString(),
    /** 最近一次修改时间。 */
    val updatedAt: Long? = System.currentTimeMillis(),
    /** 软删除时间戳（墓碑）。 */
    val deletedAt: Long? = null
)
