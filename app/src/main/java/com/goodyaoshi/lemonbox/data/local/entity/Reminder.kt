package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 提醒的重复方式。 */
enum class ReminderRepeatType {
    /** 某一天的一次性提醒，如「今晚解冻肉」。 */
    ONCE,

    /** 每天提醒。 */
    DAILY,

    /** 每隔 N 天提醒，如「隔 3 天洗一次衣服」。 */
    INTERVAL,

    /** 每周固定几天提醒，如「每周三清理洗碗机」。 */
    WEEKLY
}

/** 提醒的来源。 */
enum class ReminderSource {
    /** 手动创建。 */
    MANUAL,

    /** 由未来菜谱的某一餐生成（提醒提前准备/解冻食材）。 */
    MEAL_PREP
}

/**
 * 待办提醒：一次性（某天某时）或周期性（每天/每隔 N 天/每周几）。
 * 触发时间统一落在 [nextFireAt]，由 [com.goodyaoshi.lemonbox.util.ReminderClock] 计算、
 * [com.goodyaoshi.lemonbox.util.TodoReminderWorker] 到点通知并推进。
 * 到点只发通知不自动完结：一次性提醒保持待办，直到在通知或列表里手动完成。
 */
@Entity(
    tableName = "reminders",
    indices = [Index("nextFireAt"), Index("sourceKey")]
)
data class Reminder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val note: String = "",
    val repeatType: String = ReminderRepeatType.ONCE.name,
    /** [ReminderRepeatType.INTERVAL] 的间隔天数。 */
    val intervalDays: Int = 1,
    /** [ReminderRepeatType.WEEKLY] 的周几集合：1=周一 … 7=周日，逗号分隔。 */
    val weekdays: String = "",
    /** 提醒时刻（HH:mm）。 */
    val fireTime: String = "19:00",
    /** [ReminderRepeatType.ONCE] 的目标日期（yyyy-MM-dd）。 */
    val targetDate: String? = null,
    /** 下次触发时间戳（毫秒）；调度与首页「今天 N 件」都看它。 */
    val nextFireAt: Long = 0,
    val enabled: Boolean = true,
    /** 一次性提醒完成的时间；周期提醒完成只推进下一轮，不写这个字段。 */
    val completedAt: Long? = null,
    /** 最近一次发过通知的时间；0 = 未提醒。防止重复通知，一次性提醒触发后靠它保持待办。 */
    val notifiedAt: Long = 0,
    val source: String = ReminderSource.MANUAL.name,
    /** [ReminderSource.MEAL_PREP] 的防重复键（meal_prep:日期），一天最多生成一条。 */
    val sourceKey: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val repeat: ReminderRepeatType
        get() = runCatching { ReminderRepeatType.valueOf(repeatType) }
            .getOrDefault(ReminderRepeatType.ONCE)

    val sourceKind: ReminderSource
        get() = runCatching { ReminderSource.valueOf(source) }
            .getOrDefault(ReminderSource.MANUAL)

    /** 到点通知已发、还在等手动完成的待办项（列表里显示「待完成」标记）。 */
    val awaitingConfirmation: Boolean
        get() = enabled && notifiedAt > 0

    companion object {
        /** 周几集合的持久化格式：去重排序后逗号连接。 */
        fun encodeWeekdays(days: List<Int>): String =
            days.filter { it in 1..7 }.distinct().sorted().joinToString(",")

        /** 解析周几集合；非法 token 忽略。 */
        fun decodeWeekdays(weekdays: String): List<Int> =
            weekdays.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.distinct().sorted()
    }
}
