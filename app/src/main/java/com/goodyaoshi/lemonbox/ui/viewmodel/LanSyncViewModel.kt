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
            message = if (ip == null) "已开启共享，但未取到局域网地址，请确认已连接 WiFi" else null
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
            showMessage("请输入对方的地址")
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
     * 直接连接 mDNS 发现到的设备：配对码随对方广播的 TXT 记录带过来，
     * 无需先看码再手动输入（类似蓝牙配对，点一下就连）。
     */
    fun connectToPeer(peer: SyncPeer) {
        val token = peer.token
        if (token.isNullOrBlank()) {
            showMessage("该设备未开启共享或版本较旧，请在下方手动输入配对码")
            return
        }
        runSync(peer.host, peer.port, token)
    }

    private fun runSync(host: String, port: Int, token: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, message = "正在同步，请保持两台设备在同一 WiFi…")
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
                        message = error.message ?: "同步失败，请检查两台设备是否在同一 WiFi"
                    )
                }
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
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