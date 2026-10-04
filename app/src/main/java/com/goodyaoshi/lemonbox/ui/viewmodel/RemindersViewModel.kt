package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.ReminderRepeatType
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val reminderRepository: ReminderRepository
) : ViewModel() {

    /** 全量提醒：进行中的在前，已暂停/已完成的垫底。 */
    val reminders: StateFlow<List<Reminder>> = reminderRepository.getAllReminders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 新建提醒；时刻已过等非法输入返回 false，由页面提示。 */
    fun create(
        title: String,
        note: String,
        repeatType: ReminderRepeatType,
        targetDate: String?,
        intervalDays: Int,
        weekdays: List<Int>,
        fireTime: String,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val saved = reminderRepository.create(
                Reminder(
                    title = title.trim(),
                    note = note.trim(),
                    repeatType = repeatType.name,
                    intervalDays = intervalDays.coerceAtLeast(1),
                    weekdays = Reminder.encodeWeekdays(weekdays),
                    fireTime = fireTime,
                    targetDate = targetDate
                )
            )
            onResult(saved)
        }
    }

    /** 暂停 / 恢复。 */
    fun toggleEnabled(reminder: Reminder) {
        viewModelScope.launch {
            reminderRepository.setEnabled(reminder, !reminder.enabled)
        }
    }

    /** 完成：一次性记为完成，周期提醒推进下一轮。 */
    fun complete(reminder: Reminder) {
        viewModelScope.launch {
            reminderRepository.complete(reminder)
        }
    }

    fun delete(reminder: Reminder) {
        viewModelScope.launch {
            reminderRepository.delete(reminder)
        }
    }
}
