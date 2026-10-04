package com.goodyaoshi.lemonbox.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.settings.ThemeMode
import com.goodyaoshi.lemonbox.util.ExpiryCheckWorker
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

    fun setReminderLadder(days: List<Int>) {
        appPreferences.setReminderLadder(days)
    }

    /** 保存提醒时间后立即按新时间重排每日检查任务。 */
    fun setReminderTimes(times: List<String>) {
        appPreferences.setReminderTimes(times)
        ExpiryCheckWorker.reschedule(appContext, appPreferences.reminderTimes.value)
    }

    fun setThemeMode(mode: ThemeMode) {
        appPreferences.setThemeMode(mode)
    }
}