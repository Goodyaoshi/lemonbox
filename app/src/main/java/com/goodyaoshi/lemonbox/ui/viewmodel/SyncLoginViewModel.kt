package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudConfig
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncScheduler
import com.goodyaoshi.lemonbox.data.remote.RemoteApiException
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncClient
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncManager
import com.goodyaoshi.lemonbox.data.remote.SyncEndpointStore
import com.goodyaoshi.lemonbox.data.remote.SyncSessionStore
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.sync.CloudSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 云端账号页的状态。「登录了没有、以谁的身份」是这一页唯一的核心信息。 */
data class SyncLoginUiState(
    /** 当前包是否支持文字多设备同步（debug 包也支持；图片上传另有开关）。 */
    val supported: Boolean = false,
    /** 服务端地址，用于让用户知道数据去了哪里；空串表示这个包没配。 */
    val baseUrl: String = "",
    /** 用户输入的自定义备用地址（可空表示只用安装包默认地址）。 */
    val customBaseUrl: String = "",
    /** 探活结果或保存提示，直接展示给用户。 */
    val serverDetail: String? = null,
    val serverDetailIsError: Boolean = false,
    val probing: Boolean = false,
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
    private val imageCloudScheduler: ImageCloudSyncScheduler,
    private val appPreferences: AppPreferences,
    private val cloudSyncScheduler: CloudSyncScheduler,
    private val syncManager: RemoteSyncManager,
    private val endpointStore: SyncEndpointStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SyncLoginUiState(
            supported = config.textSyncSupported,
            baseUrl = client.baseUrl,
            customBaseUrl = endpointStore.customBaseUrl().orEmpty(),
            deviceName = deviceDisplayName()
        ).withSession()
    )
    val uiState: StateFlow<SyncLoginUiState> = _uiState.asStateFlow()

    init {
        // 静默重登（冷启动 / 回到前台触发）与退出登录都可能在本页之外改变会话，
        // 订阅一次，页面不必重进就能从「未登录」切回已登录。
        viewModelScope.launch {
            sessionStore.session.collect {
                _uiState.update { state -> state.withSession() }
            }
        }
    }

    fun updateUsername(value: String) = _uiState.update { it.copy(inputUsername = value) }

    fun updatePassword(value: String) = _uiState.update { it.copy(inputPassword = value) }

    fun updateCustomBaseUrl(value: String) = _uiState.update { it.copy(customBaseUrl = value) }

    /** 保存自定义服务器地址；清空表示回到安装包默认地址。 */
    fun saveServerSetting() {
        val raw = _uiState.value.customBaseUrl.trim()
        endpointStore.setCustomBaseUrl(raw.ifBlank { null })
        val saved = endpointStore.customBaseUrl()
        _uiState.update {
            it.copy(
                customBaseUrl = saved.orEmpty(),
                baseUrl = client.baseUrl,
                serverDetail = if (saved == null) {
                    "已清除自定义地址，使用安装包默认地址"
                } else {
                    "已保存：$saved。登录时会自动探活"
                },
                serverDetailIsError = false
            )
        }
    }

    /** 主动探活候选地址，结果直接展示；成功时更新当前使用地址。 */
    fun probeServer() {
        if (_uiState.value.probing) return
        _uiState.update { it.copy(probing = true, serverDetail = null) }
        viewModelScope.launch {
            val result = try {
                client.probe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _uiState.update {
                it.copy(
                    probing = false,
                    baseUrl = client.baseUrl,
                    serverDetail = when {
                        result == null -> "探活失败：未知错误"
                        result.reachable -> result.detail
                        else -> "连不上任何候选地址：${result.detail}"
                    },
                    serverDetailIsError = result == null || !result.reachable
                )
            }
        }
    }

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
            val loginError = outcome.exceptionOrNull()
            if (loginError != null) {
                _uiState.update { state ->
                    state.copy(
                        busy = false,
                        message = when (loginError) {
                            is RemoteApiException -> loginError.message
                            else -> "登录失败，请稍后再试"
                        },
                        messageIsError = true
                    ).withSession()
                }
                rescheduleCloudSync()
                return@launch
            }

            // 登录成功先把会话态亮出来，再立即跑一轮同步。只排后台任务不够：
            // CloudSyncTrigger 只监听 Activity onStart 与同步表写入，登录本身两者都不触发，
            // 用户会停在「已登录但数据没动」的状态直到下一次切前台或最多一小时后的周期任务。
            _uiState.update {
                it.copy(busy = false, message = "登录成功，正在同步…", messageIsError = false).withSession()
            }
            rescheduleCloudSync()

            val syncResult = try {
                syncManager.syncNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val syncError = syncManager.state.value.lastError
                ?: if (syncResult == null) "同步失败，请稍后重试" else null
            _uiState.update { state ->
                state.copy(
                    busy = false,
                    message = if (syncError != null) {
                        "登录成功，但首次同步失败：$syncError"
                    } else {
                        "登录成功，${syncResult?.message ?: "已开始多设备同步"}"
                    },
                    messageIsError = syncError != null
                ).withSession()
            }
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

    /** 同步任务只在「支持 + 已登录」时排期；退出登录立即撤掉，不留无谓的后台唤醒。 */
    private fun rescheduleCloudSync() {
        val loggedIn = sessionStore.isLoggedIn
        cloudSyncScheduler.syncSchedule(
            supported = config.textSyncSupported,
            enabled = loggedIn
        )
        // 图片与文字是两条后台通道：登录后都要排，退出后都要撤。
        imageCloudScheduler.syncSchedule(
            supported = config.supported,
            enabled = loggedIn,
            backgroundSync = true
        )
    }

    /** 把会话状态灌进界面状态，登录态的一切展示都以此为唯一来源。 */
    private fun SyncLoginUiState.withSession(): SyncLoginUiState {
        val session = sessionStore.current()
        return copy(
            loggedIn = session != null,
            username = session?.username,
            expiresAt = session?.expiresAt,
            baseUrl = session?.baseUrl ?: baseUrl
        )
    }

    /** 这台设备的名字：用机型，便于在设备列表里认出「是哪台手机」。 */
    private fun deviceDisplayName(): String = android.os.Build.MODEL ?: "本机"
}
