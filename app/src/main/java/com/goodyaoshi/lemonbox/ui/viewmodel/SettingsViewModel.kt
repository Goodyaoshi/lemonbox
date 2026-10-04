package com.goodyaoshi.lemonbox.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.settings.ThemeMode
import com.goodyaoshi.lemonbox.util.ExpiryCheckWorker
import com.goodyaoshi.lemonbox.util.TodoKeepAliveService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val appPreferences: AppPreferences
) : ViewModel() {

    val reminderLadder: StateFlow<List<Int>> = appPreferences.reminderLadder

    val reminderTimes: StateFlow<List<String>> = appPreferences.reminderTimes

    val themeMode: StateFlow<ThemeMode> = appPreferences.themeMode

    val keepAliveEnabled: StateFlow<Boolean> = appPreferences.keepAliveEnabled

    val customReminderTimes: StateFlow<Set<String>> = appPreferences.customReminderTimes

    val mealPrepDayShift: StateFlow<Int> = appPreferences.mealPrepDayShift

    val mealPrepFireTime: StateFlow<String> = appPreferences.mealPrepFireTime

    fun setReminderLadder(days: List<Int>) {
        appPreferences.setReminderLadder(days)
    }

    /** 自定义时间点：加入候选池并自动勾选。 */
    fun addCustomReminderTime(time: String) {
        appPreferences.addCustomReminderTime(time)
    }

    /** 取消勾选自定义时间点时连候选池一起移除，保持时间列表干净。 */
    fun removeCustomReminderTime(time: String) {
        appPreferences.removeCustomReminderTime(time)
    }

    fun setMealPrepDayShift(dayShift: Int) {
        appPreferences.setMealPrepDayShift(dayShift)
    }

    fun setMealPrepFireTime(time: String) {
        appPreferences.setMealPrepFireTime(time)
    }

    /** 保存提醒时间后立即按新时间重排每日检查任务。 */
    fun setReminderTimes(times: List<String>) {
        appPreferences.setReminderTimes(times)
        ExpiryCheckWorker.reschedule(appContext, appPreferences.reminderTimes.value)
    }

    fun setThemeMode(mode: ThemeMode) {
        appPreferences.setThemeMode(mode)
    }

    /** 切换保活开关：开则立刻拉起前台服务，关则停掉常驻通知。 */
    fun setKeepAliveEnabled(enabled: Boolean) {
        appPreferences.setKeepAliveEnabled(enabled)
        val intent = Intent(appContext, TodoKeepAliveService::class.java)
        try {
            if (enabled) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.stopService(intent)
            }
        } catch (_: Exception) {
            // 后台启动前台服务受限时忽略，下次打开 App 会按开关状态重新拉起。
        }
    }
}