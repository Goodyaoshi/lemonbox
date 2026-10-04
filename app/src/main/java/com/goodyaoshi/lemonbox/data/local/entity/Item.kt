package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Location::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("categoryId"),
        Index("locationId"),
        Index("usageStatus"),
        Index("disposition"),
        Index("barcode")
    ]
)
data class Item(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val barcode: String? = null,
    val categoryId: Long? = null,
    val locationId: Long? = null,
    val quantity: Int = 1,
    val unit: String = "件",
    val price: Double? = null,
    val expireTime: Long? = null,
    /** 使用进度维度：未使用 / 使用中 / 已用完。 */
    val usageStatus: Int = USAGE_IN_USE,
    /** 物品去向维度：在库 / 已借出 / 已送人 / 已丢弃。 */
    val disposition: Int = DISPOSITION_IN_STOCK,
    /** 补货标记：勾选后进入待买清单；与使用进度、去向互不影响。 */
    val needRestock: Boolean = false,
    /** 该物品单独的到期提醒阶梯（逗号分隔的天数）；空表示跟随全局默认阶梯。 */
    val reminderDays: String? = null,
    val rating: Int? = null,
    val ratedAt: Long? = null,
    val deletedAt: Long? = null,
    val note: String = "",
    val imagePath: String = "",
    val imagePaths: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** 跨设备合并用的稳定标识：本地新建自动生成，历史数据由迁移回填。 */
    val syncId: String? = UUID.randomUUID().toString(),
    /** 最近一次修改时间，导入时按“较新者胜”合并；历史数据回退到 createdAt。 */
    val updatedAt: Long? = System.currentTimeMillis()
) {
    fun imagePathList(): List<String> {
        return decodeImagePaths(imagePaths, imagePath)
    }

    fun primaryImagePath(): String {
        return imagePathList().firstOrNull().orEmpty()
    }

    companion object {
        /** 使用进度：还没开始用。 */
        const val USAGE_UNUSED = 0

        /** 使用进度：用掉了一部分（默认值）。 */
        const val USAGE_IN_USE = 1

        /** 使用进度：已经用完。 */
        const val USAGE_USED_UP = 2

        /** 物品去向：还在自己手里（默认值），对应"在库"。 */
        const val DISPOSITION_IN_STOCK = 0

        /** 物品去向：借给了别人，将来会还回来。 */
        const val DISPOSITION_LENT_OUT = 1

        /** 物品去向：已经送给别人，不会回来了。 */
        const val DISPOSITION_GIVEN_AWAY = 2

        /** 物品去向：已经丢弃/扔掉。 */
        const val DISPOSITION_DISCARDED = 3

        /**
         * 旧版单值 status 到三维状态的映射，迁移与旧备份导入共用。
         *
         * | 旧值 | 含义 | usageStatus | disposition | needRestock |
         * |---|---|---|---|---|
         * | 0 | 在用 | 使用中 | 在库 | false |
         * | 1 | 已用完 | 已用完 | 在库 | false |
         * | 2 | 已丢弃 | 使用中 | 已丢弃 | false |
         * | 3 | 待买 | 已用完 | 在库 | true |
         * | 4 | 已借出 | 使用中 | 已借出 | false |
         * | 5 | 已送人 | 使用中 | 已送人 | false |
         * | 6 | 已过期 | 使用中 | 在库 | false |
         * | 7 | 待处理 | 未使用 | 在库 | false |
         * | 100+ | 自定义 | 原样保留 | 在库 | false |
         */
        fun fromLegacyStatus(legacyStatus: Int): LegacyStatusMapping = when (legacyStatus) {
            0 -> LegacyStatusMapping(USAGE_IN_USE, DISPOSITION_IN_STOCK, false)
            1 -> LegacyStatusMapping(USAGE_USED_UP, DISPOSITION_IN_STOCK, false)
            2 -> LegacyStatusMapping(USAGE_IN_USE, DISPOSITION_DISCARDED, false)
            3 -> LegacyStatusMapping(USAGE_USED_UP, DISPOSITION_IN_STOCK, true)
            4 -> LegacyStatusMapping(USAGE_IN_USE, DISPOSITION_LENT_OUT, false)
            5 -> LegacyStatusMapping(USAGE_IN_USE, DISPOSITION_GIVEN_AWAY, false)
            6 -> LegacyStatusMapping(USAGE_IN_USE, DISPOSITION_IN_STOCK, false)
            7 -> LegacyStatusMapping(USAGE_UNUSED, DISPOSITION_IN_STOCK, false)
            else -> LegacyStatusMapping(legacyStatus, DISPOSITION_IN_STOCK, false)
        }

        /** 把提醒阶梯编码成逗号分隔字符串；空列表回退为 null（跟随全局默认）。 */
        fun encodeReminderDays(days: List<Int>): String? =
            days.filter { it > 0 }
                .distinct()
                .sorted()
                .joinToString(",")
                .ifBlank { null }

        /** 解析提醒阶梯；空/非法一律返回空列表（跟随全局默认）。 */
        fun decodeReminderDays(encoded: String?): List<Int> =
            encoded?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it > 0 }
                ?.distinct()
                ?.sorted()
                ?: emptyList()

        /**
         * 该物品的到期提醒窗口（天）：取自身阶梯（为空则全局阶梯）中的最大值。
         * 首页「即将过期」、搜索的临期条件都按这个窗口逐件判定。
         */
        fun reminderWindowDays(reminderDays: String?, globalLadder: List<Int>): Int =
            decodeReminderDays(reminderDays).ifEmpty { globalLadder }.maxOrNull() ?: 0

        fun encodeImagePaths(paths: List<String>): String {
            return paths
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(separator = "\n")
        }

        fun decodeImagePaths(encoded: String, legacyPrimaryPath: String = ""): List<String> {
            val parsed = encoded
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()

            return when {
                parsed.isNotEmpty() -> parsed
                legacyPrimaryPath.isNotBlank() -> listOf(legacyPrimaryPath)
                else -> emptyList()
            }
        }
    }
}
