package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 记账分类：支出、收入各一套，带图标与排序，可增删改。
 * 「其他」为内置保护分类，不可删除。
 */
@Entity(
    tableName = "ledger_categories",
    indices = [Index("kind")]
)
data class LedgerCategory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** 图标 key，对应分类图标目录的字符串 key 体系。 */
    val icon: String = "",
    /** 分类性质：支出 / 收入。 */
    val kind: Int = KIND_EXPENSE,
    val sort: Int = 0,
    /** 上级分类，预留二级分类支持；v1 恒为 null。 */
    val parentId: Long? = null,
    /** 内置保护分类，不可删除。 */
    val isProtected: Boolean = false,
    /** 跨设备合并用的稳定标识。 */
    val syncId: String? = UUID.randomUUID().toString(),
    /** 最近一次修改时间。 */
    val updatedAt: Long? = System.currentTimeMillis(),
    /** 软删除时间戳（墓碑）。 */
    val deletedAt: Long? = null
) {
    companion object {
        /** 支出分类（默认）。 */
        const val KIND_EXPENSE = 0

        /** 收入分类。 */
        const val KIND_INCOME = 1
    }
}
