package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.backup.BackupMergeResult
import com.goodyaoshi.lemonbox.data.backup.toUserMessage
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.sync.LanSyncManager
import com.goodyaoshi.lemonbox.data.sync.SyncClient
import com.goodyaoshi.lemonbox.data.sync.SyncPeer
import com.goodyaoshi.lemonbox.data.sync.SyncServer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LanSyncMode { IDLE, HOSTING }

data class LanSyncUiState(
    val mode: LanSyncMode = LanSyncMode.IDLE,
    val pairingCode: String = "",
    val localIp: String? = null,
    val port: Int = 0,
    val isBusy: Boolean = false,
    val message: String? = null
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

    /** 上一次成功同步的时间戳，用于在界面上提示同步新鲜度。 */
    val lastSyncAt: StateFlow<Long?> = appPreferences.lastSyncAt

    /** 本机被对方连接后，对方数据合并进来的结果。 */
    val incomingSummary: StateFlow<BackupMergeResult?> = syncServer.incomingSummary

    private val _uiState = MutableStateFlow(LanSyncUiState())
    val uiState: StateFlow<LanSyncUiState> = _uiState.asStateFlow()

    init {
        lanSyncManager.startDiscovery()
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

    fun connect(rawAddress: String, code: String) {
        val address = rawAddress.trim().removePrefix("http://")
        if (address.isBlank()) {
            showMessage("先填上对方的地址吧")
            return
        }
        if (code.trim().length != 6) {
            showMessage("配对码是 6 位数字")
            return
        }
        val host = address.substringBefore(':')
        val port = address.substringAfter(':', "").toIntOrNull() ?: SyncServer.DEFAULT_PORT
        runSync(host, port, code.trim())
    }

    /**
     * 连接 mDNS 发现到的设备。
     * 出于安全考虑，新版本不再通过 TXT 记录广播配对码：对这类设备这里只提示用户在下方
     * 「手动输入」中填写对方屏幕上显示的 6 位配对码（地址已由界面预填）。
     * 仅当对方为仍在广播配对码的旧版本时，才直接携带该码发起同步。
     */
    fun connectToPeer(peer: SyncPeer) {
        val token = peer.token
        if (token.isNullOrBlank()) {
            showMessage("你在下方「手动输入」里填上对方屏幕上那 6 位配对码")
            return
        }
        runSync(peer.host, peer.port, token)
    }

    private fun runSync(host: String, port: Int, token: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, message = "正在同步，你把两台设备留在同一个 Wi-Fi 下就行…")
            runCatching { syncClient.syncWith(host, port, token) }
                .onSuccess { outcome ->
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        message = buildString {
                            append(outcome.localMerge.toUserMessage())
                            if (outcome.peerMerge.hasChanges) {
                                append("\n对方也合并了你的改动")
                            }
                        }
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        message = error.message ?: "同步失败了，你看看两台设备是不是连的同一个 Wi-Fi"
                    )
                }
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /** 重新搜索附近设备：先停再开，让列表能刷掉已离线/已变更的项。 */
    fun refreshDiscovery() {
        lanSyncManager.stopDiscovery()
        lanSyncManager.startDiscovery()
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