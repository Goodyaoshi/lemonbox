package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.dao.ReminderDao
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.ReminderRepeatType
import com.goodyaoshi.lemonbox.data.local.entity.ReminderSource
import com.goodyaoshi.lemonbox.util.ReminderClock
import com.goodyaoshi.lemonbox.util.TodoReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** 待办提醒的数据入口：所有写操作之后都会重新调排后台任务。 */
@Singleton
class ReminderRepository @Inject constructor(
    private val reminderDao: ReminderDao,
    private val scheduler: TodoReminderScheduler
) {
    fun getAllReminders(): Flow<List<Reminder>> = reminderDao.getAllReminders()

    fun getActiveReminders(): Flow<List<Reminder>> = reminderDao.getActiveReminders()

    /** 进行中的菜谱准备提醒来源键（meal_prep:日期），周菜谱据此把按钮显示为「已提醒」。 */
    fun getMealPrepKeys(): Flow<Set<String>> = reminderDao.getActiveReminders()
        .map { list ->
            list.filter { it.sourceKind == ReminderSource.MEAL_PREP }
                .mapNotNull { it.sourceKey }
                .toSet()
        }

    suspend fun findActiveBySourceKey(sourceKey: String): Reminder? =
        reminderDao.findActiveBySourceKey(sourceKey)

    /** 新建提醒；算不出未来的触发时刻（比如给已过去的时间建一次性提醒）返回 false。 */
    suspend fun create(reminder: Reminder): Boolean {
        val first = ReminderClock.firstFireAt(reminder) ?: return false
        reminderDao.insert(reminder.copy(nextFireAt = first))
        scheduler.reschedule()
        return true
    }

    /** 开关：关闭即暂停；重新开启时触发时刻已过的顺延到下一轮（一次性提醒时刻已过则不恢复）。 */
    suspend fun setEnabled(reminder: Reminder, enabled: Boolean) {
        if (enabled == reminder.enabled) return
        if (!enabled) {
            reminderDao.update(reminder.copy(enabled = false))
            scheduler.reschedule()
            return
        }
        val now = ZonedDateTime.now()
        val next = if (reminder.nextFireAt > now.toInstant().toEpochMilli()) {
            reminder.nextFireAt
        } else {
            ReminderClock.nextAfter(reminder, LocalDate.now(), now)
        } ?: return
        // 恢复时清掉已提醒标记，到点会重新通知。
        reminderDao.update(reminder.copy(enabled = true, nextFireAt = next, notifiedAt = 0))
        scheduler.reschedule()
    }

    /** 完成：一次性提醒记为完成；周期提醒从今天推进下一轮（提前做完也能提前重置节奏）。 */
    suspend fun complete(reminder: Reminder) {
        if (!reminder.enabled) return
        if (reminder.repeat == ReminderRepeatType.ONCE) {
            reminderDao.update(
                reminder.copy(enabled = false, completedAt = System.currentTimeMillis())
            )
        } else {
            val next = ReminderClock.nextAfter(reminder, LocalDate.now(), ZonedDateTime.now())
                ?: return
            reminderDao.update(reminder.copy(nextFireAt = next, notifiedAt = 0))
        }
        scheduler.reschedule()
    }

    suspend fun delete(reminder: Reminder) {
        reminderDao.delete(reminder)
        scheduler.reschedule()
    }
}
