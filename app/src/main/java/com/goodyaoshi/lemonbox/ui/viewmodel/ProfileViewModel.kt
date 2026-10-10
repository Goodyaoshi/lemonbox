package com.goodyaoshi.lemonbox.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.backup.AppBackupManager
import com.goodyaoshi.lemonbox.data.backup.toUserMessage
import com.goodyaoshi.lemonbox.data.repository.AnniversaryRepository
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class BackupUiState(
    val isBusy: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val backupManager: AppBackupManager,
    reminderRepository: ReminderRepository,
    anniversaryRepository: AnniversaryRepository
) : ViewModel() {

    // 家当域的计数（待买 / 临期 / 回收站）已随「家当域」迁到 SearchViewModel，此处不再保留。

    /** 今天（含已提醒还没完成）的待办数量：与首页「今天的事」的提醒口径一致。 */
    val dueReminderCount: StateFlow<Int> = reminderRepository.getActiveReminders()
        .map { list ->
            val endOfToday = LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            list.count { it.nextFireAt < endOfToday }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 纪念日数量：「常用」组纪念日入口的徽标。 */
    val anniversaryCount: StateFlow<Int> = anniversaryRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _backupState = MutableStateFlow(BackupUiState())
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    fun exportBackup(targetUri: Uri) {
        viewModelScope.launch {
            runBackupAction(successMessage = "备份导出完成") {
                backupManager.exportBackup(targetUri)
            }
        }
    }

    fun importBackup(sourceUri: Uri) {
        viewModelScope.launch {
            _backupState.value = _backupState.value.copy(isBusy = true, message = null)
            runCatching { backupManager.importBackup(sourceUri) }
                .onSuccess { result ->
                    _backupState.value = _backupState.value.copy(
                        isBusy = false,
                        message = result.toUserMessage()
                    )
                }
                .onFailure { error ->
                    _backupState.value = _backupState.value.copy(
                        isBusy = false,
                        message = error.message ?: "导入失败，请稍后再试"
                    )
                }
        }
    }

    fun consumeBackupMessage() {
        _backupState.value = _backupState.value.copy(message = null)
    }

    private suspend fun runBackupAction(
        successMessage: String,
        action: suspend () -> Unit
    ) {
        _backupState.value = _backupState.value.copy(isBusy = true, message = null)
        runCatching { action() }
            .onSuccess {
                _backupState.value = _backupState.value.copy(isBusy = false, message = successMessage)
            }
            .onFailure { error ->
                _backupState.value = _backupState.value.copy(
                    isBusy = false,
                    message = error.message ?: "操作失败，请稍后再试"
                )
            }
    }
}
