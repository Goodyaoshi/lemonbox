package com.goodyaoshi.lemonbox.data.imagecloud

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 图片云端补传的后台任务（F20）。
 *
 * 上传失败过去只提示一句、靠用户自己补，容易让「本地有、云端没有」的图长期存在——
 * 对「删本地、只留云端」的路线来说这是会真的丢图的前置风险，故加自动兜底。
 *
 * 三重收敛避免变成耗电/耗流量的后台活动：仅不计费网络（UNMETERED 约束）；
 * 用户可在设置页关掉；全程幂等，已传过的在增量判断里就被跳过。
 */
@HiltWorker
class ImageCloudSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncManager: ImageCloudSyncManager,
    private val preferences: ImageCloudPreferences
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // 用户关掉了后台补传：直接成功退出，不下沉为失败（失败会触发重试，与用户意愿相悖）。
        if (!preferences.backgroundSync.value) return Result.success()

        return when (val result = syncManager.syncPendingNow()) {
            // 未就绪（功能没开 / 缺凭据 / debug 包）不是错误，重试没有意义。
            SyncRunResult.NotReady -> Result.success()

            is SyncRunResult.Done ->
                // 有失败项才重试（WorkManager 会按指数退避重来）；
                // 全部成功或全部跳过都算完成，避免空转。
                if (result.allSucceeded) Result.success() else Result.retry()
        }
    }
}

/**
 * 补传任务的调度器：注册一个约 12 小时一次的周期任务，约束为**仅不计费网络**。
 *
 * 用 [ExistingPeriodicWorkPolicy.KEEP]：已排上就保留原有的，重置周期没有意义，
 * 反而会把「刚跑过」的任务又排到很久以后。取消用 [cancel]，对应设置页关掉开关。
 */
@Singleton
class ImageCloudSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun schedule() {
        val request = PeriodicWorkRequestBuilder<ImageCloudSyncWorker>(
            REPEAT_INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * 按当前条件决定「该排还是该撤」，冷启动与设置页开关共用这一处判断，
     * 免得两边的条件各写一份、日后改一处忘另一处。
     *
     * 三个条件全都要满足才排期：功能可用（debug 包不排）、用户已开启、且允许后台补传。
     */
    fun syncSchedule(supported: Boolean, enabled: Boolean, backgroundSync: Boolean) {
        if (supported && enabled && backgroundSync) schedule() else cancel()
    }

    private companion object {
        const val WORK_NAME = "image_cloud_sync"
        const val REPEAT_INTERVAL_HOURS = 12L
    }
}
