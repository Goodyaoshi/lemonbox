package com.goodyaoshi.lemonbox.ui.components

/**
 * 删除相关文案的唯一来源（I2）。
 *
 * 背景：评审发现各入口对"删除"的表述互相矛盾 —— 详情页写"删除后不可恢复"，
 * 而家当 / 待买 / 搜索等页写"先移入回收站，30 天内仍可恢复"。实际上详情页的
 * `DetailViewModel.delete` 与其它入口一样都调用 `ItemRepository.moveToTrash`
 * （软删除，仅置 deletedAt），永久删除只在回收站内提供。
 *
 * 因此收敛为单一数据模型：所有入口统一"先入回收站、期间可恢复"，并让提示文案
 * 由同一份常量提供，避免各页面各写一份而再次漂移。
 */
object DeleteCopy {

    /** 删除二次确认弹窗的标题。 */
    const val CONFIRM_TITLE = "确认删除"

    /** 软删除（移入回收站）二次确认弹窗的副标题。 */
    const val SOFT_SUBTITLE = "删除后会先移入回收站，30 天内仍可恢复。"

    /** 软删除确认按钮文案。 */
    const val SOFT_CONFIRM = "移入回收站"

    /** 「更多操作」列表中删除项的副标题。 */
    const val SOFT_ACTION_SUBTITLE = "移入回收站，30 天内可恢复"
}
