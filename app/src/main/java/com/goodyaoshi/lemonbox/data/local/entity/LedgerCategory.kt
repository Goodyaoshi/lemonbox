package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.ColumnInfo
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
) {
    companion object {
        /** 支出分类（默认）。 */
        const val KIND_EXPENSE = 0

        /** 收入分类。 */
        const val KIND_INCOME = 1
    }
}
