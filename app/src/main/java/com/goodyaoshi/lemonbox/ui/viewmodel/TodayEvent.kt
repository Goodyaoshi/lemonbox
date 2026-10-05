package com.goodyaoshi.lemonbox.ui.viewmodel

/**
 * 今日事项的三种来源。未来新增账单（BILL）、纪念日（MEMORY）等场景时，
 * 只要注册数据源映射成 [TodayEvent] 即可进入今日清单，今日页结构不用动。
 */
enum class TodayEventKind {
    /** 提醒待办：今天要办的事（解冻、缴费、家务…）。 */
    TASK,

    /** 家当到期：临期物品提醒趁新鲜处理。 */
    EXPIRY,

    /** 待买：需要补货或新买的物品。 */
    TO_BUY
}

/** 点一条今日事项的去处：跳对应页面，或直接看某件物品详情。 */
sealed interface TodayEventTarget {
    /** 提醒待办 → 提醒页逐件处理。 */
    data object Reminders : TodayEventTarget

    /** 家当到期 → 到期提醒页。 */
    data object Expiry : TodayEventTarget

    /** 待买 → 待买清单页（采购闭环的购物清单）。 */
    data object ToBuy : TodayEventTarget

    /** 直接看某件物品。 */
    data class ItemDetail(val itemId: Long) : TodayEventTarget
}

/**
 * 今日事项统一模型：提醒待办 + 临期家当 + 待买聚合后的「今天的事」。
 *
 * @param key 稳定标识，供 Compose 列表复用
 * @param completable 是否可在今日清单里直接勾掉（目前仅提醒待办）
 * @param reminderId 可勾选时对应提醒的 id，完成时回查用
 */
data class TodayEvent(
    val kind: TodayEventKind,
    val key: String,
    val title: String,
    val detail: String? = null,
    val target: TodayEventTarget,
    val completable: Boolean = false,
    val reminderId: Long? = null
)
