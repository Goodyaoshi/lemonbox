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