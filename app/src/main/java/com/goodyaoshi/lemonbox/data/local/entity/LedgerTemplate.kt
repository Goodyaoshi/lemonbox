package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.ColumnInfo
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
    /**
     * 本机改动待推送标记（P6）：true = 这条有本机改动还没被服务端确认收到。
     * 默认取 true 的取舍见 `Migrations.MIGRATION_36_37` 的说明。
     */
    @ColumnInfo(defaultValue = "1")
    val dirty: Boolean = true,
    /** 本机上次见到的该条服务端序号；0 表示服务端还没有这条（P6）。 */
    @ColumnInfo(defaultValue = "0")
    val serverSeq: Long = 0,
    val updatedAt: Long? = System.currentTimeMillis(),
    /** 软删除墓碑；非空表示已删除。 */
    val deletedAt: Long? = null
)
