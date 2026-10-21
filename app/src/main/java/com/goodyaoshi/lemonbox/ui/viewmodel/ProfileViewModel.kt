package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncManager
import com.goodyaoshi.lemonbox.data.remote.SyncSessionStore
import com.goodyaoshi.lemonbox.data.repository.AnniversaryRepository
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 「我的」页用的一行云同步状态；只够决定入口副标题，不展开细节。 */
data class ProfileCloudSummary(
    val loggedIn: Boolean = false,
    val syncing: Boolean = false,
    val lastSyncedAt: Long? = null,
    val hasError: Boolean = false
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    reminderRepository: ReminderRepository,
    anniversaryRepository: AnniversaryRepository,
    private val appPreferences: AppPreferences,
    sessionStore: SyncSessionStore,
    remoteSyncManager: RemoteSyncManager
) : ViewModel() {

    // 家当域的计数（待买 / 临期 / 回收站）已随「家当域」迁到 HouseholdViewModel，此处不再保留。
    // 导出可读报告 / 恢复内置分类已随「导出与维护」整块移除，本类不再依赖备份与导出管理器。

    /** 她的昵称：头像首字与「我的」页称呼都取自这里，可在「我的」页修改。 */
    val nickname: StateFlow<String> = appPreferences.nickname

    fun setNickname(value: String) {
        appPreferences.setNickname(value)
    }

    /** 今天（含已提醒还没完成）的待办数量：直接复用「今天的事」的同一份口径（IA-7），两处数字恒一致。 */
    val dueReminderCount: StateFlow<Int> = reminderRepository.getDueTodayReminders()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 纪念日数量：「常用」组纪念日入口的徽标。 */
    val anniversaryCount: StateFlow<Int> = anniversaryRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * 「数据与同步」入口的实时副标题。
     *
     * 之前这里只有一句静态的「设备同步、云端同步」，用户看不出同步到底有没有在跑；
     * 现在登录态、进行中、失败与上次成功时间都直接落在入口上。
     */
    val cloudSummary: StateFlow<ProfileCloudSummary> =
        combine(sessionStore.session, remoteSyncManager.state) { session, sync ->
            ProfileCloudSummary(
                loggedIn = session != null,
                syncing = sync.running,
                lastSyncedAt = sync.lastSyncedAt,
                hasError = sync.lastError != null
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfileCloudSummary())
}
