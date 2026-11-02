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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 诊断页当前在跑的动作，界面据此区分按钮文案与进度来源。 */
internal const val BUSY_AUDIT = "audit"
internal const val BUSY_REBUILD = "rebuild"
internal const val BUSY_UPLOAD = "upload"
internal const val BUSY_UNDO = "undo"
internal const val BUSY_RESTORE = "restore"

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
    /** 当前在跑的动作：audit / rebuild / upload；用于给忙碌状态配一句可读的进度。 */
    val busyKind: String? = null,
    /** 忙碌期间的进度文案；重建时跟随同步阶段实时更新。 */
    val progressText: String? = null,
    /** 重建确认弹窗。 */
    val showRebuildConfirm: Boolean = false,
    /** 「以本机为准合并上传」确认弹窗。 */
    val showUploadLocalConfirm: Boolean = false,
    /** 待确认回退的冲突留痕；非 null 时展示确认弹窗。 */
    val pendingRestoreConflict: ConflictLogEntry? = null,
    /** 最近一次动作的回执；重建成功后带撤销点路径。 */
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** 上次同步因载荷无法解析而跳过的记录数；非 0 时页面直接告警。 */
    val skippedPayloads: Int = 0,
    /** 历史累计被跳过的记录数；水位已越过、不会重试，必须长期可见。 */
    val totalSkippedPayloads: Int = 0,
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

    private val _uiState = MutableStateFlow(
        SyncDiagnosticsUiState(
            ready = config.isLoggedIn(),
            skippedPayloads = remoteSyncManager.state.value.lastSkippedPayloads,
            totalSkippedPayloads = remoteSyncManager.state.value.totalSkippedPayloads
        )
    )
    val uiState: StateFlow<SyncDiagnosticsUiState> = _uiState.asStateFlow()

    init {
        // 重建/上传本质是一轮同步，进度只存在于 RemoteSyncManager 的 phase 里；
        // ViewModel 不订阅就只剩一个转圈按钮，用户无法判断是卡住还是在拉数据。
        viewModelScope.launch {
            remoteSyncManager.state.collect { sync ->
                _uiState.update { state ->
                    val text = when (state.busyKind) {
                        // 对账不走同步流程，进度文案由本页自己维护。
                        BUSY_AUDIT -> state.progressText
                        // 重建/上传本质是一轮同步：跟着它的阶段走，用户才知道是在拉数据还是卡住了。
                        null -> null
                        else -> sync.phase ?: state.progressText
                    }
                    state.copy(
                        progressText = text,
                        skippedPayloads = sync.lastSkippedPayloads,
                        totalSkippedPayloads = sync.totalSkippedPayloads
                    )
                }
            }
        }
        if (_uiState.value.ready) refresh()
    }

    /** 立即对账：拉服务端统计 + 读本机逐表计数 + 读冲突留痕。 */
    fun refresh() {
        if (_uiState.value.busy) return
        _uiState.update {
            it.copy(busy = true, busyKind = BUSY_AUDIT, progressText = "正在对账…", message = null)
        }
        viewModelScope.launch {
            val outcome = runCatching {
                remoteSyncManager.audit() to remoteSyncManager.conflictLog()
            }
            _uiState.update { state ->
                val error = outcome.exceptionOrNull()
                val (report, conflicts) = outcome.getOrNull() ?: (null to emptyList())
                state.copy(
                    busy = false,
                    busyKind = null,
                    progressText = null,
                    report = report,
                    conflicts = conflicts,
                    skippedPayloads = remoteSyncManager.state.value.lastSkippedPayloads,
                    totalSkippedPayloads = remoteSyncManager.state.value.totalSkippedPayloads,
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

    /** 请求回退某条「服务端较新」的冲突：先二次确认，避免误覆盖服务端现有版本。 */
    fun requestRestoreConflict(entry: ConflictLogEntry) =
        _uiState.update { it.copy(pendingRestoreConflict = entry) }

    fun dismissRestoreConflict() = _uiState.update { it.copy(pendingRestoreConflict = null) }

    /** 把留痕里的本机版本按最新时间写回本地，并排队重推到服务端。 */
    fun restoreConflict() {
        val entry = _uiState.value.pendingRestoreConflict ?: return
        if (_uiState.value.busy) return
        _uiState.update {
            it.copy(
                pendingRestoreConflict = null,
                busy = true,
                busyKind = BUSY_RESTORE,
                progressText = "正在恢复本机版本…",
                message = null
            )
        }
        viewModelScope.launch {
            val result = remoteSyncManager.restoreLocalConflict(entry)
            _uiState.update {
                it.copy(
                    busy = false,
                    busyKind = null,
                    progressText = null,
                    message = result.message,
                    messageIsError = !result.success
                )
            }
            refresh()
        }
    }

    /**
     * 以本机为准合并上传：本机全量记录强制覆盖服务端同一条记录，再拉回其它设备独有的记录。
     *
     * 与「以服务端为准重建」正好相反，解决的是对账页最常见的差异：本机有、服务端没有，
     * 或服务端还停在旧版本。完成后立即重新对账，让用户看到差异是否归零。
     */
    fun uploadLocalEverything() {
        if (_uiState.value.busy) return
        _uiState.update {
            it.copy(
                showUploadLocalConfirm = false,
                busy = true,
                busyKind = BUSY_UPLOAD,
                progressText = "正在整理本机数据…",
                message = null
            )
        }
        viewModelScope.launch {
            val result = remoteSyncManager.uploadLocalEverything()
            val error = remoteSyncManager.state.value.lastError
            _uiState.update { state ->
                state.copy(
                    busy = false,
                    busyKind = null,
                    progressText = null,
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
        _uiState.update {
            it.copy(
                showRebuildConfirm = false,
                busy = true,
                busyKind = BUSY_REBUILD,
                progressText = "正在导出撤销备份并清空本机…",
                message = null
            )
        }
        viewModelScope.launch {
            val outcome = remoteSyncManager.rebuildFromServer()
            _uiState.update { state ->
                state.copy(
                    busy = false,
                    busyKind = null,
                    progressText = null,
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
        _uiState.update {
            it.copy(busy = true, busyKind = BUSY_UNDO, progressText = "正在恢复重建前的本机数据…", message = null)
        }
        viewModelScope.launch {
            val message = remoteSyncManager.undoRebuild(path)
            _uiState.update {
                it.copy(
                    busy = false,
                    busyKind = null,
                    progressText = null,
                    message = message,
                    messageIsError = false,
                    undoZipPath = null
                )
            }
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
