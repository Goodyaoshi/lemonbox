package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudConfig
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudPreferences
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncManager
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncScheduler
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncState
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCredentialStore
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCredentials
import com.goodyaoshi.lemonbox.data.imagecloud.S3Outcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 图片云端存储的设置页状态（F20）。
 *
 * 表单字段与「已保存的生效值」刻意分开：`endpoint` 等初值取**当前生效值**
 * （用户覆盖优先，否则为 APK 内置配置），用户改动后点「保存并测试连接」才落盘。
 */
data class ImageCloudUiState(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val autoUpload: Boolean = true,
    val backgroundSync: Boolean = true,
    val endpoint: String = "",
    val region: String = "",
    val bucket: String = "",
    val prefix: String = "",
    /** 仅用于新填写；已保存的凭据回显为空，不把明文读回界面。 */
    val accessKey: String = "",
    val secretKey: String = "",
    val hasCredentials: Boolean = false,
    val testing: Boolean = false,
    val testMessage: String? = null,
    val testOk: Boolean = false,
    val readinessBlocker: String? = null,
    /** 尚未上传的图片张数（本地算得，不发网络请求）；null 表示还没算过。 */
    val pendingCount: Int? = null
)

@HiltViewModel
class ImageCloudViewModel @Inject constructor(
    private val config: ImageCloudConfig,
    private val preferences: ImageCloudPreferences,
    private val credentialStore: ImageCredentialStore,
    private val syncManager: ImageCloudSyncManager,
    private val syncScheduler: ImageCloudSyncScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ImageCloudUiState(
            supported = config.supported,
            enabled = preferences.enabled.value,
            autoUpload = preferences.autoUpload.value,
            backgroundSync = preferences.backgroundSync.value,
            endpoint = config.endpoint(),
            region = config.region(),
            bucket = config.bucket(),
            prefix = config.prefix(),
            hasCredentials = credentialStore.hasCredentials(),
            readinessBlocker = config.readiness().firstBlocker
        )
    )
    val uiState: StateFlow<ImageCloudUiState> = _uiState.asStateFlow()

    /** 同步进度由同步管理器持有，跨页面进出不丢。 */
    val syncState: StateFlow<ImageCloudSyncState> = syncManager.state

    init {
        refreshPendingCount()
    }

    fun setEnabled(value: Boolean) {
        preferences.setEnabled(value)
        // 开关同时是「要不要在后台补传」的条件之一，改了就重排/撤掉后台任务。
        syncBackgroundWork()
        refresh()
        refreshPendingCount()
    }

    fun setAutoUpload(value: Boolean) {
        preferences.setAutoUpload(value)
        refresh()
    }

    /** 后台补传开关：只影响 WorkManager 的周期任务，不影响手动与保存时的上传。 */
    fun setBackgroundSync(value: Boolean) {
        preferences.setBackgroundSync(value)
        syncBackgroundWork()
        refresh()
    }

    /** 按当前三条件决定后台补传任务的排期，与 LemonApplication 冷启动走同一处判断。 */
    private fun syncBackgroundWork() {
        syncScheduler.syncSchedule(
            supported = config.supported,
            enabled = preferences.enabled.value,
            backgroundSync = preferences.backgroundSync.value
        )
    }

    /**
     * 重新统计未上传张数。会读一遍本地图片算哈希，所以是挂起操作；
     * 由进入页面、同步结束、开关变化时触发，不在每次 recompose 时跑。
     */
    fun refreshPendingCount() {
        viewModelScope.launch {
            val count = syncManager.countPendingUploads()
            _uiState.update { it.copy(pendingCount = count) }
        }
    }

    fun updateEndpoint(value: String) = _uiState.update { it.copy(endpoint = value) }
    fun updateRegion(value: String) = _uiState.update { it.copy(region = value) }
    fun updateBucket(value: String) = _uiState.update { it.copy(bucket = value) }
    fun updatePrefix(value: String) = _uiState.update { it.copy(prefix = value) }
    fun updateAccessKey(value: String) = _uiState.update { it.copy(accessKey = value) }
    fun updateSecretKey(value: String) = _uiState.update { it.copy(secretKey = value) }

    /**
     * 保存表单并测试连通性。
     *
     * 「测试隐含保存」是刻意的：否则用户改了地址直接点测试，用的却是上一次保存的配置，
     * 失败原因会很难理解。凭据留空表示「不改动已保存的那份」。
     */
    fun saveAndTest() {
        if (_uiState.value.testing) return
        val state = _uiState.value
        // 记下改动前的生效目标：存储位置变了，旧账本里那些键就不再可信。
        val previousTarget = listOf(config.endpoint(), config.bucket(), config.prefix())
        preferences.setConnectionOverrides(
            endpoint = state.endpoint,
            region = state.region,
            bucket = state.bucket,
            prefix = state.prefix
        )
        val currentTarget = listOf(config.endpoint(), config.bucket(), config.prefix())
        if (previousTarget != currentTarget) {
            syncManager.forgetKnownKeys()
        }
        val credentialsSubmitted = state.accessKey.isNotBlank() && state.secretKey.isNotBlank()
        if (credentialsSubmitted) {
            credentialStore.save(
                ImageCredentials(state.accessKey.trim(), state.secretKey.trim())
            )
        }

        _uiState.update {
            it.copy(
                testing = true,
                testMessage = null,
                // 提交成功后立刻清空输入框，避免明文凭据长期停留在界面上。
                accessKey = if (credentialsSubmitted) "" else it.accessKey,
                secretKey = if (credentialsSubmitted) "" else it.secretKey
            )
        }

        viewModelScope.launch {
            val result = syncManager.testConnection()
            _uiState.update {
                it.copy(
                    testing = false,
                    testOk = result is S3Outcome.Success,
                    testMessage = when (result) {
                        S3Outcome.Success -> "连接成功，可以开始上传了"
                        S3Outcome.NotFound -> "没连上：存储桶不存在，或凭据没有访问权限"
                        is S3Outcome.Error -> "没连上：${result.message}"
                    }
                )
            }
            refresh()
            refreshPendingCount()
        }
    }

    fun clearCredentials() {
        credentialStore.clear()
        // 凭据换了，之前「已确认」的键是以旧身份确认的，不再可信。
        syncManager.forgetKnownKeys()
        _uiState.update { it.copy(accessKey = "", secretKey = "", testMessage = null) }
        refresh()
        refreshPendingCount()
    }

    fun syncAll() = syncManager.syncAll()

    fun cancelSync() = syncManager.cancel()

    private fun refresh() {
        _uiState.update {
            it.copy(
                enabled = preferences.enabled.value,
                autoUpload = preferences.autoUpload.value,
                hasCredentials = credentialStore.hasCredentials(),
                readinessBlocker = config.readiness().firstBlocker
            )
        }
    }
}
