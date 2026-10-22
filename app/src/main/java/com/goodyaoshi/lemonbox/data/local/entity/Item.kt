package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

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
    /**
     * 剩余数量。
     *
     * 用 Double 而不是 Int：菜谱扣料要表达「用 200 克肉」「半袋盐」，
     * 整数件在食材场景里根本不够用（v38 起）。展示时走 [formatQuantity]，
     * 整数就显示整数，不会满屏「2.0」。
     */
    val quantity: Double = 1.0,
    /**
     * 已消耗数量（仅按件消耗累计）：总量 = quantity(剩余) + consumedQuantity(已用)。
     * 持续使用型物品不累计。
     */
    @ColumnInfo(defaultValue = "0")
    val consumedQuantity: Double = 0.0,
    val unit: String = "件",
    val price: Double? = null,
    val expireTime: Long? = null,
    /** 使用进度维度：未使用 / 使用中 / 已用完。 */
    val usageStatus: Int = USAGE_IN_USE,
    /** 物品去向维度：在库 / 已借出 / 已送人 / 已丢弃。 */
    val disposition: Int = DISPOSITION_IN_STOCK,
    /** 补货标记：勾选后进入待买清单；与使用进度、去向互不影响。 */
    val needRestock: Boolean = false,
    /**
     * 待买项的可选归属：给谁买的。存 [FOR_WHOM_SELF] / [FOR_WHOM_PARTNER] /
     * [FOR_WHOM_SHARED] 之一，null 表示未标注（不展示）。存码值而非文案，
     * 这样「给她」能随昵称一起变化。
     */
    val forWhom: String? = null,
    /** 该物品单独的到期提醒阶梯（逗号分隔的天数）；空表示跟随全局默认阶梯。 */
    val reminderDays: String? = null,
    /** 购买日期（天精度）；未记录为 null。 */
    val purchaseDate: Long? = null,
    /** 生产日期（天精度）；未记录为 null，仅作参考，不参与提醒/统计。 */
    val productionDate: Long? = null,
    /** 开始使用时间；进入使用中时自动落值，可手动补录。 */
    val startUseTime: Long? = null,
    /** 使用结束时间；进入用完/送人/丢弃等非可用状态时自动落值。 */
    val usageEndedAt: Long? = null,
    /**
     * 计量方式：按件消耗（零食、酱油瓶等，逐件扣数量）或持续使用
     * （电器、调料等一直在用还没用完的，不扣数量，按使用天数统计成本）。
     */
    @ColumnInfo(defaultValue = "0")
    val trackMode: Int = TRACK_CONSUMABLE,
    val rating: Int? = null,
    val ratedAt: Long? = null,
    val deletedAt: Long? = null,
    /**
     * 「永久删除」墓碑标记（F21）。
     *
     * 为什么永久删除也要留痕：删除必须能被其他设备看见，否则云端快照里这条记录
     * 直接消失，对方只会认为「它没被改过」而永远保留。所以物理删行改成写墓碑。
     *
     * 与 [deletedAt] 的区别：非空时表示这是永久删除的墓碑——不出现在回收站列表里
     * （见 ItemDao.getRecycleItems），其他设备合并时直接物理清除、不重新插入。
     * 清理时机与普通墓碑一致，由 AppMaintenance 按 TTL 物理清除。
     */
    val purgedAt: Long? = null,
    val note: String = "",
    /**
     * 图片槽位：每张图一个**内容哈希**，换行分隔，顺序即展示顺序（首项为主图）（F22）。
     *
     * 为什么只存哈希：本地文件名（`item_<hash>.jpg`）与云端对象键
     * （`[prefix/]images/<hash>.jpg`）都由它唯一推导，因此不必像早先那样
     * 再各存一列「本地路径」与「对象键」——三列信息本就是一列。
     *
     * 哈希存在即表示「这个槽位有一张图」，与它此刻在本地还是只在云端无关：
     * 上传成功后本地副本会被删掉，但槽位与顺序必须保持，否则主图会漂移。
     * 空项不占位：能落盘的图必然算得出哈希，没有哈希就没有这个槽位。
     */
    val imageHashes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** 跨设备合并用的稳定标识：本地新建自动生成，历史数据由迁移回填。 */
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
    /** 最近一次修改时间，导入时按“较新者胜”合并；历史数据回退到 createdAt。 */
    val updatedAt: Long? = System.currentTimeMillis()
) {
    /** 累计总量：剩余数量 + 已消耗数量，供「已用 / 共 / 剩余」展示。 */
    val totalQuantity: Double
        get() = quantity + consumedQuantity

    /** 全部图片槽位的内容哈希，顺序即展示顺序（首项为主图）。 */
    fun imageHashList(): List<String> = decodeImageHashes(imageHashes)

    /** 第 [index] 张图的内容哈希；没有则为 null。 */
    fun imageHashAt(index: Int): String? =
        imageHashList().getOrNull(index)?.takeIf { it.isNotBlank() }

    /** 主图的内容哈希；没有则为 null。 */
    fun primaryImageHash(): String? = imageHashAt(0)

    /**
     * 替换全部图片槽位（增删 / 重排图片必须走这里）。
     * 只维护一份哈希列表，因此不存在「路径与键各自增删后错位」的可能。
     */
    fun withImageHashes(hashes: List<String>): Item =
        copy(imageHashes = encodeImageHashes(hashes))

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

        /** 计量方式：按件消耗（默认），「用1件」逐件扣数量，减到 0 记为已用完。 */
        const val TRACK_CONSUMABLE = 0

        /** 计量方式：持续使用（电器、调料等），不扣数量，按使用天数统计成本。 */
        const val TRACK_DURABLE = 1

        /** 待买归属：给自己。 */
        const val FOR_WHOM_SELF = "self"

        /** 待买归属：给她（展示时用当前昵称）。 */
        const val FOR_WHOM_PARTNER = "partner"

        /** 待买归属：家里共用。 */
        const val FOR_WHOM_SHARED = "shared"

        /** 待买归属的可选码值，供选择器按顺序渲染。 */
        val FOR_WHOM_CODES = listOf(FOR_WHOM_SELF, FOR_WHOM_PARTNER, FOR_WHOM_SHARED)

        /** 这些顶级分类下的新物品默认按「持续使用」计量；录入时可手动改。 */
        val durableTopCategoryNames = setOf("家电", "数码", "厨具", "衣物", "工具")

        /**
         * 新获得物品（录入 / 扫码入库）的默认使用进度：
         * 按件消耗（零食、酱油等）刚到手还没拆封，默认「未使用」；
         * 持续使用（电器等）默认已在用，「使用中」。
         */
        fun defaultUsageStatusForNewItem(trackMode: Int): Int =
            if (trackMode == TRACK_DURABLE) USAGE_IN_USE else USAGE_UNUSED

        /** 该物品是否已进入非可用状态（已用完/已送人/已丢弃）；借出是暂时的，不算结束。 */
        fun isUsageEnded(usageStatus: Int, disposition: Int): Boolean =
            usageStatus == USAGE_USED_UP ||
                disposition == DISPOSITION_GIVEN_AWAY ||
                disposition == DISPOSITION_DISCARDED

        /** 开始使用的兜底链：开始使用时间 → 购买日期 → 创建时间。 */
        fun effectiveStartUseTime(startUseTime: Long?, purchaseDate: Long?, createdAt: Long): Long =
            startUseTime ?: purchaseDate ?: createdAt

        /**
         * 已使用天数（按本地日历日，含首尾两天）：从开始使用到结束日；
         * 还在用则算到今天。未开始，或已结束但没有结束时间戳（如待买占位记录）
         * 返回 0，界面据此不展示使用统计。
         */
        fun usageDays(
            startUseTime: Long?,
            purchaseDate: Long?,
            createdAt: Long,
            usageEndedAt: Long?,
            usageStatus: Int,
            disposition: Int,
            nowMillis: Long = System.currentTimeMillis()
        ): Int {
            if (usageStatus == USAGE_UNUSED) return 0
            if (isUsageEnded(usageStatus, disposition) && usageEndedAt == null) return 0
            val zone = ZoneId.systemDefault()
            val start = Instant.ofEpochMilli(
                effectiveStartUseTime(startUseTime, purchaseDate, createdAt)
            ).atZone(zone).toLocalDate()
            val endMillis = usageEndedAt ?: nowMillis
            val end = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
            return (ChronoUnit.DAYS.between(start, end) + 1).toInt().coerceAtLeast(0)
        }

        /**
         * 平均每天花费：总价（单价×数量，与「我的」页总价值口径一致）÷ 使用天数。
         * 无价格或使用不足 1 天时返回 null，界面不展示。
         */
        fun averageDailyCost(price: Double?, quantity: Double, days: Int): Double? {
            if (price == null || days < 1) return null
            return price * quantity / days
        }

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

        /**
         * 编码图片槽位：换行分隔，顺序即展示顺序。
         *
         * **不留空项**：哈希就是槽位本身，没有哈希就没有这个槽位，
         * 因此不像早先那样需要「空行占位」来防下标错位——一行一个槽位，天然对齐。
         */
        fun encodeImageHashes(hashes: List<String>): String? =
            hashes.map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(separator = "\n")
                .ifBlank { null }

        /** 解析图片槽位；空列与空白项一律忽略，返回顺序即展示顺序。 */
        fun decodeImageHashes(encoded: String?): List<String> =
            encoded.orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()
    }
}

/**
 * 数量展示：整数不带小数点，非整数最多两位小数（0.5 / 1.25）。
 *
 * 数量从 v38 起是 Double，展示入口必须收敛到一处，
 * 否则会出现一半界面写「2」、另一半写「2.0」。
 */
fun formatQuantity(value: Double): String {
    if (!value.isFinite() || value <= 0) return "0"
    val rounded = (value * 100).roundToLong() / 100.0
    return if (abs(rounded - rounded.roundToLong()) < 1e-9) {
        rounded.roundToLong().toString()
    } else {
        // 去掉末尾多余的 0：0.50 → 0.5
        rounded.toString().trimEnd('0').trimEnd('.')
    }
}

/** 仍需按件展示的场景（如「共 3 件」）：四舍五入到整数。 */
fun Double.toQuantityInt(): Int = roundToLong().toInt().coerceAtLeast(0)
