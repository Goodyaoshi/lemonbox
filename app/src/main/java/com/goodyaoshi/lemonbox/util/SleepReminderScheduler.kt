package com.goodyaoshi.lemonbox.util

import android.content.Context
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 就寝提醒调度入口（F15）：把「读偏好 → 排 / 撤 WorkManager 任务」收在一处，
 * 供应用启动对齐与设置在页改动后重排复用。
 */
@Singleton
class SleepReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences
) {

    /** 启动时对齐：开启则确保任务在，关闭则取消。保持已有排期不动。 */
    fun sync() {
        if (appPreferences.bedtimeReminderEnabled.value) {
            SleepReminderWorker.ensureScheduled(context, appPreferences.bedtimeTime.value)
        } else {
            SleepReminderWorker.cancel(context)
        }
    }

    /** 用户切换开关或修改时间后重排。 */
    fun reschedule() {
        if (appPreferences.bedtimeReminderEnabled.value) {
            SleepReminderWorker.reschedule(context, appPreferences.bedtimeTime.value)
        } else {
            SleepReminderWorker.cancel(context)
        }
    }
}
