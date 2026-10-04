package com.goodyaoshi.lemonbox.data.local.entity

/**
 * 物品状态的维度。三种信息彼此正交，不再压成单值枚举：
 * - [USAGE] 使用进度：未使用 / 使用中 / 已用完
 * - [DISPOSITION] 物品去向：在库 / 已借出 / 已送人 / 已丢弃
 * 过期与否不单独存储，由 [Item.expireTime] 实时计算。
 */
enum class StatusDimension {
    USAGE,
    DISPOSITION
}

/** 物品状态选项：某个维度下的内置状态或用户自定义状态的统一描述。 */
data class ItemStatusOption(
    val code: Int,
    val label: String,
    val dimension: StatusDimension,
    val isBuiltIn: Boolean
)

/** 旧版单值 status 推导出的三维状态，供数据库迁移与旧备份导入使用。 */
data class LegacyStatusMapping(
    val usageStatus: Int,
    val disposition: Int,
    val needRestock: Boolean
)

/**
 * 物品状态目录的静态部分。
 *
 * 内置状态沿用 [Item] 里的数值常量，历史数据依赖它们；
 * 自定义状态按维度分别编号（[CUSTOM_USAGE_BASE] / [CUSTOM_DISPOSITION_BASE] 起），
 * 编号一旦分配就不再复用，避免历史物品挂到错误的状态上。
 */
object ItemStatusCatalog {

    /** 自定义「使用进度」的起始编号。 */
    const val CUSTOM_USAGE_BASE = 100

    /** 自定义「物品去向」的起始编号。 */
    const val CUSTOM_DISPOSITION_BASE = 200

    val builtInUsage: List<ItemStatusOption> = listOf(
        ItemStatusOption(Item.USAGE_UNUSED, "未使用", StatusDimension.USAGE, true),
        ItemStatusOption(Item.USAGE_IN_USE, "使用中", StatusDimension.USAGE, true),
        ItemStatusOption(Item.USAGE_USED_UP, "已用完", StatusDimension.USAGE, true)
    )

    val builtInDisposition: List<ItemStatusOption> = listOf(
        ItemStatusOption(Item.DISPOSITION_IN_STOCK, "在库", StatusDimension.DISPOSITION, true),
        ItemStatusOption(Item.DISPOSITION_LENT_OUT, "已借出", StatusDimension.DISPOSITION, true),
        ItemStatusOption(Item.DISPOSITION_GIVEN_AWAY, "已送人", StatusDimension.DISPOSITION, true),
        ItemStatusOption(Item.DISPOSITION_DISCARDED, "已丢弃", StatusDimension.DISPOSITION, true)
    )

    /** 取某个维度的内置状态。 */
    fun builtInFor(dimension: StatusDimension): List<ItemStatusOption> = when (dimension) {
        StatusDimension.USAGE -> builtInUsage
        StatusDimension.DISPOSITION -> builtInDisposition
    }

    /** 某个维度自定义状态的起始编号。 */
    fun customBaseFor(dimension: StatusDimension): Int = when (dimension) {
        StatusDimension.USAGE -> CUSTOM_USAGE_BASE
        StatusDimension.DISPOSITION -> CUSTOM_DISPOSITION_BASE
    }

    /** 未知状态码的兜底展示。 */
    fun fallbackLabel(code: Int): String = "状态 $code"
}