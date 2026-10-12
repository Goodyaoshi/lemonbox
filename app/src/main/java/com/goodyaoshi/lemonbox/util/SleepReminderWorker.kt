package com.goodyaoshi.lemonbox.util

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 就寝提醒（F15）：按用户设定的就寝时间，每晚推送一条「该休息啦」的提醒。
 * 首次触发对齐到当天的该时刻，之后每 24 小时一次；按天去重，避免重复打扰。
 */
@HiltWorker
class SleepReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val appPreferences: AppPreferences
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (!appPreferences.bedtimeReminderEnabled.value) return Result.success()

        val time = appPreferences.bedtimeTime.value
        val notifyKey = "${LocalDate.now()}@$time"
        if (appPreferences.bedtimeNotifiedDate() == notifyKey) {
            return Result.success()
        }

        NotificationHelper.showSleepReminder(applicationContext, appPreferences.nickname.value)
        appPreferences.setBedtimeNotifiedDate(notifyKey)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "sleep_reminder"

        /** 应用启动时调用：保证任务存在，已存在则保持原排期不动。 */
        fun ensureScheduled(context: Context, time: String) {
            applySchedule(context, time, ExistingPeriodicWorkPolicy.KEEP)
        }

        /** 用户修改就寝时间后调用：按新时间重排。 */
        fun reschedule(context: Context, time: String) {
            applySchedule(context, time, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun applySchedule(
            context: Context,
            time: String,
            policy: ExistingPeriodicWorkPolicy
        ) {
            val delay = millisUntilNext(time)
            if (delay < 0) return
            val request = PeriodicWorkRequestBuilder<SleepReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        }

        /** 距离今天的下一个 time 还有多少毫秒；已过则算到明天同一时刻。 */
        private fun millisUntilNext(time: String): Long {
            val parts = time.split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: return -1
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: return -1
            if (hour !in 0..23 || minute !in 0..59) return -1

            val now = ZonedDateTime.now()
            var next = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
            if (!next.isAfter(now)) {
                next = next.plusDays(1)
            }
            return Duration.between(now, next).toMillis()
        }
    }
}
