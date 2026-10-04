package com.goodyaoshi.lemonbox.util

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.goodyaoshi.lemonbox.data.local.dao.ReminderDao
import com.goodyaoshi.lemonbox.data.local.entity.ReminderRepeatType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 家务提醒的调度器：始终只保留一个对准「最近的 nextFireAt」的一次性任务，
 * 触发后由 [TodoReminderWorker] 推进到下一轮并再次调排，增删改后也即时重排。
 */
@Singleton
class TodoReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminderDao: ReminderDao
) {
    suspend fun reschedule() {
        val workManager = WorkManager.getInstance(context)
        val earliest = reminderDao.getEarliestNextFireAt()
        workManager.cancelUniqueWork(WORK_NAME)
        if (earliest == null) return
        val delay = (earliest - System.currentTimeMillis()).coerceAtLeast(0)
        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TodoReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
        )
    }

    companion object {
        private const val WORK_NAME = "todo_reminder_check"
    }
}

/**
 * 到点检查家务提醒：把到期的逐条发通知；一次性提醒标记完成，
 * 周期提醒推进到下一轮，最后重新调排下一个任务。
 * 通知与推进在同一次数据库更新里完成，天然不会重复提醒同一次触发。
 */
@HiltWorker
class TodoReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val reminderDao: ReminderDao,
    private val scheduler: TodoReminderScheduler
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val now = ZonedDateTime.now()
        val nowMillis = now.toInstant().toEpochMilli()
        val due = reminderDao.getActiveRemindersSync().filter { it.nextFireAt <= nowMillis }
        due.forEach { reminder ->
            NotificationHelper.showTodoReminder(
                context = applicationContext,
                title = reminder.title,
                note = reminder.note,
                reminderId = reminder.id
            )
            when (reminder.repeat) {
                ReminderRepeatType.ONCE -> reminderDao.update(
                    reminder.copy(enabled = false, completedAt = nowMillis)
                )

                else -> {
                    val anchor = Instant.ofEpochMilli(reminder.nextFireAt)
                        .atZone(now.zone)
                        .toLocalDate()
                    val next = ReminderClock.nextAfter(reminder, anchor, now)
                    // 周期提醒总有下一轮；算不出（如每周几被清空）就停用，避免空转。
                    if (next == null) {
                        reminderDao.update(reminder.copy(enabled = false))
                    } else {
                        reminderDao.update(reminder.copy(nextFireAt = next))
                    }
                }
            }
        }
        scheduler.reschedule()
        return Result.success()
    }
}
