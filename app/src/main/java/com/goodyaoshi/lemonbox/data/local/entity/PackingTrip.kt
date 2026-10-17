package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.ColumnInfo
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
