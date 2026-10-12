package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 旅行打包清单（F16）：一次出行对应一份清单，下面挂若干 [PackingItem]。
 *
 * [startDate] / [endDate] 为可选的出行日期（yyyy-MM-dd），仅作展示，不参与逻辑。
 */
@Entity(tableName = "packing_trips")
data class PackingTrip(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 行程名，如「三亚亲子游」。 */
    val name: String,
    /** 目的地（可选）。 */
    val destination: String = "",
    /** 出发日期（yyyy-MM-dd，可选）。 */
    val startDate: String? = null,
    /** 返回日期（yyyy-MM-dd，可选）。 */
    val endDate: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** 跨设备合并用的稳定标识。 */
    val syncId: String? = UUID.randomUUID().toString(),
    val updatedAt: Long? = System.currentTimeMillis(),
    /** 软删除墓碑；非空表示已删除。 */
    val deletedAt: Long? = null
)
