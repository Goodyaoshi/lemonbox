package com.goodyaoshi.lemonbox.util

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.goodyaoshi.lemonbox.data.local.dao.ItemDao
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

@HiltWorker
class ExpiryCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val itemDao: ItemDao,
    private val appPreferences: AppPreferences
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val today = LocalDate.now().toString()
        // 每个提醒时间点当天各自提醒一次：去重键带上时间点，早/晚两个档位不会互相顶掉。
        val slot = inputData.getString(KEY_REMINDER_TIME)
        val notifyKey = if (slot.isNullOrBlank()) today else "$today@$slot"
        if (appPreferences.expiryNotifiedDate() == notifyKey) {
            return Result.success()
        }

        // 阶梯式提醒：剩余天数正好落在该物品的提醒阶梯上（或已到期）才提醒。
        val globalLadder = appPreferences.reminderLadder.value
        val expiring = itemDao.getActiveItemsSync()
            .mapNotNull { item ->
                val expireTime = item.expireTime
                if (expireTime == null || expireTime <= 0) {
                    return@mapNotNull null
                }
                val daysLeft = DateUtil.daysUntil(expireTime)
                val ladder = Item.decodeReminderDays(item.reminderDays)
                    .ifEmpty { globalLadder }
                val shouldNotify = daysLeft <= 0 || ladder.any { it.toLong() == daysLeft }
                if (!shouldNotify) {
                    null
                } else {
                    NotificationHelper.ExpiryNotificationItem(
                        name = item.name,
                        daysLeft = daysLeft
                    )
                }
            }
            .sortedBy { it.daysLeft }

        if (expiring.isEmpty()) {
            return Result.success()
        }

        NotificationHelper.showExpirySummary(applicationContext, expiring)
        appPreferences.setExpiryNotifiedDate(notifyKey)
        return Result.success()
    }

    companion object {
        private const val LEGACY_WORK_NAME = "expiry_check"
        private const val WORK_NAME_PREFIX = "expiry_check_"
        private const val WORK_TAG = "expiry_check"
        private const val KEY_REMINDER_TIME = "reminder_time"

        /** 应用启动时调用：补齐缺失的时间点任务，已存在的任务保持原排期不动。 */
        fun ensureScheduled(context: Context, times: List<String>) {
            applySchedule(context, times, ExistingPeriodicWorkPolicy.KEEP)
        }

        /** 用户修改提醒时间后调用：按新时间重新排期。 */
        fun reschedule(context: Context, times: List<String>) {
            applySchedule(context, times, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
        }

        /**
         * 每个提醒时间点一个独立的唯一周期任务：首次延迟到当天的该时刻，之后每 24 小时一次。
         * 未选中的时间点会被取消，旧版的 12 小时任务也会被清理。
         */
        private fun applySchedule(
            context: Context,
            times: List<String>,
            policy: ExistingPeriodicWorkPolicy
        ) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(LEGACY_WORK_NAME)

            val wanted = times.toSet()
            AppPreferences.REMINDER_TIME_OPTIONS.forEach { time ->
                if (time !in wanted) {
                    workManager.cancelUniqueWork(workName(time))
                    return@forEach
                }
                val delay = millisUntilNext(time)
                if (delay < 0) return@forEach
                val request = PeriodicWorkRequestBuilder<ExpiryCheckWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY_REMINDER_TIME to time))
                    .addTag(WORK_TAG)
                    .build()
                workManager.enqueueUniquePeriodicWork(workName(time), policy, request)
            }
        }

        private fun workName(time: String) = "$WORK_NAME_PREFIX$time"

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
