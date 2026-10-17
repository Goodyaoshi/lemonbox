package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "categories",
    indices = [Index("parentId")]
)
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val icon: String = "",
    /** 上级分类：null 表示一级分类，非空表示挂在某个分类下，支持任意深度。 */
    val parentId: Long? = null,
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
    /** 软删除时间戳（墓碑）：非空表示已删除，用于在同步中传递删除。 */
    val deletedAt: Long? = null,
    /**
     * 内置保护分类：「食品」及其食材子树（菜谱按这些分类匹配食材，家当默认
     * 筛选与过期提醒也依赖它们），不可删除、不可改名。同步导入时保护位只增不减。
     */
    @ColumnInfo(defaultValue = "0")
    val isProtected: Boolean = false
)