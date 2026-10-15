package com.goodyaoshi.lemonbox.util

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudPurger
import com.goodyaoshi.lemonbox.data.repository.AnniversaryRepository
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.data.repository.LocationRepository
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台维护：把历史遗留的同名重复项（重复种子 / 重复录入 / 旧版合并留下的）去重，
 * 并清理软删超过 30 天的墓碑与其图片。
 *
 * 这些是「算一次就够」的重活，以前放在每次冷启动里执行，划卡频繁的用户会反复白跑。
 * 现在统一收敛到这里，由 [MaintenanceWorker] 每天凌晨跑一次；冷启动只做一次「超过一天没算过就补算」。
 */
@Singleton
class AppMaintenance @Inject constructor(
    private val itemRepository: ItemRepository,
    private val anniversaryRepository: AnniversaryRepository,
    private val reminderRepository: ReminderRepository,
    private val locationRepository: LocationRepository,
    private val categoryRepository: CategoryRepository,
    private val ledgerRepository: LedgerRepository,
    private val cloudPurger: ImageCloudPurger,
    private val appPreferences: AppPreferences
) {

    suspend fun run() {
        val cutoff = System.currentTimeMillis() - TOMBSTONE_TTL_MS
        // 物理清除的墓碑：本地残留副本与云端对象一并收拾（云端可能已无本地副本，是唯一来源）。
        cloudPurger.purgeImagesOf(itemRepository.purgeDeletedItemsOlderThan(cutoff))
        // 纪念日与待办提醒的软删墓碑：30 天后物理清除，删除动作仍会先同步到对方。
        anniversaryRepository.purgeDeletedOlderThan(cutoff)
        reminderRepository.purgeDeletedOlderThan(cutoff)

        // 位置、家当分类、记账分类、账户与纪念日的同名去重。
        locationRepository.deduplicateLocations()
        categoryRepository.deduplicateCategories()
        ledgerRepository.deduplicateCategories()
        ledgerRepository.deduplicateAssets()
        anniversaryRepository.deduplicateAnniversaries()

        appPreferences.setLastMaintenanceAt(System.currentTimeMillis())
    }

    companion object {
        /** 墓碑保留时长：软删满 30 天后才物理清除。 */
        const val TOMBSTONE_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** 冷启动补算的间隔：距上次维护超过一天才在打开时补算一次。 */
        const val MAINTENANCE_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * 每天凌晨 3 点的后台维护任务：把去重 / 清理集中到夜里跑，减少用户打开应用时的等待与开销。
 * WorkManager 的周期任务会在设备重启后自动恢复，无需额外的开机广播。
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val maintenance: AppMaintenance
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        maintenance.run()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "app_maintenance"

        /** 应用启动时调用：设置（或保留）每天凌晨 3 点的维护任务。 */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MaintenanceWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(millisUntilNext3am(), TimeUnit.MILLISECONDS)
                    .build()
            )
        }

        /** 距离今天的下一个凌晨 3 点还有多少毫秒；已过则算到明天。 */
        private fun millisUntilNext3am(): Long {
            val now = ZonedDateTime.now()
            var next = now.toLocalDate().atTime(3, 0).atZone(now.zone)
            if (!next.isAfter(now)) {
                next = next.plusDays(1)
            }
            return Duration.between(now, next).toMillis()
        }
    }
}
