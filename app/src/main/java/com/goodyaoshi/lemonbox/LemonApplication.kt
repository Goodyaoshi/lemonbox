package com.goodyaoshi.lemonbox

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudConfig
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudPreferences
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncScheduler
import com.goodyaoshi.lemonbox.data.repository.LedgerPlanningRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.sync.CloudSyncScheduler
import com.goodyaoshi.lemonbox.data.sync.CloudSyncTrigger
import com.goodyaoshi.lemonbox.util.AnniversaryCheckWorker
import com.goodyaoshi.lemonbox.util.AppMaintenance
import com.goodyaoshi.lemonbox.util.ExpiryCheckWorker
import com.goodyaoshi.lemonbox.util.MaintenanceWorker
import com.goodyaoshi.lemonbox.util.NotificationHelper
import com.goodyaoshi.lemonbox.util.RecurringLedgerWorker
import com.goodyaoshi.lemonbox.util.SleepReminderScheduler
import com.goodyaoshi.lemonbox.util.TodoReminderScheduler
import com.goodyaoshi.lemonbox.util.WidgetRefreshWorker
import com.goodyaoshi.lemonbox.widget.LemonWidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LemonApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var todoReminderScheduler: TodoReminderScheduler

    @Inject
    lateinit var appMaintenance: AppMaintenance

    @Inject
    lateinit var ledgerPlanningRepository: LedgerPlanningRepository

    @Inject
    lateinit var widgetUpdater: LemonWidgetUpdater

    @Inject
    lateinit var sleepReminderScheduler: SleepReminderScheduler

    @Inject
    lateinit var imageCloudSyncScheduler: ImageCloudSyncScheduler

    @Inject
    lateinit var imageCloudPreferences: ImageCloudPreferences

    @Inject
    lateinit var imageCloudConfig: ImageCloudConfig

    @Inject
    lateinit var cloudSyncScheduler: CloudSyncScheduler

    @Inject
    lateinit var cloudSyncTrigger: CloudSyncTrigger

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        ExpiryCheckWorker.ensureScheduled(this, appPreferences.reminderTimes.value)
        // 纪念日提前提醒与到期提醒共用同一组时间点，一起排期。
        AnniversaryCheckWorker.ensureScheduled(this, appPreferences.reminderTimes.value)
        // 去重 / 清理这类重活改为每天凌晨跑一次，见 MaintenanceWorker；
        // 提醒保活前台服务只在用户打开应用时拉起（见 MainActivity），避免后台常驻占用内存。
        MaintenanceWorker.schedule(this)
        // 周期记账（房租/工资等）：每 6 小时检查一次到点规则。
        RecurringLedgerWorker.schedule(this)
        // 桌面小组件（F04）：后台每 30 分钟刷新一次。
        WidgetRefreshWorker.schedule(this)
        // 就寝提醒（F15）：按偏好对齐任务（开启则排期，关闭则取消）。
        sleepReminderScheduler.sync()
        // 图片云端补传（F20）：仅当「正式包 + 用户已开启 + 允许后台补传」三者齐备才排期，
        // 否则撤掉——避免给没在用该功能的人留下无谓的后台唤醒。
        imageCloudSyncScheduler.syncSchedule(
            supported = imageCloudConfig.supported,
            enabled = imageCloudPreferences.enabled.value,
            backgroundSync = imageCloudPreferences.backgroundSync.value
        )
        // 多设备同步：按「正式包 + 用户已开启」排期；未开启则撤掉。
        // 用云同步自己的开关，不看图片上传开关——两者取舍不同。
        cloudSyncScheduler.syncSchedule(
            supported = imageCloudConfig.supported,
            enabled = imageCloudPreferences.cloudSyncEnabled.value
        )
        // 即时触发：回到前台 / 本地写入后同步。冷启动的首次 onStart 也在其覆盖内，
        // 故不再单独在此处启动一次同步。
        cloudSyncTrigger.start()
        applicationScope.launch {
            // 打开应用时顺手刷新一次桌面小组件，让数据立刻最新。
            widgetUpdater.refresh()
        }
        applicationScope.launch {
            // 待办提醒（解冻肉/洗衣服等）：启动时按最新数据重排一次性任务。
            todoReminderScheduler.reschedule()
        }
        applicationScope.launch {
            // 周期记账：打开应用时立即补生成到点账单，保证用户第一时间看到。
            ledgerPlanningRepository.fireDueRules()
        }
        applicationScope.launch {
            // 冷启动补算：距上次维护超过一天才在打开时补跑一次，避免重复重活。
            val elapsed = System.currentTimeMillis() - appPreferences.lastMaintenanceAt.value
            if (elapsed >= AppMaintenance.MAINTENANCE_INTERVAL_MS) {
                appMaintenance.run()
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * 图片加载器的磁盘缓存（F20）。
     *
     * 为什么必须显式配置：云端图片的预签名 URL 每次都不同，若沿用「以 URL 为缓存键」的默认行为，
     * 缓存永远不命中、每帧都可能重新下载，离线更是一张都看不到。配合
     * [com.goodyaoshi.lemonbox.data.imagecloud.ImageUrlResolver] 把缓存键固定为对象键
     * （内容哈希），同一张图无论取回多少次都命中同一份磁盘缓存。
     *
     * 容量取 256MB：家当图片以缩略浏览为主，这个量级足以覆盖常用图；超出后 Coil 按 LRU 淘汰。
     * 沿用 Coil 的其他默认行为（不额外开 crossfade 等），避免顺带改变现有本地图片的观感。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(IMAGE_DISK_CACHE_DIR))
                    .maxSizeBytes(IMAGE_DISK_CACHE_MAX_BYTES)
                    .build()
            }
            .build()

    private companion object {
        const val IMAGE_DISK_CACHE_DIR = "image_cache"
        const val IMAGE_DISK_CACHE_MAX_BYTES = 256L * 1024 * 1024
    }
}
