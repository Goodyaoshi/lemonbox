package com.goodyaoshi.lemonbox

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.goodyaoshi.lemonbox.data.repository.AnniversaryRepository
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.data.repository.LocationRepository
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.AnniversaryCheckWorker
import com.goodyaoshi.lemonbox.util.ExpiryCheckWorker
import com.goodyaoshi.lemonbox.util.ImageUtil
import com.goodyaoshi.lemonbox.util.NotificationHelper
import com.goodyaoshi.lemonbox.util.TodoKeepAliveService
import com.goodyaoshi.lemonbox.util.TodoReminderScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LemonApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var itemRepository: ItemRepository

    @Inject
    lateinit var anniversaryRepository: AnniversaryRepository

    @Inject
    lateinit var reminderRepository: ReminderRepository

    @Inject
    lateinit var locationRepository: LocationRepository

    @Inject
    lateinit var categoryRepository: CategoryRepository

    @Inject
    lateinit var ledgerRepository: LedgerRepository

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var todoReminderScheduler: TodoReminderScheduler

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        ExpiryCheckWorker.ensureScheduled(this, appPreferences.reminderTimes.value)
        // 纪念日提前提醒与到期提醒共用同一组时间点，一起排期。
        AnniversaryCheckWorker.ensureScheduled(this, appPreferences.reminderTimes.value)
        startKeepAliveService()
        applicationScope.launch {
            // 待办提醒（解冻肉/洗衣服等）：启动时按最新数据重排一次性任务。
            todoReminderScheduler.reschedule()
        }
        applicationScope.launch {
            val cutoffTime = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            itemRepository.purgeDeletedItemsOlderThan(cutoffTime).forEach { item ->
                item.imagePathList().forEach(ImageUtil::deleteImage)
            }
            // 纪念日与待办提醒的软删墓碑：30 天后物理清除，删除动作仍会先同步到对方。
            anniversaryRepository.purgeDeletedOlderThan(cutoffTime)
            reminderRepository.purgeDeletedOlderThan(cutoffTime)
        }
        applicationScope.launch {
            // 清理历史遗留的同名重复项（重复种子/重复录入/旧版合并留下的）：
            // 位置、家当分类、记账分类与账户。
            locationRepository.deduplicateLocations()
            categoryRepository.deduplicateCategories()
            ledgerRepository.deduplicateCategories()
            ledgerRepository.deduplicateAssets()
        }
    }

    /**
     * 启动提醒保活前台服务（默认开启，可在设置关闭）。
     * 前台身份能防住 MIUI 等划卡清理的 force stop，避免提醒闹钟被连带取消。
     * 仅在前台启动时拉起；闹钟在后台拉起进程的场景下会因系统限制失败，静默忽略即可。
     */
    private fun startKeepAliveService() {
        if (!appPreferences.keepAliveEnabled.value) return
        val intent = Intent(this, TodoKeepAliveService::class.java)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (_: Exception) {
            // 后台被拉起进程时系统不允许启动前台服务，忽略；下次用户打开 App 会再拉起。
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}