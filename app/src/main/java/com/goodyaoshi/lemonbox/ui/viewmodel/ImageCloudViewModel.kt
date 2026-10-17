package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudConfig
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudPreferences
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncManager
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncScheduler
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncState
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncManager
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncState
import com.goodyaoshi.lemonbox.data.remote.SyncSessionStore
import com.goodyaoshi.lemonbox.data.sync.CloudSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 云存储与多设备同步的设置页状态（F20）。
 *
 * 存储连接参数与密钥都不在此列，也不在本机任何地方：它们只存在服务端，
 * 客户端能有的只是「登录了没有、以谁的身份」。
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
    /** 是否已登录同步服务端；未登录时图片与数据都只留在本机。 */
    val loggedIn: Boolean = false,
    /** 当前登录的账号名；未登录为 null。 */
    val username: String? = null,
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
    private val remoteSyncManager: RemoteSyncManager,
    private val cloudSyncScheduler: CloudSyncScheduler,
    private val sessionStore: SyncSessionStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ImageCloudUiState(
            supported = config.supported,
            enabled = preferences.enabled.value,
            autoUpload = preferences.autoUpload.value,
            backgroundSync = preferences.backgroundSync.value,
            cloudSyncEnabled = preferences.cloudSyncEnabled.value,
            cloudSyncBlocker = config.remoteSyncBlocker(),
            loggedIn = config.isLoggedIn(),
            username = sessionStore.current()?.username,
            readinessBlocker = config.readiness().firstBlocker
        )
    )
    val uiState: StateFlow<ImageCloudUiState> = _uiState.asStateFlow()

    /** 同步进度由同步管理器持有，跨页面进出不丢。 */
    val syncState: StateFlow<ImageCloudSyncState> = syncManager.state

    /** 服务端同步的状态（上次同步时间 / 是否在跑 / 上次错误 / 上次冲突条数）。 */
    val remoteSyncState: StateFlow<RemoteSyncState> = remoteSyncManager.state

    /** 立即执行一次服务端同步。 */
    fun syncNow() {
        viewModelScope.launch { remoteSyncManager.syncNow() }
    }

    init {
        refreshPendingCount()
        // 登录态可能在本页之外改变（去登录页登录 / 退出后返回），
        // 因此订阅会话流：变化时刷新文案并重排后台任务。
        viewModelScope.launch {
            sessionStore.session.collect {
                refresh()
                syncBackgroundWork()
                rescheduleCloudSync()
            }
        }
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
        rescheduleCloudSync()
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

    /** 同步任务只在「已登录 + 用户开启」时才排期，否则撤掉，不留无谓的后台唤醒。 */
    private fun rescheduleCloudSync() {
        cloudSyncScheduler.syncSchedule(
            supported = config.supported,
            enabled = config.isLoggedIn() && preferences.cloudSyncEnabled.value
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

    fun syncAll() = syncManager.syncAll()

    fun cancelSync() = syncManager.cancel()

    private fun refresh() {
        _uiState.update {
            it.copy(
                enabled = preferences.enabled.value,
                autoUpload = preferences.autoUpload.value,
                backgroundSync = preferences.backgroundSync.value,
                cloudSyncEnabled = preferences.cloudSyncEnabled.value,
                cloudSyncBlocker = config.remoteSyncBlocker(),
                loggedIn = config.isLoggedIn(),
                username = sessionStore.current()?.username,
                readinessBlocker = config.readiness().firstBlocker
            )
        }
    }
}
