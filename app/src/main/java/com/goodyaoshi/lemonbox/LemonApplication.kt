package com.goodyaoshi.lemonbox

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.ExpiryCheckWorker
import com.goodyaoshi.lemonbox.util.ImageUtil
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
    lateinit var itemRepository: ItemRepository

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var todoReminderScheduler: TodoReminderScheduler

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        ExpiryCheckWorker.ensureScheduled(this, appPreferences.reminderTimes.value)
        applicationScope.launch {
            // 家务提醒（解冻肉/洗衣服等）：启动时按最新数据重排一次性任务。
            todoReminderScheduler.reschedule()
        }
        applicationScope.launch {
            val cutoffTime = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            itemRepository.purgeDeletedItemsOlderThan(cutoffTime).forEach { item ->
                item.imagePathList().forEach(ImageUtil::deleteImage)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}