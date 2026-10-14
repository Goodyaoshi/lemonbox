package com.goodyaoshi.lemonbox.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.backup.AppBackupManager
import com.goodyaoshi.lemonbox.data.export.ReadableExportManager
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
import javax.inject.Inject

data class BackupUiState(
    val isBusy: Boolean = false,
    /** 处理中弹窗的标题，让等待有明确所指。 */
    val busyLabel: String? = null,
    val message: String? = null,
    /** 结果是否为失败：用于成功 / 失败分级提示（文案与语气不同）。 */
    val isError: Boolean = false
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val backupManager: AppBackupManager,
    private val readableExportManager: ReadableExportManager,
    reminderRepository: ReminderRepository,
    anniversaryRepository: AnniversaryRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    // 家当域的计数（待买 / 临期 / 回收站）已随「家当域」迁到 HouseholdViewModel，此处不再保留。

    /** 她的昵称：头像首字与「我的」页称呼都取自这里，可在「我的」页修改。 */
    val nickname: StateFlow<String> = appPreferences.nickname

    fun setNickname(value: String) {
        appPreferences.setNickname(value)
    }

    /** 今天（含已提醒还没完成）的待办数量：直接复用「今天的事」的同一份口径（IA-7），两处数字恒一致。 */
    val dueReminderCount: StateFlow<Int> = reminderRepository.getDueTodayReminders()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 纪念日数量：「常用」组纪念日入口的徽标。 */
    val anniversaryCount: StateFlow<Int> = anniversaryRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _backupState = MutableStateFlow(BackupUiState())
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    /** 人类可读报告导出（F12）：生成可阅读、可打印的 HTML 清单。 */
    fun exportReadable(targetUri: Uri) {
        viewModelScope.launch {
            runBackupAction(busyLabel = "正在生成报告…") {
                readableExportManager.exportHtml(targetUri)
                "可读报告已导出"
            }
        }
    }

    fun consumeBackupMessage() {
        _backupState.value = _backupState.value.copy(message = null, isError = false)
    }

    /**
     * 预览「恢复内置分类」将新增的内置分类名（G-6）：与 [restoreBuiltInData] 的补种同口径，
     * 先列明细让用户确认，不再点了就往整理过的分类树里塞回不用的分类。
     */
    suspend fun previewMissingBuiltInCategories(): List<String> =
        backupManager.previewRestoreBuiltIn()

    /** 恢复内置分类：补齐食材/零食等受保护分类并去重，用于同步后分类缺失时手动找回。 */
    fun restoreBuiltInData() {
        viewModelScope.launch {
            runBackupAction(busyLabel = "正在恢复内置分类…") {
                backupManager.restoreBuiltInData()
                "内置分类已补齐，去「分类」里看看"
            }
        }
    }

    private suspend fun runBackupAction(
        busyLabel: String,
        action: suspend () -> String
    ) {
        _backupState.value = _backupState.value.copy(
            isBusy = true,
            busyLabel = busyLabel,
            message = null,
            isError = false
        )
        runCatching { action() }
            .onSuccess { message ->
                _backupState.value = _backupState.value.copy(
                    isBusy = false,
                    busyLabel = null,
                    message = message,
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
