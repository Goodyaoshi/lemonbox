package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 周期性记账规则：按固定周期自动生成一笔账单（如房租、工资、水电费）。
 *
 * [nextFireAt] 是下一次应生成账单的时间戳，由调度任务读取；
 * 到点由 Worker 生成 [LedgerRecord] 并把 [nextFireAt] 推进一个周期。
 */
@Entity(
    tableName = "ledger_recurring_rules",
    indices = [Index("nextFireAt")]
)
data class LedgerRecurringRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** 记账类型：支出 / 收入 / 转账，取值同 [LedgerRecord]。 */
    val type: Int = LedgerRecord.TYPE_EXPENSE,
    /** 金额（分），恒为正。 */
    val amount: Long,
    val categoryId: Long? = null,
    val assetId: Long? = null,
    val targetAssetId: Long? = null,
    val remark: String = "",
    /** 周期单位，取值见 [RepeatUnit]。 */
    val repeatUnit: String = RepeatUnit.MONTH.name,
    /** 每 N 个单位生成一次。 */
    val repeatInterval: Int = 1,
    /** 下次生成账单的时间戳（毫秒）。 */
    val nextFireAt: Long,
    /** 最近一次生成账单的时间戳（毫秒）。 */
    val lastFiredAt: Long? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val syncId: String? = UUID.randomUUID().toString(),
    val updatedAt: Long? = System.currentTimeMillis(),
    /** 软删除墓碑；非空表示已删除。 */
    val deletedAt: Long? = null
) {
    val unit: RepeatUnit
        get() = runCatching { RepeatUnit.valueOf(repeatUnit) }.getOrDefault(RepeatUnit.MONTH)

    /** 周期单位。 */
    enum class RepeatUnit {
        DAY,
        WEEK,
        MONTH,
        YEAR
    }
}
