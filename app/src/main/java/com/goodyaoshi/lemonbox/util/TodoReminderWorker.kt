package com.goodyaoshi.lemonbox.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.goodyaoshi.lemonbox.data.local.dao.ReminderDao
import com.goodyaoshi.lemonbox.data.local.entity.ReminderRepeatType
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import androidx.core.app.NotificationManagerCompat
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 待办提醒的调度器：主通道是 [AlarmManager] 的 `setExactAndAllowWhileIdle` 精确闹钟
 * （Doze/省电模式下也能准点触发，解决 WorkManager 任务被系统推迟几十分钟的问题），
 * 同时保留一个对准「最近的 nextFireAt」的 WorkManager 一次性任务兜底。
 * 两条通道每次触发都会重排对方：极端省电策略吞掉闹钟时，兜底任务恢复后仍能补发；
 * 双通道同时到期时由 [ReminderFirer] 的进程内互斥锁防重。
 */
@Singleton
class TodoReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminderDao: ReminderDao
) {
    private val alarmPendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, TodoAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    suspend fun reschedule() {
        val earliest = reminderDao.getEarliestNextFireAt()
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(WORK_NAME)
        cancelAlarm()

        if (earliest == null) return
        val delay = (earliest - System.currentTimeMillis()).coerceAtLeast(0)
        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TodoReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
        )
        setAlarm(earliest)
    }

    /** 精确闹钟主通道；系统不给精确权限时退化为窗口闹钟，仍比纯 WorkManager 及时。 */
    private fun setAlarm(triggerAtMillis: Long) {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent
                )
            } else {
                alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, EXACT_FALLBACK_WINDOW_MS,
                    alarmPendingIntent
                )
            }
        } catch (_: SecurityException) {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, EXACT_FALLBACK_WINDOW_MS,
                alarmPendingIntent
            )
        }
    }

    private fun cancelAlarm() {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(alarmPendingIntent)
    }

    companion object {
        private const val WORK_NAME = "todo_reminder_check"
        private const val EXACT_FALLBACK_WINDOW_MS = 60_000L
    }
}

/**
 * 到期提醒的统一触发逻辑：把到期的逐条发通知；一次性提醒只标记「已提醒」、
 * 保持待办等用户手动完成；周期提醒推进到下一轮，最后重新调排下一个任务。
 * WorkManager 与闹钟广播两条通道都走这里，进程内互斥串行，
 * 保证同一次触发只发一条通知。
 */
@Singleton
class ReminderFirer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminderDao: ReminderDao,
    private val scheduler: TodoReminderScheduler
) {
    private val mutex = Mutex()

    suspend fun fireDueReminders() = mutex.withLock {
        val now = ZonedDateTime.now()
        val nowMillis = now.toInstant().toEpochMilli()
        val due = reminderDao.getActiveRemindersSync()
            .filter { it.nextFireAt <= nowMillis && it.notifiedAt == 0L }
        due.forEach { reminder ->
            NotificationHelper.showTodoReminder(
                context = context,
                title = reminder.title,
                note = reminder.note,
                reminderId = reminder.id
            )
            when (reminder.repeat) {
                // 待办语义：只标记已提醒，保持进行中，等用户手动点完成。
                ReminderRepeatType.ONCE -> reminderDao.update(
                    reminder.copy(notifiedAt = nowMillis)
                )

                else -> {
                    val anchor = Instant.ofEpochMilli(reminder.nextFireAt)
                        .atZone(now.zone)
                        .toLocalDate()
                    val next = ReminderClock.nextAfter(reminder, anchor, now)
                    // 周期提醒总有下一轮；算不出（如每周几被清空）就停用，避免空转。
                    if (next == null) {
                        reminderDao.update(reminder.copy(enabled = false, notifiedAt = nowMillis))
                    } else {
                        reminderDao.update(reminder.copy(nextFireAt = next, notifiedAt = 0))
                    }
                }
            }
        }
        scheduler.reschedule()
    }
}

/**
 * 通知上的「完成」按钮：直接把这条待办标记完成并撤掉通知，不必进 App。
 */
@AndroidEntryPoint
class TodoCompleteReceiver : BroadcastReceiver() {

    @Inject
    lateinit var reminderRepository: ReminderRepository

    @Inject
    lateinit var reminderDao: ReminderDao

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (reminderId <= 0L) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                reminderDao.findActiveById(reminderId)?.let { reminder ->
                    reminderRepository.complete(reminder)
                }
                NotificationManagerCompat.from(context).cancel(
                    NotificationHelper.TODO_NOTIFICATION_ID_BASE.toInt() + reminderId.toInt()
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"

        fun intent(context: Context, reminderId: Long): Intent =
            Intent(context, TodoCompleteReceiver::class.java)
                .putExtra(EXTRA_REMINDER_ID, reminderId)
    }
}

/**
 * 精确闹钟到点的广播：`setExactAndAllowWhileIdle` 触发时系统临时放行进程，
 * 在广播里直接处理最可靠，不依赖 WorkManager 的二次调度。
 */
@AndroidEntryPoint
class TodoAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var firer: ReminderFirer

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                firer.fireDueReminders()
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/**
 * WorkManager 兜底通道：闹钟被极端省电策略吞掉/取消时，
 * 进程恢复后仍能补发到期提醒。
 */
@HiltWorker
class TodoReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val firer: ReminderFirer
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        firer.fireDueReminders()
        return Result.success()
    }
}
