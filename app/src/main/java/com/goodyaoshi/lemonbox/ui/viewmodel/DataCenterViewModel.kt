package com.goodyaoshi.lemonbox.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.backup.AppBackupManager
import com.goodyaoshi.lemonbox.data.backup.toUserMessage
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncManager
import com.goodyaoshi.lemonbox.data.remote.SyncSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class DataCenterUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** 云同步状态：未登录、上次同步时间与失败原因都要在入口层可见。 */
    val cloudLoggedIn: Boolean = false,
    val cloudUsername: String? = null,
    val cloudSyncing: Boolean = false,
    val cloudLastSyncedAt: Long? = null,
    val cloudLastError: String? = null,
    /** 上次同步因载荷无法解析而跳过的记录数；非 0 时入口直接提示有数据未落地。 */
    val cloudSkippedPayloads: Int = 0
)

/**
 * 「数据与同步」页的状态。
 *
 * 除了本地备份的导入/导出，还汇总云同步的登录态与最近一次同步结果，
 * 让用户在进入二级页之前就知道「数据到底同没同步」。
 */
@HiltViewModel
class DataCenterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: AppBackupManager,
    private val remoteSyncManager: RemoteSyncManager,
    private val sessionStore: SyncSessionStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(DataCenterUiState())
    val uiState: StateFlow<DataCenterUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sessionStore.session.collect { session ->
                _uiState.update {
                    it.copy(
                        cloudLoggedIn = session != null,
                        cloudUsername = session?.username
                    )
                }
            }
        }
        viewModelScope.launch {
            remoteSyncManager.state.collect { sync ->
                _uiState.update {
                    it.copy(
                        cloudSyncing = sync.running,
                        cloudLastSyncedAt = sync.lastSyncedAt,
                        cloudLastError = sync.lastError,
                        cloudSkippedPayloads = sync.lastSkippedPayloads
                    )
                }
            }
        }
    }

    fun defaultBackupFileName(): String =
        "柠檬百宝箱备份-" +
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()) +
            ".zip"

    /** 导出全量备份到用户通过 SAF 选定的位置。 */
    fun export(uri: Uri) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            var tempFile: java.io.File? = null
            try {
                val archive = withContext(Dispatchers.IO) {
                    backupManager.createBackupZip(since = null, includeImages = true)
                }
                tempFile = archive.file
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        archive.file.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("无法写入所选位置")
                }
                _uiState.update {
                    it.copy(
                        busy = false,
                        message = "备份已导出，请把文件存到网盘或电脑上",
                        messageIsError = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        busy = false,
                        message = "导出失败：${e.message ?: "请重试"}",
                        messageIsError = true
                    )
                }
            } finally {
                tempFile?.delete()
            }
        }
    }

    /**
     * 从用户选定的 ZIP 合并恢复。
     *
     * `markDirty = true`：导入的记录要能继续推到云端；否则它们只在本机，
     * 换机后云端仍然缺这一段。合并语义是新增/较新者胜，不会清空现有数据。
     */
    fun import(uri: Uri) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    backupManager.importBackup(uri, markDirty = true)
                }
                _uiState.update {
                    it.copy(
                        busy = false,
                        message = result.toUserMessage() +
                            if (result.hasChanges) "；已登录时会自动同步到云端" else "",
                        messageIsError = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        busy = false,
                        message = "导入失败：${e.message ?: "文件可能已损坏或不是本应用的备份"}",
                        messageIsError = true
                    )
                }
            }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }
}
