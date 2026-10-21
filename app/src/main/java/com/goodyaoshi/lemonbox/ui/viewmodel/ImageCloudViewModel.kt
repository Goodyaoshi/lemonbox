package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudConfig
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncManager
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncScheduler
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncState
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncManager
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncState
import com.goodyaoshi.lemonbox.data.remote.SyncSessionStore
import com.goodyaoshi.lemonbox.data.sync.CloudSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 云同步页的状态。
 *
 * 只有三件事值得展示：有没有登录、还有多少图片没传、上一次手动同步说了什么。
 * 「是否开启」不再是一个用户决定——登录即开启，退出登录即停止。
 */
data class ImageCloudUiState(
    /** 文字多设备同步是否可用（debug 包也可用）。 */
    val supported: Boolean = false,
    /** 图片上传是否可用（仅正式包）。 */
    val imageSupported: Boolean = false,
    /** 是否已登录同步服务端；未登录时图片与数据都只留在本机。 */
    val loggedIn: Boolean = false,
    /** 当前登录的账号名；未登录为 null。 */
    val username: String? = null,
    /** 尚未上传的图片张数（本地算得，不发网络请求）；null 表示还没算过。 */
    val pendingCount: Int? = null,
    /** 最近一次手动点「立即同步」的回执（成功/失败/已在同步中），常驻到下一次点击。 */
    val lastSyncMessage: String? = null
)

@HiltViewModel
class ImageCloudViewModel @Inject constructor(
    private val config: ImageCloudConfig,
    private val syncManager: ImageCloudSyncManager,
    private val syncScheduler: ImageCloudSyncScheduler,
    private val remoteSyncManager: RemoteSyncManager,
    private val cloudSyncScheduler: CloudSyncScheduler,
    private val sessionStore: SyncSessionStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ImageCloudUiState(
            supported = config.textSyncSupported,
            imageSupported = config.supported,
            loggedIn = config.isLoggedIn(),
            username = sessionStore.current()?.username
        )
    )
    val uiState: StateFlow<ImageCloudUiState> = _uiState.asStateFlow()

    /** 图片同步进度由同步管理器持有，跨页面进出不丢。 */
    val syncState: StateFlow<ImageCloudSyncState> = syncManager.state

    /** 服务端同步的状态（上次同步时间 / 是否在跑 / 上次错误 / 上次冲突条数）。 */
    val remoteSyncState: StateFlow<RemoteSyncState> = remoteSyncManager.state

    /** 手动同步的协程；用于避免连点叠加，也便于页面销毁时随之取消。 */
    private var remoteSyncJob: Job? = null

    /** 立即执行一次服务端同步，并把结果写成可见回执（不再静默吞掉）。 */
    fun syncNow() {
        if (remoteSyncJob?.isActive == true) return
        remoteSyncJob = viewModelScope.launch {
            val result = remoteSyncManager.syncNow()
            _uiState.update { it.copy(lastSyncMessage = result.message) }
        }
    }

    /** 取消正在进行的服务端同步；已完成的批次保留，下次从水位继续。 */
    fun cancelRemoteSync() {
        remoteSyncManager.cancel()
        _uiState.update { it.copy(lastSyncMessage = "已取消同步") }
    }

    init {
        refreshPendingCount()
        // 登录态可能在本页之外改变（登录成功 / 退出后返回），
        // 因此订阅会话流：变化时刷新文案并重排后台任务。
        viewModelScope.launch {
            sessionStore.session.collect {
                refresh()
                syncBackgroundWork()
                rescheduleCloudSync()
                refreshPendingCount()
            }
        }
    }

    /** 登录即开启；退出登录时两个任务都会被撤掉。 */
    private fun syncBackgroundWork() {
        syncScheduler.syncSchedule(
            supported = config.supported,
            enabled = config.isLoggedIn(),
            backgroundSync = true
        )
    }

    private fun rescheduleCloudSync() {
        cloudSyncScheduler.syncSchedule(
            supported = config.textSyncSupported,
            enabled = config.isLoggedIn()
        )
    }

    /**
     * 重新统计未上传张数。会读一遍本地图片算哈希，所以是挂起操作；
     * 由进入页面、同步结束、登录态变化时触发，不在每次 recompose 时跑。
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
                loggedIn = config.isLoggedIn(),
                username = sessionStore.current()?.username
            )
        }
    }
}
