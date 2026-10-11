package com.goodyaoshi.lemonbox.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 开机广播：设备重启后 AlarmManager 的精确闹钟会被系统全部清空，
 * 若不重排，待办提醒会一直静默到用户下次主动打开应用。
 *
 * 这里在开机（含部分厂商的快速开机广播）后重排一次：
 * - 周期任务（到期 / 纪念日 / 后台维护）走 WorkManager 会自动恢复，这里以 KEEP 策略再补一次兜底；
 * - 待办提醒的精确闹钟必须手动重排，否则重启后不再触发。
 *
 * 保活前台服务不在这里拉起：它只在用户打开应用时启动（见 MainActivity），
 * 以免为了「重启后自启」而在开机就常驻一个前台服务、重新占用内存。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var todoReminderScheduler: TodoReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT_POWERON) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val times = appPreferences.reminderTimes.value
                ExpiryCheckWorker.ensureScheduled(context, times)
                AnniversaryCheckWorker.ensureScheduled(context, times)
                MaintenanceWorker.schedule(context)
                todoReminderScheduler.reschedule()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        /** 部分厂商 ROM（如 HTC / 部分 MTK 机型）的「快速开机」广播。 */
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
