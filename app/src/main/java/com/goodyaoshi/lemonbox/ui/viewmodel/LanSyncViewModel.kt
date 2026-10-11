package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.backup.BackupMergeResult
import com.goodyaoshi.lemonbox.data.backup.BackupPreview
import com.goodyaoshi.lemonbox.data.backup.SyncScope
import com.goodyaoshi.lemonbox.data.backup.toUserMessage
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.sync.LanSyncManager
import com.goodyaoshi.lemonbox.data.sync.SyncClient
import com.goodyaoshi.lemonbox.data.sync.SyncPeer
import com.goodyaoshi.lemonbox.data.sync.SyncServer
import com.goodyaoshi.lemonbox.data.sync.SyncStage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** 展示对方数据合并后的变化，并让用户选同步范围；主客两侧共用。 */
data class ScopePrompt(
    val peerLabel: String,
    val preview: BackupPreview
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
    /** 发起方：待用户选择同步范围。 */
    val clientScopePrompt: ScopePrompt? = null,
    /** 被连接方：待用户确认的配对请求。 */
    val hostPairPrompt: HostPairPrompt? = null,
    /** 被连接方：收到对方数据后，待用户选择同步范围。 */
    val hostScopePrompt: ScopePrompt? = null
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
    private val appPreferences: AppPreferences
) : ViewModel() {

    val peers: StateFlow<List<SyncPeer>> = lanSyncManager.peers

    /** 是否正在扫描整个局域网网段（mDNS 失效时的兜底发现）。 */
    val scanning: StateFlow<Boolean> = lanSyncManager.scanning

    /** 扫描进度（0–100），用于界面上提示「已扫描 x%」。 */
    val scanProgress: StateFlow<Int> = lanSyncManager.scanProgress

    /** 本机型号，用于在「本机」卡片上标明这台设备，避免分不清哪台是哪台。 */
    val localDeviceModel: String = lanSyncManager.localDeviceModel

    /** 上一次成功同步的时间戳，用于在界面上提示同步新鲜度。 */
    val lastSyncAt: StateFlow<Long?> = appPreferences.lastSyncAt

    /** 本机被对方连接后，对方数据合并进来的结果。 */
    val incomingSummary: StateFlow<BackupMergeResult?> = syncServer.incomingSummary

    private val _uiState = MutableStateFlow(LanSyncUiState())
    val uiState: StateFlow<LanSyncUiState> = _uiState.asStateFlow()

    /** 发起方等待用户核对匹配码 / 选择范围的挂起闸门。 */
    private var matchGate: CompletableDeferred<Boolean>? = null
    private var scopeGate: CompletableDeferred<SyncScope?>? = null

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
                        hostScopePrompt = ScopePrompt(peerLabel = hostPeerLabel, preview = preview)
                    )
                }
            }
        }
        viewModelScope.launch {
            syncServer.hostMergeResult.collect { result ->
                if (result != null) {
                    _uiState.value = _uiState.value.copy(
                        hostScopePrompt = null,
                        message = "本机" + result.toUserMessage()
                    )
                }
            }
        }
        viewModelScope.launch {
            syncServer.hostError.collect { error ->
                if (error != null) {
                    _uiState.value = _uiState.value.copy(
                        hostScopePrompt = null,
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

    /** 发起方：选择同步范围（null=取消）。 */
    fun chooseScope(scope: SyncScope?) {
        _uiState.value = _uiState.value.copy(clientScopePrompt = null)
        val gate = scopeGate ?: return
        scopeGate = null
        gate.complete(scope)
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

    /** 被连接方：按选定范围把对方数据合并进本机。 */
    fun applyHostScope(scope: SyncScope) {
        _uiState.value = _uiState.value.copy(hostScopePrompt = null)
        syncServer.applyIncoming(scope)
    }

    private fun runSync(host: String, port: Int) {
        viewModelScope.launch {
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
                    awaitScope = { preview, peer ->
                        val gate = CompletableDeferred<SyncScope?>()
                        scopeGate = gate
                        _uiState.value = _uiState.value.copy(
                            clientScopePrompt = ScopePrompt(peerLabel = peer, preview = preview)
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
                            outcome.peerMerge?.let { append("\n对方").append(it.toUserMessage()) }
                        }
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        matchCodePrompt = null,
                        clientScopePrompt = null,
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

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
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
