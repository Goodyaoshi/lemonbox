package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudConfig
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudPreferences
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncManager
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncScheduler
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncState
import com.goodyaoshi.lemonbox.data.imagecloud.S3Outcome
import com.goodyaoshi.lemonbox.data.imagecloud.UnlockOutcome
import com.goodyaoshi.lemonbox.data.sync.CloudSyncManager
import com.goodyaoshi.lemonbox.data.sync.CloudSyncScheduler
import com.goodyaoshi.lemonbox.data.sync.CloudSyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 云存储的设置页状态（F20）。
 *
 * 存储连接参数与密钥都不在此列：它们整份加密随包内置，界面既不展示也不可改，
 * 用户唯一能提供的是**解锁口令**。
 */
data class ImageCloudUiState(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val autoUpload: Boolean = true,
    val backgroundSync: Boolean = true,
    /** 多设备数据同步（文字数据，不含图片）；与 [enabled] 相互独立。 */
    val cloudSyncEnabled: Boolean = true,
    /** 云同步的第一处待补齐事项；null 表示已就绪。 */
    val cloudSyncBlocker: String? = null,
    /** 仅用于新输入；解锁成功后立刻清空，不把口令留在界面上。 */
    val passphrase: String = "",
    /** 本机是否已解锁（解出的配置已加密存在本机）。 */
    val unlocked: Boolean = false,
    val unlocking: Boolean = false,
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
    private val syncManager: ImageCloudSyncManager,
    private val syncScheduler: ImageCloudSyncScheduler,
    private val cloudSyncManager: CloudSyncManager,
    private val cloudSyncScheduler: CloudSyncScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ImageCloudUiState(
            supported = config.supported,
            enabled = preferences.enabled.value,
            autoUpload = preferences.autoUpload.value,
            backgroundSync = preferences.backgroundSync.value,
            cloudSyncEnabled = preferences.cloudSyncEnabled.value,
            cloudSyncBlocker = config.cloudSyncBlocker(),
            unlocked = config.isUnlocked(),
            readinessBlocker = config.readiness().firstBlocker
        )
    )
    val uiState: StateFlow<ImageCloudUiState> = _uiState.asStateFlow()

    /** 同步进度由同步管理器持有，跨页面进出不丢。 */
    val syncState: StateFlow<ImageCloudSyncState> = syncManager.state

    /** 多设备同步的状态（上次同步时间 / 是否在跑 / 上次错误）。 */
    val cloudSyncState: StateFlow<CloudSyncState> = cloudSyncManager.state

    /** 立即执行一次多设备同步。 */
    fun syncNow() {
        viewModelScope.launch { cloudSyncManager.syncNow() }
    }

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

    /**
     * 多设备数据同步开关：与图片上传开关相互独立。
     * 改了要重排后台任务——否则关掉后周期任务仍会唤醒网络。
     */
    fun setCloudSyncEnabled(value: Boolean) {
        preferences.setCloudSyncEnabled(value)
        cloudSyncScheduler.syncSchedule(
            supported = config.supported,
            enabled = preferences.cloudSyncEnabled.value
        )
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

    fun updatePassphrase(value: String) = _uiState.update { it.copy(passphrase = value) }

    /**
     * 用口令解锁包内密文，随即测试连通性。
     *
     * 口令只在这一步用一次：解开后配置由 Keystore 加密落本机，之后同步直接读本机那份。
     * 解密是 PBKDF2 十万次迭代（刻意慢），必须切到后台线程。
     */
    fun unlockAndTest() {
        if (_uiState.value.unlocking) return
        val passphrase = _uiState.value.passphrase
        if (passphrase.isBlank()) {
            _uiState.update { it.copy(testMessage = "请先输入口令", testOk = false) }
            return
        }

        val previous = config.credentials()
        _uiState.update {
            it.copy(
                unlocking = true,
                testMessage = null,
                // 立刻清空输入框，避免口令明文长期停留在界面上。
                passphrase = ""
            )
        }

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) { config.unlock(passphrase) }
            if (outcome is UnlockOutcome.Success && config.credentials() != previous) {
                // 解出的配置与上一份不同：之前「已确认」的键是以旧目标确认的，不再可信。
                syncManager.forgetKnownKeys()
            }
            _uiState.update {
                it.copy(
                    unlocking = false,
                    testMessage = when (outcome) {
                        UnlockOutcome.Success -> null
                        UnlockOutcome.NotConfigured -> "此安装包未内置云存储配置"
                        UnlockOutcome.WrongPassphrase -> "口令不对，解不开内置的存储配置"
                        UnlockOutcome.StorageUnavailable -> "系统密钥库不可用，无法在本机保存配置"
                    },
                    testOk = false
                )
            }
            refresh()

            if (outcome is UnlockOutcome.Success) {
                _uiState.update { it.copy(testing = true) }
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

    /** 锁定：清掉本机保存的配置，之后需要重新输入口令。 */
    fun lockCloud() {
        config.lock()
        // 配置没了，之前「已确认」的键也不再可用。
        syncManager.forgetKnownKeys()
        _uiState.update { it.copy(passphrase = "", testMessage = null, testOk = false) }
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
                backgroundSync = preferences.backgroundSync.value,
                cloudSyncEnabled = preferences.cloudSyncEnabled.value,
                cloudSyncBlocker = config.cloudSyncBlocker(),
                unlocked = config.isUnlocked(),
                readinessBlocker = config.readiness().firstBlocker
            )
        }
    }
}
