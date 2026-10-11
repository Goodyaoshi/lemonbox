package com.goodyaoshi.lemonbox

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.AnniversaryCheckWorker
import com.goodyaoshi.lemonbox.util.AppMaintenance
import com.goodyaoshi.lemonbox.util.ExpiryCheckWorker
import com.goodyaoshi.lemonbox.util.MaintenanceWorker
import com.goodyaoshi.lemonbox.util.NotificationHelper
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
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var todoReminderScheduler: TodoReminderScheduler

    @Inject
    lateinit var appMaintenance: AppMaintenance

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
        applicationScope.launch {
            // 待办提醒（解冻肉/洗衣服等）：启动时按最新数据重排一次性任务。
            todoReminderScheduler.reschedule()
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
}
