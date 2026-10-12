package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 记账模板：把常用的记账参数（类型 / 金额 / 分类 / 账户 / 备注）存下来，
 * 下次记账时一键套用，省去重复录入。金额以「分」为单位存储。
 */
@Entity(tableName = "ledger_templates")
data class LedgerTemplate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** 记账类型：支出 / 收入 / 转账，取值同 [LedgerRecord]。 */
    val type: Int = LedgerRecord.TYPE_EXPENSE,
    /** 金额（分），恒为正。 */
    val amount: Long = 0,
    val categoryId: Long? = null,
    val assetId: Long? = null,
    val targetAssetId: Long? = null,
    val remark: String = "",
    /** 列表排序权重，越小越靠前。 */
    val sort: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** 跨设备合并用的稳定标识。 */
    val syncId: String? = UUID.randomUUID().toString(),
    val updatedAt: Long? = System.currentTimeMillis(),
    /** 软删除墓碑；非空表示已删除。 */
    val deletedAt: Long? = null
)
