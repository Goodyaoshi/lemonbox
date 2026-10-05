package com.goodyaoshi.lemonbox.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.backup.AppBackupManager
import com.goodyaoshi.lemonbox.data.backup.toUserMessage
import com.goodyaoshi.lemonbox.data.local.dao.ItemDao
import com.goodyaoshi.lemonbox.data.repository.AnniversaryRepository
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileViewModel @Inject constructor(
    itemDao: ItemDao,
    private val backupManager: AppBackupManager,
    appPreferences: AppPreferences,
    reminderRepository: ReminderRepository,
    anniversaryRepository: AnniversaryRepository
) : ViewModel() {

    private val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000

    val trashCount: StateFlow<Int> = itemDao
        .getRecycleCount(System.currentTimeMillis() - thirtyDaysMs)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 待买数量：「常用」组待买清单的徽标。 */
    val toBuyCount: StateFlow<Int> = itemDao
        .getToBuyCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 临期数量：阈值与「即将过期」口径一致（提醒阶梯的最大天数）。 */
    val expiringCount: StateFlow<Int> = appPreferences.reminderDays
        .flatMapLatest { days ->
            itemDao.getExpiringCount(
                System.currentTimeMillis() + days * 24L * 60 * 60 * 1000
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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
