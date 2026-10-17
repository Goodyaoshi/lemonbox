package com.goodyaoshi.lemonbox.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 多设备同步的后台任务。
 *
 * 为什么需要后台：两台设备很少同时在线，只在打开 App 时同步会让数据迟到很久。
 * 周期任务让「对方上次改的东西」在我下次拿起手机前就到位。
 *
 * 三重收敛避免变成耗电/耗流量的后台活动：仅不计费网络（UNMETERED）；
 * 未登录 / 未开启多设备同步时直接成功退出；全程幂等，无变更时不上传。
 */
@HiltWorker
class CloudSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncManager: RemoteSyncManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // 未就绪不是错误（没配凭据 / debug 包），重试没有意义。
        // 同步失败也不下沉为 retry：下一轮周期任务自然会再试，
        // 指数退避在这里只会让一次网络抖动演变成短时间内的反复重试。
        syncManager.syncNow()
        return Result.success()
    }
}

/**
 * 周期同步调度器：注册一个约 1 小时一次的周期任务，约束为**任意联网**。
 *
 * 用 [ExistingPeriodicWorkPolicy.KEEP]：已排上就保留原有的。
 *
 * 为什么从「6 小时 + 仅 WiFi（UNMETERED）」放宽到「1 小时 + 任意联网（CONNECTED）」：
 * 前者在真实使用中几乎不触发——国产 ROM 对 WorkManager 周期任务限制严格，
 * 且 UNMETERED 意味着用移动数据时永远不跑，结果就是「改完对方几天看不到」。
 * 周期任务本只是兜底（即时性由 [CloudSyncTrigger] 的前台/写入触发承担），
 * 放宽后它至少能在后台真的跑起来。
 */
@Singleton
class CloudSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun schedule() {
        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(
            REPEAT_INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
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
     * 按当前条件决定「该排还是该撤」，冷启动与设置页开关共用这一处判断。
     * 正式包且用户已开启才排期。
     */
    fun syncSchedule(supported: Boolean, enabled: Boolean) {
        if (supported && enabled) schedule() else cancel()
    }

    private companion object {
        const val WORK_NAME = "cloud_sync"
        const val REPEAT_INTERVAL_HOURS = 1L
    }
}
