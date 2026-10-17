package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 记账账户（微信/支付宝/现金/银行卡等）。
 * 不落余额字段——当前余额 = 初始余额 + 账单聚合，编辑、删除、合并导入后自动正确。
 */
@Entity(tableName = "ledger_assets")
data class LedgerAsset(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** 图标 key，对应分类图标目录的字符串 key 体系。 */
    val icon: String = "",
    val sort: Int = 0,
    /** 初始余额（分）；建账后一般不再改动。 */
    val initialBalance: Long = 0,
    /** 预留账户类型：0 普通账户，1 信用卡（v1 不做账单日）。 */
    val type: Int = 0,
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
