package com.goodyaoshi.lemonbox.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.backup.AppBackupManager
import com.goodyaoshi.lemonbox.data.backup.toUserMessage
import com.goodyaoshi.lemonbox.data.repository.AnniversaryRepository
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
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
    /** 处理中弹窗的标题，区分导出 / 导入，让等待有明确所指。 */
    val busyLabel: String? = null,
    val message: String? = null,
    /** 结果是否为失败：用于成功 / 失败分级提示（文案与语气不同）。 */
    val isError: Boolean = false
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val backupManager: AppBackupManager,
    reminderRepository: ReminderRepository,
    anniversaryRepository: AnniversaryRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    // 家当域的计数（待买 / 临期 / 回收站）已随「家当域」迁到 SearchViewModel，此处不再保留。

    /** 她的昵称：头像首字与「我的」页称呼都取自这里，可在「我的」页修改。 */
    val nickname: StateFlow<String> = appPreferences.nickname

    fun setNickname(value: String) {
        appPreferences.setNickname(value)
    }

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
            runBackupAction(successMessage = "备份导出完成", busyLabel = "正在导出备份包…") {
                backupManager.exportBackup(targetUri)
            }
        }
    }

    fun importBackup(sourceUri: Uri) {
        viewModelScope.launch {
            _backupState.value = _backupState.value.copy(
                isBusy = true,
                busyLabel = "正在导入并合并…",
                message = null,
                isError = false
            )
            runCatching { backupManager.importBackup(sourceUri) }
                .onSuccess { result ->
                    _backupState.value = _backupState.value.copy(
                        isBusy = false,
                        busyLabel = null,
                        message = result.toUserMessage(),
                        isError = false
                    )
                }
                .onFailure { error ->
                    _backupState.value = _backupState.value.copy(
                        isBusy = false,
                        busyLabel = null,
                        message = error.message ?: "导入失败了，你待会儿再试一次",
                        isError = true
                    )
                }
        }
    }

    fun consumeBackupMessage() {
        _backupState.value = _backupState.value.copy(message = null, isError = false)
    }

    /** 恢复内置分类：补齐食材/零食等受保护分类并去重，用于同步后分类缺失时手动找回。 */
    fun restoreBuiltInData() {
        viewModelScope.launch {
            runBackupAction(
                successMessage = "内置分类已补齐，去「分类」里看看",
                busyLabel = "正在恢复内置分类…"
            ) {
                backupManager.restoreBuiltInData()
            }
        }
    }

    private suspend fun runBackupAction(
        successMessage: String,
        busyLabel: String,
        action: suspend () -> Unit
    ) {
        _backupState.value = _backupState.value.copy(
            isBusy = true,
            busyLabel = busyLabel,
            message = null,
            isError = false
        )
        runCatching { action() }
            .onSuccess {
                _backupState.value = _backupState.value.copy(
                    isBusy = false,
                    busyLabel = null,
                    message = successMessage,
                    isError = false
                )
            }
            .onFailure { error ->
                _backupState.value = _backupState.value.copy(
                    isBusy = false,
                    busyLabel = null,
                    message = error.message ?: "操作没成功，你待会儿再试一次",
                    isError = true
                )
            }
    }
}
