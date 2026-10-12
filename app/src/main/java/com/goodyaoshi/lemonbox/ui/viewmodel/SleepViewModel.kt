package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.widget.WidgetDataProvider
import com.goodyaoshi.lemonbox.data.widget.WidgetSnapshot
import com.goodyaoshi.lemonbox.util.SleepReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 睡前氛围（F15）：今日速览（复用小组件的数据口径）+ 就寝提醒开关与时间。
 */
@HiltViewModel
class SleepViewModel @Inject constructor(
    private val widgetDataProvider: WidgetDataProvider,
    private val appPreferences: AppPreferences,
    private val scheduler: SleepReminderScheduler
) : ViewModel() {

    private val _review = MutableStateFlow<WidgetSnapshot?>(null)

    /** 今日速览：待买 / 今天的事 / 本月支出。 */
    val review: StateFlow<WidgetSnapshot?> = _review.asStateFlow()

    val bedtimeEnabled: StateFlow<Boolean> = appPreferences.bedtimeReminderEnabled
    val bedtimeTime: StateFlow<String> = appPreferences.bedtimeTime

    init {
        viewModelScope.launch {
            _review.value = widgetDataProvider.snapshot()
        }
    }

    fun setBedtimeEnabled(enabled: Boolean) {
        appPreferences.setBedtimeReminderEnabled(enabled)
        scheduler.reschedule()
    }

    fun setBedtimeTime(time: String) {
        appPreferences.setBedtimeTime(time)
        scheduler.reschedule()
    }
}
