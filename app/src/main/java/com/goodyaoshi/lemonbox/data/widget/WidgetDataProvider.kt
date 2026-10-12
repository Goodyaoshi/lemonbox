package com.goodyaoshi.lemonbox.data.widget

import com.goodyaoshi.lemonbox.data.local.dao.ItemDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerRecordDao
import com.goodyaoshi.lemonbox.data.local.dao.ReminderDao
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** 桌面小组件要展示的一份数据快照（F04）。 */
data class WidgetSnapshot(
    /** 待买清单的物品名（按需截取展示）。 */
    val toBuyNames: List<String>,
    /** 今天待办的提醒条数。 */
    val todayReminderCount: Int,
    /** 本月支出合计（分）。 */
    val monthExpenseCents: Long
)

/**
 * 桌面小组件的数据来源（F04）：拉一次待买 / 今日提醒 / 本月支出，
 * 供小组件渲染。查询都是轻量的聚合 / 名单，适合在广播或后台任务里跑。
 */
@Singleton
class WidgetDataProvider @Inject constructor(
    private val itemDao: ItemDao,
    private val reminderDao: ReminderDao,
    private val ledgerRecordDao: LedgerRecordDao
) {

    suspend fun snapshot(): WidgetSnapshot {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startOfDay = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endOfDay = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfMonth = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endOfMonth = today.withDayOfMonth(1).plusMonths(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

        val toBuy = itemDao.getToBuyNames()
        val todayCount = reminderDao.getActiveRemindersSync().count {
            it.nextFireAt in startOfDay until endOfDay
        }
        val monthExpense = ledgerRecordDao.sumByTypeBetween(startOfMonth, endOfMonth)
            .firstOrNull { it.type == LedgerRecord.TYPE_EXPENSE }?.total ?: 0L

        return WidgetSnapshot(
            toBuyNames = toBuy,
            todayReminderCount = todayCount,
            monthExpenseCents = monthExpense
        )
    }
}
