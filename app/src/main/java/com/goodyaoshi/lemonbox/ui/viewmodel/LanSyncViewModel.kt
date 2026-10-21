package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.backup.BackupChanges
import com.goodyaoshi.lemonbox.data.backup.BackupMergeResult
import com.goodyaoshi.lemonbox.data.backup.toUserMessage
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncManager
import com.goodyaoshi.lemonbox.data.remote.SyncSessionStore
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.sync.LanSyncManager
import com.goodyaoshi.lemonbox.data.sync.SyncClient
import com.goodyaoshi.lemonbox.data.sync.SyncPeer
import com.goodyaoshi.lemonbox.data.sync.SyncReport
import com.goodyaoshi.lemonbox.data.sync.SyncServer
import com.goodyaoshi.lemonbox.data.sync.SyncStage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LanSyncMode { IDLE, HOSTING }

/** 单个同步步骤的状态，用于把「同步中…」细化成可看的进度。 */
enum class SyncStepStatus { PENDING, RUNNING, DONE, FAILED }

data class SyncStepUi(val label: String, val status: SyncStepStatus)

/** 数据交换阶段的传输明细：正在传什么、已传多少、总量多少（总量 -1 表示未知）。 */
data class SyncTransferUi(val label: String, val done: Long, val total: Long) {
    /** 0–100；总量未知时返回 null，界面改用「已传 xx」展示。 */
    val percent: Int?
        get() = if (total > 0) ((done * 100 / total).toInt()).coerceIn(0, 100) else null
}

/** 发起方核对匹配码的提示：需要和对方屏幕上显示的码人工对照。 */
data class MatchCodePrompt(
    val peerLabel: String,
    val matchCode: String
)

/** 展示对方数据合并后的逐条变化，并让用户勾选要落地的内容；主客两侧共用。 */
data class ChangesPrompt(
    val peerLabel: String,
    val changes: BackupChanges
)

/** 被连接方收到的配对请求：对方是谁、匹配码是多少。 */
data class HostPairPrompt(
    val peerLabel: String,
    val deviceModel: String,
    val matchCode: String
)

data class LanSyncUiState(
    val mode: LanSyncMode = LanSyncMode.IDLE,
    val pairingCode: String = "",
    val localIp: String? = null,
    val port: Int = 0,
    val isBusy: Boolean = false,
    val message: String? = null,
    val syncSteps: List<SyncStepUi> = emptyList(),
    /** 数据交换阶段正在传输的字节明细；不在传输时为 null。 */
    val transfer: SyncTransferUi? = null,
    /** 被连接方是否已点「接受」。 */
    val hostAccepted: Boolean = false,
    /** 发起方：待用户核对的匹配码。 */
    val matchCodePrompt: MatchCodePrompt? = null,
    /** 发起方：待用户勾选要同步的内容。 */
    val clientChangesPrompt: ChangesPrompt? = null,
    /** 被连接方：待用户确认的配对请求。 */
    val hostPairPrompt: HostPairPrompt? = null,
    /** 被连接方：收到对方数据后，待用户勾选要落地的内容。 */
    val hostChangesPrompt: ChangesPrompt? = null
) {
    /** 显示给对方手动输入的地址；端口是默认端口时只显示 IP。 */
    val displayAddress: String?
        get() = localIp?.let { ip ->
            if (port == SyncServer.DEFAULT_PORT) ip else "$ip:$port"
        }
}

@HiltViewModel
class LanSyncViewModel @Inject constructor(
    private val lanSyncManager: LanSyncManager,
    private val syncServer: SyncServer,
    private val syncClient: SyncClient,
    private val appPreferences: AppPreferences,
    sessionStore: SyncSessionStore,
    remoteSyncManager: RemoteSyncManager
) : ViewModel() {

    val peers: StateFlow<List<SyncPeer>> = lanSyncManager.peers

    /** 是否正在扫描整个局域网网段（mDNS 失效时的兜底发现）。 */
    val scanning: StateFlow<Boolean> = lanSyncManager.scanning

    /** 扫描进度（0–100），用于界面上提示「已扫描 x%」。 */
    val scanProgress: StateFlow<Int> = lanSyncManager.scanProgress

    /** 本机型号，用于在「本机」卡片上标明这台设备，避免分不清哪台是哪台。 */
    val localDeviceModel: String = lanSyncManager.localDeviceModel

    /** 上一次成功**局域网同步**的时间戳，用于在界面上提示同步新鲜度。 */
    val lastSyncAt: StateFlow<Long?> = appPreferences.lastSyncAt

    /** 云同步是否已登录：本页据此说明「日常同步已自动完成」，避免用户每次都来这里手动对齐。 */
    val cloudLoggedIn: StateFlow<Boolean> = sessionStore.session
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 云同步上次成功的时刻；仅用于说明本页与自动同步的分工。 */
    val cloudLastSyncedAt: StateFlow<Long?> = remoteSyncManager.state
        .map { it.lastSyncedAt }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 本机被对方连接后，对方数据合并进来的结果。 */
    val incomingSummary: StateFlow<BackupMergeResult?> = syncServer.incomingSummary

    /** 同步历史（S5）：最近几次局域网同步的逐次回执，供页面底部展示。 */
    val syncReports: StateFlow<List<SyncReport>> = appPreferences.syncReports

    private val _uiState = MutableStateFlow(LanSyncUiState())
    val uiState: StateFlow<LanSyncUiState> = _uiState.asStateFlow()

    /** 发起方等待用户核对匹配码 / 勾选同步内容的挂起闸门。 */
    private var matchGate: CompletableDeferred<Boolean>? = null
    private var selectionGate: CompletableDeferred<Set<String>?>? = null

    private val _lastTarget = MutableStateFlow<Pair<String, Int>?>(null)

    /** 上一次发起同步的目标（IP:端口）：失败后据此提供「重试」，不必再翻设备列表（GAP-3）。 */
    val lastTarget: StateFlow<Pair<String, Int>?> = _lastTarget.asStateFlow()

    /** 当前配对请求对应的对方名字，用于展示「对方数据」预览时标明来源。 */
    private var hostPeerLabel: String = "对方"

    init {
        lanSyncManager.startDiscovery()
        observeServer()
    }

    private fun observeServer() {
        viewModelScope.launch {
            syncServer.pairRequest.collect { request ->
                hostPeerLabel = request?.deviceName ?: "对方"
                _uiState.value = _uiState.value.copy(
                    hostPairPrompt = request?.let {
                        HostPairPrompt(
                            peerLabel = it.deviceName,
                            deviceModel = it.deviceModel,
                            matchCode = it.matchCode
                        )
                    }
                )
            }
        }
        viewModelScope.launch {
            syncServer.hostAccepted.collect { accepted ->
                _uiState.value = _uiState.value.copy(hostAccepted = accepted)
            }
        }
        viewModelScope.launch {
            syncServer.stagedPreview.collect { preview ->
                if (preview != null) {
                    _uiState.value = _uiState.value.copy(
                        hostChangesPrompt = ChangesPrompt(peerLabel = hostPeerLabel, changes = preview)
                    )
                }
            }
        }
        viewModelScope.launch {
            syncServer.hostMergeResult.collect { result ->
                if (result != null) {
                    _uiState.value = _uiState.value.copy(
                        hostChangesPrompt = null,
                        message = "本机" + result.toUserMessage()
                    )
                }
            }
        }
        viewModelScope.launch {
            syncServer.hostError.collect { error ->
                if (error != null) {
                    _uiState.value = _uiState.value.copy(
                        hostChangesPrompt = null,
                        message = error
                    )
                    syncServer.clearHostError()
                }
            }
        }
    }

    fun startHosting() {
        val code = (100000..999999).random().toString()
        val ip = lanSyncManager.startHosting(code)
        _uiState.value = LanSyncUiState(
            mode = LanSyncMode.HOSTING,
            pairingCode = code,
            localIp = ip,
            port = syncServer.listeningPort,
            message = if (ip == null) "已开启共享，但没拿到局域网地址，你确认一下 Wi-Fi 是否已连接" else null
        )
        lanSyncManager.startDiscovery()
    }

    fun stopHosting() {
        lanSyncManager.stopHosting()
        lanSyncManager.clearPeers()
        syncServer.clearIncomingSummary()
        _uiState.value = LanSyncUiState()
        lanSyncManager.startDiscovery()
    }

    /** 发起方：手动输入对方 IP（端口可选），点连接后进入配对流程。 */
    fun connect(rawAddress: String) {
        val address = rawAddress.trim().removePrefix("http://")
        if (address.isBlank()) {
            showMessage("先填上对方的地址吧")
            return
        }
        val host = address.substringBefore(':')
        val port = address.substringAfter(':', "").toIntOrNull() ?: SyncServer.DEFAULT_PORT
        runSync(host, port)
    }

    /**
     * 连接 mDNS 发现到的设备：地址已由发现结果给出，
     * 连接后由双方屏幕上的匹配码人工对照确认，因此不再需要预先输入配对码。
     */
    fun connectToPeer(peer: SyncPeer) {
        runSync(peer.host, peer.port)
    }

    /** 发起方：核对匹配码的结果（true=一致，继续同步）。 */
    fun confirmMatch(accept: Boolean) {
        _uiState.value = _uiState.value.copy(matchCodePrompt = null)
        val gate = matchGate ?: return
        matchGate = null
        gate.complete(accept)
    }

    /** 发起方：勾选要同步的内容（null=取消整次同步）。 */
    fun chooseChanges(selected: Set<String>?) {
        _uiState.value = _uiState.value.copy(clientChangesPrompt = null)
        val gate = selectionGate ?: return
        selectionGate = null
        gate.complete(selected)
    }

    /** 被连接方：接受配对。 */
    fun acceptHostPair() {
        _uiState.value = _uiState.value.copy(hostPairPrompt = null)
        syncServer.acceptPairing()
    }

    /** 被连接方：拒绝配对。 */
    fun rejectHostPair() {
        _uiState.value = _uiState.value.copy(hostPairPrompt = null)
        syncServer.rejectPairing()
    }

    /** 被连接方：按勾选结果把对方数据合并进本机。 */
    fun applyHostChanges(selected: Set<String>) {
        _uiState.value = _uiState.value.copy(hostChangesPrompt = null)
        syncServer.applyIncoming(selected)
    }

    /** 被连接方：取消这次合并——拒绝整个会话，对方会收到「没有落地」的结果。 */
    fun cancelHostChanges() {
        _uiState.value = _uiState.value.copy(hostChangesPrompt = null)
        syncServer.rejectPairing()
    }

    private fun runSync(host: String, port: Int) {
        viewModelScope.launch {
            _lastTarget.value = host to port
            _uiState.value = _uiState.value.copy(
                isBusy = true,
                message = null,
                syncSteps = SyncStage.entries.map { SyncStepUi(it.label, SyncStepStatus.PENDING) },
                transfer = null
            )
            runCatching {
                syncClient.syncInteractive(
                    host = host,
                    port = port,
                    onProgress = { stage ->
                        // 进入新阶段时清掉上一阶段的传输明细，具体进度由 onTransfer 重新填充。
                        _uiState.value = _uiState.value.copy(
                            syncSteps = stepsFor(stage),
                            transfer = null
                        )
                    },
                    onTransfer = { done, total, label ->
                        _uiState.value = _uiState.value.copy(
                            transfer = SyncTransferUi(label = label, done = done, total = total)
                        )
                    },
                    awaitMatchCode = { code, peer ->
                        val gate = CompletableDeferred<Boolean>()
                        matchGate = gate
                        _uiState.value = _uiState.value.copy(
                            matchCodePrompt = MatchCodePrompt(peerLabel = peer, matchCode = code)
                        )
                        gate.await()
                    },
                    awaitSelection = { changes, peer ->
                        val gate = CompletableDeferred<Set<String>?>()
                        selectionGate = gate
                        _uiState.value = _uiState.value.copy(
                            clientChangesPrompt = ChangesPrompt(peerLabel = peer, changes = changes)
                        )
                        gate.await()
                    }
                )
            }
                .onSuccess { outcome ->
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        transfer = null,
                        syncSteps = _uiState.value.syncSteps.map {
                            it.copy(status = SyncStepStatus.DONE)
                        },
                        message = buildString {
                            append("本机").append(outcome.localMerge.toUserMessage())
                            if (outcome.peerMerge != null) {
                                append("\n对方").append(outcome.peerMerge.toUserMessage())
                            } else {
                                append("\n对方没有回传落地结果，下次同步会自动补上")
                            }
                        }
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        matchCodePrompt = null,
                        clientChangesPrompt = null,
                        transfer = null,
                        syncSteps = _uiState.value.syncSteps.map {
                            if (it.status == SyncStepStatus.RUNNING) {
                                it.copy(status = SyncStepStatus.FAILED)
                            } else {
                                it
                            }
                        },
                        message = error.message ?: "同步失败了，你看看两台设备是不是连的同一个 Wi-Fi"
                    )
                }
        }
    }

    /** 依据当前进行到哪一步，把步骤列表刷成「之前已完成 / 当前进行中 / 之后待开始」。 */
    private fun stepsFor(current: SyncStage): List<SyncStepUi> =
        SyncStage.entries.map { stage ->
            val status = when {
                stage.ordinal < current.ordinal -> SyncStepStatus.DONE
                stage == current -> SyncStepStatus.RUNNING
                else -> SyncStepStatus.PENDING
            }
            SyncStepUi(stage.label, status)
        }

    /** 失败后从失败处重试：复用上一次的目标地址，省去重新发现/输入（GAP-3）。 */
    fun retryLastSync() {
        val target = _lastTarget.value ?: return
        if (_uiState.value.isBusy) return
        runSync(target.first, target.second)
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /**
     * 离开页面时清空瞬态（IA-7）：上次同步的步骤列表与结果回执不再滞留——
     * 同步结束后的回执已由 Snackbar 与「同步历史」承接，步骤列表留在界面上
     * 会让用户误以为上次同步还没完成。
     */
    fun clearTransients() {
        _uiState.value = LanSyncUiState()
    }

    /** 重新搜索附近设备：先停再开，让列表能刷掉已离线/已变更的项。 */
    fun refreshDiscovery() {
        lanSyncManager.stopDiscovery()
        lanSyncManager.startDiscovery()
    }

    /**
     * 兜底发现：直接扫描本机所在网段。适用于 mDNS 失效的网络（路由器开了 AP 隔离），
     * 以及用手机开「个人热点」把两台设备直连的场景。
     */
    fun scanLan() {
        lanSyncManager.scanLan()
    }

    private fun showMessage(text: String) {
        _uiState.value = _uiState.value.copy(message = text)
    }

    override fun onCleared() {
        super.onCleared()
        lanSyncManager.stopHosting()
        lanSyncManager.stopDiscovery()
    }
}
