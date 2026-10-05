package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 纪念日，按用途分三类（[type]）：
 * - 倒数日：盯着一个未来的日子倒数（考试、旅行、周年），可选每 N 天/周/月/年重复；
 * - 正数日：从过去某天起累计（在一起第 N 天、戒烟第 N 天），不重复；
 * - 生日：每年循环一天（公历或农历），可提前提醒准备礼物。
 * 提前提醒天数存在 [remindDays]，由 AnniversaryCheckWorker 按提醒时间点检查。
 */
@Entity(tableName = "anniversaries")
data class Anniversary(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val note: String = "",
    /** 纪念日类型（[TYPE_COUNTDOWN] 等，见 companion 常量）。 */
    val type: Int = TYPE_COUNTDOWN,
    /** 公历锚点日期（yyyy-MM-dd）。农历录入时也存换算后的公历；生日=出生日。 */
    val date: String,
    /** 农历标记：仅生日使用，按农历月/日换算下一次（tyme4kt）。 */
    val isLunar: Boolean = false,
    /** [isLunar] 时的农历月（1-12；闰月生日按平月过，不提供闰月选项）。 */
    val lunarMonth: Int = 0,
    /** [isLunar] 时的农历日（1-30；某年缺三十时按当月月末过）。 */
    val lunarDay: Int = 0,
    /** 重复周期单位（倒数日可选；生日固定 REPEAT_YEAR；正数日固定 REPEAT_NONE）。 */
    val repeatUnit: String = REPEAT_NONE,
    /** 重复间隔：每 N 个 [repeatUnit] 循环一次。 */
    val repeatInterval: Int = 1,
    /** 提前提醒天数，逗号分隔：""=不提醒，"0"=当天，"0,3"=当天与提前 3 天各提醒一次。 */
    val remindDays: String = "",
    /** 最近一次发过通知的去重键（yyyy-MM-dd@HH:mm）；设备本地状态，不进备份。 */
    val lastNotifiedDate: String? = null,
    val enabled: Boolean = true,
    val syncId: String? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        private const val REMINDER_DAYS_SEPARATOR = ","

        /** 类型：倒数日，盯未来的日子，可选重复。 */
        const val TYPE_COUNTDOWN = 0
        /** 类型：正数日，从过去某天累计「第 N 天」。 */
        const val TYPE_COUNTUP = 1
        /** 类型：生日，每年循环（公历/农历）。 */
        const val TYPE_BIRTHDAY = 2

        /** 重复周期单位：不重复。 */
        const val REPEAT_NONE = "NONE"
        const val REPEAT_DAY = "DAY"
        const val REPEAT_WEEK = "WEEK"
        const val REPEAT_MONTH = "MONTH"
        const val REPEAT_YEAR = "YEAR"

        /** 农历生日只支持的循环单位。 */
        const val LUNAR_ONLY_UNIT = REPEAT_YEAR

        /** 归一化重复单位：非法值兜底为不重复；大小写不敏感。 */
        fun normalizeRepeatUnit(raw: String?): String = when (raw?.trim()?.uppercase()) {
            REPEAT_DAY -> REPEAT_DAY
            REPEAT_WEEK -> REPEAT_WEEK
            REPEAT_MONTH -> REPEAT_MONTH
            REPEAT_YEAR -> REPEAT_YEAR
            else -> REPEAT_NONE
        }

        /**
         * 周期的展示名：每天/每周/每月/每年，间隔大于 1 时为「每 N 天」等。
         * 不重复返回空串（由调用方决定倒数/累计文案）。
         */
        fun repeatLabel(unit: String, interval: Int): String {
            val n = interval.coerceAtLeast(1)
            return when (normalizeRepeatUnit(unit)) {
                REPEAT_DAY -> if (n == 1) "每天" else "每${n}天"
                REPEAT_WEEK -> if (n == 1) "每周" else "每${n}周"
                REPEAT_MONTH -> if (n == 1) "每月" else "每${n}个月"
                REPEAT_YEAR -> if (n == 1) "每年" else "每${n}年"
                else -> ""
            }
        }

        /** 提前提醒天数的持久化格式：去重排序后逗号连接。 */
        fun encodeReminderDays(days: List<Int>): String =
            days.filter { it >= 0 }.distinct().sorted().joinToString(REMINDER_DAYS_SEPARATOR)

        /** 解析提前提醒天数；非法 token 忽略。 */
        fun decodeReminderDays(encoded: String): List<Int> =
            encoded.split(REMINDER_DAYS_SEPARATOR)
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it >= 0 }
                .distinct()
                .sorted()
    }
}
