package com.goodyaoshi.lemonbox.data.local.entity

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
    /** 最近一次修改时间。 */
    val updatedAt: Long? = System.currentTimeMillis(),
    /** 软删除时间戳（墓碑）。 */
    val deletedAt: Long? = null
)
