package com.goodyaoshi.lemonbox

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.goodyaoshi.lemonbox.data.imagecloud.CloudImageFetcherFactory
import com.goodyaoshi.lemonbox.data.imagecloud.CloudImageModelKeyer
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudConfig
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncScheduler
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncClient
import com.goodyaoshi.lemonbox.data.remote.SyncSessionStore
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
    lateinit var cloudImageFetcherFactory: CloudImageFetcherFactory

    @Inject
    lateinit var imageCloudConfig: ImageCloudConfig

    @Inject
    lateinit var cloudSyncScheduler: CloudSyncScheduler

    @Inject
    lateinit var syncSessionStore: SyncSessionStore

    @Inject
    lateinit var remoteSyncClient: RemoteSyncClient

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
        // 图片云端补传：登录即开启，任务本身只在不计费网络下运行。
        imageCloudSyncScheduler.syncSchedule(
            supported = imageCloudConfig.supported,
            enabled = syncSessionStore.isLoggedIn,
            backgroundSync = true
        )
        // 多设备同步：按「支持文字同步 + 已登录」排期；未登录视为纯本地，不注册后台唤醒。
        cloudSyncScheduler.syncSchedule(
            supported = imageCloudConfig.textSyncSupported,
            enabled = syncSessionStore.isLoggedIn
        )
        // 即时触发：回到前台 / 本地写入后同步。冷启动的首次 onStart 也在其覆盖内，
        // 故不再单独在此处启动一次同步。
        cloudSyncTrigger.start()
        applicationScope.launch {
            // 登录态保活：access_token 只有一小时，冷启动先续期；refresh_token 也失效时
            // 用加密保存的凭据静默重登。只要没主动退出登录，用户就不会再被要求输密码。
            remoteSyncClient.ensureSessionFresh()
            // 静默重登成功后登录态才为真，启动时那次排期按未登录算过，这里补排一次。
            imageCloudSyncScheduler.syncSchedule(
                supported = imageCloudConfig.supported,
                enabled = syncSessionStore.isLoggedIn,
                backgroundSync = true
            )
            cloudSyncScheduler.syncSchedule(
                supported = imageCloudConfig.textSyncSupported,
                enabled = syncSessionStore.isLoggedIn
            )
        }
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
     * 应用级图片加载器（F22 / P8）。
     *
     * 云端图片由自定义取图器 [CloudImageFetcherFactory] 加载（预签名 URL 需网络往返，
     * 挪进取图器的挂起阶段），其下载直接落进 ImageRestoreStore，持久缓存由它负责，
     * 因此不再配置 Coil 的磁盘缓存。内存缓存键经 [CloudImageModelKeyer] 固定为对象键
     * （内容哈希），同一张图无论取回多少次都命中同一份。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(CloudImageModelKeyer())
                add(cloudImageFetcherFactory)
            }
            .build()
}
