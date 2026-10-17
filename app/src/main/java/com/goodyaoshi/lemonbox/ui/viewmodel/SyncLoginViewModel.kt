package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudConfig
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudPreferences
import com.goodyaoshi.lemonbox.data.remote.RemoteApiException
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncClient
import com.goodyaoshi.lemonbox.data.remote.SyncSessionStore
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.sync.CloudSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 云端账号页的状态。「登录了没有、以谁的身份」是这一页唯一的核心信息。 */
data class SyncLoginUiState(
    /** 当前包是否支持（仅正式包）。 */
    val supported: Boolean = false,
    /** 服务端地址，用于让用户知道数据去了哪里；空串表示这个包没配。 */
    val baseUrl: String = "",
    val loggedIn: Boolean = false,
    val username: String? = null,
    /** 令牌到期时刻（毫秒）；未登录为 null。 */
    val expiresAt: Long? = null,
    /** 这台设备在服务端登记的名字。 */
    val deviceName: String = "",
    /** 仅用于新输入；登录成功后立刻清空，不把密码留在界面上。 */
    val inputUsername: String = "",
    val inputPassword: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false
)

/**
 * 云端账号页（登录 / 退出）。
 *
 * 只有登录成功，本机才会开始与其他设备同步；未登录时一切云入口都不动作（纯本地）。
 * 账号由服务端预先配置，客户端不内置账号密码，也不保存密码——
 * 登录换来的是会话令牌，由 [SyncSessionStore] 用 Keystore 加密存在本机。
 */
@HiltViewModel
class SyncLoginViewModel @Inject constructor(
    private val client: RemoteSyncClient,
    private val sessionStore: SyncSessionStore,
    private val config: ImageCloudConfig,
    private val preferences: ImageCloudPreferences,
    private val appPreferences: AppPreferences,
    private val cloudSyncScheduler: CloudSyncScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SyncLoginUiState(
            supported = config.supported,
            baseUrl = client.baseUrl,
            deviceName = deviceDisplayName()
        ).withSession()
    )
    val uiState: StateFlow<SyncLoginUiState> = _uiState.asStateFlow()

    fun updateUsername(value: String) = _uiState.update { it.copy(inputUsername = value) }

    fun updatePassword(value: String) = _uiState.update { it.copy(inputPassword = value) }

    /** 登录；失败原因（账号密码不对 / 网络不通）直接展示给用户。 */
    fun login() {
        if (_uiState.value.busy) return
        val username = _uiState.value.inputUsername.trim()
        val password = _uiState.value.inputPassword
        if (username.isEmpty() || password.isEmpty()) {
            _uiState.update { it.copy(message = "请输入账号和密码", messageIsError = true) }
            return
        }

        _uiState.update {
            it.copy(
                busy = true,
                message = null,
                // 立刻清空输入框，避免密码明文长期停留在界面上。
                inputPassword = ""
            )
        }

        viewModelScope.launch {
            val outcome = runCatching {
                client.login(
                    username = username,
                    password = password,
                    deviceId = appPreferences.deviceId,
                    deviceName = deviceDisplayName()
                )
            }
            _uiState.update { state ->
                val error = outcome.exceptionOrNull()
                state.copy(
                    busy = false,
                    message = when {
                        error is RemoteApiException -> error.message
                        error != null -> "登录失败，请稍后再试"
                        else -> "登录成功，已开始多设备同步"
                    },
                    messageIsError = error != null
                ).withSession()
            }
            rescheduleCloudSync()
        }
    }

    /** 退出登录：只清本机凭据，云端与本地数据都不动。 */
    fun logout() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            client.logout()
            _uiState.update {
                it.copy(busy = false, message = "已退出登录，本机数据不受影响").withSession()
            }
            rescheduleCloudSync()
        }
    }

    /** 同步任务只在「已登录 + 用户开启」时才排期，否则撤掉。 */
    private fun rescheduleCloudSync() {
        cloudSyncScheduler.syncSchedule(
            supported = config.supported,
            enabled = sessionStore.isLoggedIn && preferences.cloudSyncEnabled.value
        )
    }

    /** 把会话状态灌进界面状态，登录态的一切展示都以此为唯一来源。 */
    private fun SyncLoginUiState.withSession(): SyncLoginUiState {
        val session = sessionStore.current()
        return copy(
            loggedIn = session != null,
            username = session?.username,
            expiresAt = session?.expiresAt
        )
    }

    /** 这台设备的名字：用机型，便于在设备列表里认出「是哪台手机」。 */
    private fun deviceDisplayName(): String = android.os.Build.MODEL ?: "本机"
}
