package com.goodyaoshi.lemonbox.data.forecast

import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 常买物品预测（F08）：按消耗速度推算每件物品「还能用多久」。
 *
 * 思路：以「已消耗数量 ÷ 使用天数」得到日均消耗速度，再用「剩余数量 ÷ 日均速度」
 * 推算距用完还有多少天；据此给出预计用完日期与紧急度，帮助她提前补货。
 * 只统计按件消耗、仍在库、且已经开始用并有过消耗记录的家当——持续使用型与
 * 尚未开封的物品没有稳定的消耗速度，不参与预测。
 */
data class RestockForecast(
    val detail: ItemDetail,
    /** 日均消耗量（件/天），恒大于 0。 */
    val dailyConsumption: Double,
    /** 距用完的天数：负数表示按速度早已用完；恒为有限值。 */
    val daysUntilEmpty: Double,
    /** 预计用完时间戳（毫秒）。 */
    val predictedEmptyAt: Long
) {
    /** 紧急度：0 快要用完（≤3 天）、1 最近要补（≤14 天）、2 还够用。 */
    val urgency: Int
        get() = when {
            daysUntilEmpty <= 3.0 -> 0
            daysUntilEmpty <= 14.0 -> 1
            else -> 2
        }

    companion object {
        /** 参与预测最少已消耗件数：样本太少时速度噪声大，不展示。 */
        private const val MIN_CONSUMED = 1

        /** 一周默认比较窗：不足一周按实际天数算，避免新品速度被摊薄。 */
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000

        fun compute(
            items: List<ItemDetail>,
            nowMillis: Long = System.currentTimeMillis()
        ): List<RestockForecast> {
            val zone = ZoneId.systemDefault()
            val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            return items.mapNotNull { detail ->
                val item = detail.item
                if (item.trackMode != Item.TRACK_CONSUMABLE) return@mapNotNull null
                if (item.disposition != Item.DISPOSITION_IN_STOCK) return@mapNotNull null
                if (item.quantity <= 0) return@mapNotNull null
                if (item.consumedQuantity < MIN_CONSUMED) return@mapNotNull null
                if (item.usageStatus == Item.USAGE_UNUSED) return@mapNotNull null

                val startMillis = Item.effectiveStartUseTime(
                    item.startUseTime, item.purchaseDate, item.createdAt
                )
                val startDate = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
                val elapsedDays = ChronoUnit.DAYS.between(startDate, nowDate)
                    .coerceAtLeast(1)
                val daily = item.consumedQuantity.toDouble() / elapsedDays
                if (daily <= 0.0) return@mapNotNull null

                val daysUntilEmpty = item.quantity / daily
                val predicted = nowMillis + (daysUntilEmpty * DAY_MILLIS).toLong()
                RestockForecast(
                    detail = detail,
                    dailyConsumption = daily,
                    daysUntilEmpty = daysUntilEmpty,
                    predictedEmptyAt = predicted
                )
            }.sortedWith(compareBy({ it.daysUntilEmpty }, { it.detail.item.name }))
        }
    }
}
