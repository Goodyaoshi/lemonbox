package com.goodyaoshi.lemonbox.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.backup.AppBackupManager
import com.goodyaoshi.lemonbox.data.backup.toUserMessage
import com.goodyaoshi.lemonbox.data.local.dao.ItemDao
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.DateUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BackupUiState(
    val isBusy: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    itemDao: ItemDao,
    private val backupManager: AppBackupManager,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000

    /** 在库可用：在库且还没用完，是「我的」页最该关注的库存量。 */
    val availableCount: StateFlow<Int> = itemDao.getAvailableCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val toBuyCount: StateFlow<Int> = itemDao.getToBuyCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 临期数量：逐件按物品自己的提醒阶梯窗口判定（未设置则跟随全局）。 */
    val expiringCount: StateFlow<Int> =
        combine(itemDao.getActiveItems(), appPreferences.reminderLadder) { items, ladder ->
            items.count { detail ->
                val expireTime = detail.item.expireTime ?: return@count false
                if (expireTime <= 0L) return@count false
                val window = Item.reminderWindowDays(detail.item.reminderDays, ladder)
                DateUtil.daysUntil(expireTime) <= window
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val trashCount: StateFlow<Int> = itemDao
        .getRecycleCount(System.currentTimeMillis() - thirtyDaysMs)
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