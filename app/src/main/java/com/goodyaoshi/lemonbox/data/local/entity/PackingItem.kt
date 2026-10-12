package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 打包清单条目（F16）：某次行程要带的一件东西，可勾选「已装好」。
 *
 * [tripId] 指向所属 [PackingTrip]；[checked] 为打包进度标记；
 * [sort] 为同组内排序权重，越小越靠前。
 */
@Entity(
    tableName = "packing_items",
    indices = [Index("tripId")]
)
data class PackingItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 所属行程 id。 */
    val tripId: Long,
    /** 条目名，如「充电器」。 */
    val name: String,
    /** 数量，至少 1。 */
    val quantity: Int = 1,
    /** 分组名（如「证件」「衣物」），空串表示未分组。 */
    val category: String = "",
    /** 是否已装好。 */
    val checked: Boolean = false,
    /** 同组内排序权重，越小越靠前。 */
    val sort: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** 跨设备合并用的稳定标识。 */
    val syncId: String? = UUID.randomUUID().toString(),
    val updatedAt: Long? = System.currentTimeMillis(),
    /** 软删除墓碑；非空表示已删除。 */
    val deletedAt: Long? = null
)
