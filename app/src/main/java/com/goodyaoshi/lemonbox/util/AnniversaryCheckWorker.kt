package com.goodyaoshi.lemonbox.util

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.goodyaoshi.lemonbox.data.repository.AnniversaryRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 纪念日检查：与到期提醒共用同一组提醒时间点（reminderTimes），
 * 每个时间点跑一次，把提前提醒阶梯命中的纪念日合并成一条通知。
 * 去重键带上时间点与日期，由 [Anniversary.lastNotifiedDate] 逐条记录。
 */
@HiltWorker
class AnniversaryCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val anniversaryRepository: AnniversaryRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val today = LocalDate.now()
        // 与 ExpiryCheckWorker 一致：去重键含时间点，早/晚两个档位不会互相顶掉。
        val slot = inputData.getString(KEY_REMINDER_TIME)
        val notifyKey = if (slot.isNullOrBlank()) today.toString() else "$today@$slot"

        val due = anniversaryRepository.getActiveSnapshot().mapNotNull { anniversary ->
            if (anniversary.lastNotifiedDate == notifyKey) return@mapNotNull null
            if (!AnniversaryClock.isDueOn(anniversary, today)) return@mapNotNull null
            val days = AnniversaryClock.daysUntil(anniversary, today) ?: 0L
            anniversary to days
        }
        if (due.isEmpty()) {
            return Result.success()
        }

        NotificationHelper.showAnniversarySummary(
            applicationContext,
            due.map { (anniversary, days) ->
                NotificationHelper.AnniversaryNotificationItem(
                    name = anniversary.name,
                    lineText = if (days == 0L) "就是今天" else "还有 $days 天"
                )
            }
        )
        due.forEach { (anniversary, _) ->
            anniversaryRepository.markNotified(anniversary.id, notifyKey)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME_PREFIX = "anniversary_check_"
        private const val WORK_TAG = "anniversary_check"
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
         * 未选中的时间点会被取消。
         */
        private fun applySchedule(
            context: Context,
            times: List<String>,
            policy: ExistingPeriodicWorkPolicy
        ) {
            val workManager = WorkManager.getInstance(context)
            val wanted = times.toSet()
            AppPreferences.REMINDER_TIME_OPTIONS.forEach { time ->
                if (time !in wanted) {
                    workManager.cancelUniqueWork(workName(time))
                    return@forEach
                }
                val delay = millisUntilNext(time)
                if (delay < 0) return@forEach
                val request = PeriodicWorkRequestBuilder<AnniversaryCheckWorker>(24, TimeUnit.HOURS)
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
