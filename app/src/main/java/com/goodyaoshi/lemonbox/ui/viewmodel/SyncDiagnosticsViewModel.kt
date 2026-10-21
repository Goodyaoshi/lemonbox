package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.backup.FieldDiff
import com.goodyaoshi.lemonbox.data.backup.conflictFieldDiffs
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudConfig
import com.goodyaoshi.lemonbox.data.remote.ConflictLogEntry
import com.goodyaoshi.lemonbox.data.remote.RemoteApiException
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncManager
import com.goodyaoshi.lemonbox.data.remote.SyncAuditReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 同步诊断页的状态（P9）。 */
data class SyncDiagnosticsUiState(
    /** 是否已登录且开启多设备同步：一切诊断动作的前提。 */
    val ready: Boolean = false,
    /** 对账结果；null 表示还没对过账。 */
    val report: SyncAuditReport? = null,
    /** 冲突留痕（新痕在前）。 */
    val conflicts: List<ConflictLogEntry> = emptyList(),
    /** 正在对账 / 重建中。 */
    val busy: Boolean = false,
    /** 重建确认弹窗。 */
    val showRebuildConfirm: Boolean = false,
    /** 「以本机为准合并上传」确认弹窗。 */
    val showUploadLocalConfirm: Boolean = false,
    /** 最近一次动作的回执；重建成功后带撤销点路径。 */
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** 可撤销的重建备份（仅内存持有：撤销是紧跟着重建的一次动作，不值得持久化）。 */
    val undoZipPath: String? = null
)

/**
 * 同步诊断页：把「同步到底对不对」从一句话提示升级为可查的证据（P9）。
 *
 * 四件事：逐表三方计数对账、冲突留痕逐字段对比、服务端设备列表、
 * 「以服务端为准重建本地」（危险操作：二次确认 + 撤销回执）。
 */
@HiltViewModel
class SyncDiagnosticsViewModel @Inject constructor(
    private val remoteSyncManager: RemoteSyncManager,
    private val config: ImageCloudConfig
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncDiagnosticsUiState(ready = config.isLoggedIn()))
    val uiState: StateFlow<SyncDiagnosticsUiState> = _uiState.asStateFlow()

    init {
        if (_uiState.value.ready) refresh()
    }

    /** 立即对账：拉服务端统计 + 读本机逐表计数 + 读冲突留痕。 */
    fun refresh() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                remoteSyncManager.audit() to remoteSyncManager.conflictLog()
            }
            _uiState.update { state ->
                val error = outcome.exceptionOrNull()
                val (report, conflicts) = outcome.getOrNull() ?: (null to emptyList())
                state.copy(
                    busy = false,
                    report = report,
                    conflicts = conflicts,
                    message = if (error != null) "对账失败：${errorMessage(error)}" else null,
                    messageIsError = error != null
                )
            }
        }
    }

    fun showRebuildConfirm() = _uiState.update { it.copy(showRebuildConfirm = true) }

    fun dismissRebuildConfirm() = _uiState.update { it.copy(showRebuildConfirm = false) }

    fun showUploadLocalConfirm() = _uiState.update { it.copy(showUploadLocalConfirm = true) }

    fun dismissUploadLocalConfirm() = _uiState.update { it.copy(showUploadLocalConfirm = false) }

    /**
     * 以本机为准合并上传：本机全量记录强制覆盖服务端同一条记录，再拉回其它设备独有的记录。
     *
     * 与「以服务端为准重建」正好相反，解决的是对账页最常见的差异：本机有、服务端没有，
     * 或服务端还停在旧版本。完成后立即重新对账，让用户看到差异是否归零。
     */
    fun uploadLocalEverything() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(showUploadLocalConfirm = false, busy = true, message = null) }
        viewModelScope.launch {
            val result = remoteSyncManager.uploadLocalEverything()
            val error = remoteSyncManager.state.value.lastError
            _uiState.update { state ->
                state.copy(
                    busy = false,
                    message = result.message,
                    messageIsError = error != null
                )
            }
            refresh()
        }
    }

    /** 以服务端为准重建：二次确认后执行；完成（无论成败）都带撤销点回执。 */
    fun rebuildFromServer() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(showRebuildConfirm = false, busy = true, message = null) }
        viewModelScope.launch {
            val outcome = remoteSyncManager.rebuildFromServer()
            _uiState.update { state ->
                state.copy(
                    busy = false,
                    message = outcome.message,
                    messageIsError = !outcome.success,
                    undoZipPath = outcome.undoZipPath
                )
            }
            // 重建成功后立刻对一次账，让页面直接展示重建后的对齐结果。
            if (outcome.success) refresh()
        }
    }

    /** 撤销重建：把重建前的快照导回本机。 */
    fun undoRebuild() {
        val path = _uiState.value.undoZipPath ?: return
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = remoteSyncManager.undoRebuild(path)
            _uiState.update { it.copy(busy = false, message = message, messageIsError = false, undoZipPath = null) }
            refresh()
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    private fun errorMessage(error: Throwable?): String = when (error) {
        is RemoteApiException -> error.message
        null -> "未知错误"
        else -> error.message ?: "网络异常"
    }

    /** 冲突留痕的字段对比（展示时算，而不是存时算：留痕存的是原始载荷）。 */
    fun diffsFor(entry: ConflictLogEntry): List<FieldDiff> =
        conflictFieldDiffs(entry.localPayload, entry.remotePayload)
}
