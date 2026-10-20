package com.goodyaoshi.lemonbox.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.backup.AppBackupManager
import com.goodyaoshi.lemonbox.data.backup.toUserMessage
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
    val messageIsError: Boolean = false
)

/**
 * 数据中心里的「本地备份 / 恢复」。
 *
 * 这是普通用户唯一的离线保险：云同步需要登录，换机、卸载、清数据时如果没有
 * 一份本机 ZIP，数据就只剩云端那一份。导出时把仍在 `filesDir/images` 的图片
 * 一起打包；已经上传云端并清理本地的图片仍要靠云端取回。
 */
@HiltViewModel
class DataCenterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: AppBackupManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DataCenterUiState())
    val uiState: StateFlow<DataCenterUiState> = _uiState.asStateFlow()

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
