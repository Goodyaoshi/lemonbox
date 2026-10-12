package com.goodyaoshi.lemonbox.util

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.goodyaoshi.lemonbox.widget.LemonWidgetUpdater
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * 小组件后台刷新（F04）：每 30 分钟拉一次最新数据重绘桌面组件，
 * 让待买 / 今日 / 本月支出在用户不动手的情况下也保持较新。
 * 系统自带的 updatePeriodMillis 最短也是 30 分钟，这里用 WorkManager 保证可靠性。
 */
@HiltWorker
class WidgetRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val updater: LemonWidgetUpdater
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        updater.refresh()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "widget_refresh"

        /** 应用启动时调用：保证周期刷新任务存在，已存在则保持原排期。 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
