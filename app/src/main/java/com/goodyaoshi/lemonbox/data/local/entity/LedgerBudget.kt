package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.ColumnInfo
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
    /**
     * 本机改动待推送标记（P6）：true = 这条有本机改动还没被服务端确认收到。
     * 默认取 true 的取舍见 `Migrations.MIGRATION_36_37` 的说明。
     */
    @ColumnInfo(defaultValue = "1")
    val dirty: Boolean = true,
    /** 本机上次见到的该条服务端序号；0 表示服务端还没有这条（P6）。 */
    @ColumnInfo(defaultValue = "0")
    val serverSeq: Long = 0,
    /** 最近一次修改时间。 */
    val updatedAt: Long? = System.currentTimeMillis(),
    /** 软删除时间戳（墓碑）。 */
    val deletedAt: Long? = null
)
