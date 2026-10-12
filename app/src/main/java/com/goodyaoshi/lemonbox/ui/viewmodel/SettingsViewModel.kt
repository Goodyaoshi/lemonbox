package com.goodyaoshi.lemonbox.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.settings.ThemeMode
import com.goodyaoshi.lemonbox.data.update.AppUpdater
import com.goodyaoshi.lemonbox.data.update.UpdateCheckResult
import com.goodyaoshi.lemonbox.data.update.UpdateInfo
import com.goodyaoshi.lemonbox.util.AnniversaryCheckWorker
import com.goodyaoshi.lemonbox.util.ExpiryCheckWorker
import com.goodyaoshi.lemonbox.util.TodoKeepAliveService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 在线更新的界面状态：检查中 / 下载进度 / 待确认版本 / 提示语。 */
data class UpdateUiState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    /** 下载进度 0..1；-1 表示总大小未知。 */
    val progress: Float = -1f,
    val available: UpdateInfo? = null,
    val message: String? = null,
    /** 需要用户先到系统里允许「安装未知来源应用」。 */
    val needInstallPermission: Boolean = false,
    /** 处于移动数据网络，等待用户确认是否用流量下载。 */
    val awaitingDataConfirm: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val appPreferences: AppPreferences,
    private val appUpdater: AppUpdater
) : ViewModel() {

    val reminderTimes: StateFlow<List<String>> = appPreferences.reminderTimes

    val themeMode: StateFlow<ThemeMode> = appPreferences.themeMode

    val keepAliveEnabled: StateFlow<Boolean> = appPreferences.keepAliveEnabled

    /** 是否已前往系统「自启动」页确认过（厂商无公开 API，只能记录用户自述）。 */
    val autoStartConfirmed: StateFlow<Boolean> = appPreferences.autoStartConfirmed

    val customReminderTimes: StateFlow<Set<String>> = appPreferences.customReminderTimes

    val mealPrepDayShift: StateFlow<Int> = appPreferences.mealPrepDayShift

    val mealPrepFireTime: StateFlow<String> = appPreferences.mealPrepFireTime

    val budgetReminderEnabled: StateFlow<Boolean> = appPreferences.budgetReminderEnabled

    /** 「在一起」起始日（yyyy-MM-dd）；null 表示未设置。 */
    val togetherStartDate: StateFlow<String?> = appPreferences.togetherStartDate

    /** 首页计数前缀（如「我们」）。 */
    val togetherLabelName: StateFlow<String> = appPreferences.togetherLabelName

    fun setTogetherStartDate(date: String?) {
        appPreferences.setTogetherStartDate(date)
    }

    fun setTogetherLabelName(name: String) {
        appPreferences.setTogetherLabelName(name)
    }

    /** 在线更新状态：检查、下载进度与待确认的新版本。 */
    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    fun setBudgetReminderEnabled(enabled: Boolean) {
        appPreferences.setBudgetReminderEnabled(enabled)
    }

    /** 自定义时间点：加入候选池并自动勾选。 */
    fun addCustomReminderTime(time: String) {
        appPreferences.addCustomReminderTime(time)
    }

    /** 取消勾选自定义时间点时连候选池一起移除，保持时间列表干净。 */
    fun removeCustomReminderTime(time: String) {
        appPreferences.removeCustomReminderTime(time)
    }

    fun setMealPrepDayShift(dayShift: Int) {
        appPreferences.setMealPrepDayShift(dayShift)
    }

    fun setMealPrepFireTime(time: String) {
        appPreferences.setMealPrepFireTime(time)
    }

    /** 保存提醒时间后立即按新时间重排每日检查任务（到期与纪念日共用时间点）。 */
    fun setReminderTimes(times: List<String>) {
        appPreferences.setReminderTimes(times)
        val latest = appPreferences.reminderTimes.value
        ExpiryCheckWorker.reschedule(appContext, latest)
        AnniversaryCheckWorker.reschedule(appContext, latest)
    }

    fun setThemeMode(mode: ThemeMode) {
        appPreferences.setThemeMode(mode)
    }

    /** 用户确认已前往系统自启动页设置后记录一次，让状态不再永远停在「去设置」。 */
    fun markAutoStartConfirmed() {
        appPreferences.setAutoStartConfirmed(true)
    }

    /** 切换保活开关：开则立刻拉起前台服务，关则停掉常驻通知。 */
    fun setKeepAliveEnabled(enabled: Boolean) {
        appPreferences.setKeepAliveEnabled(enabled)
        val intent = Intent(appContext, TodoKeepAliveService::class.java)
        try {
            if (enabled) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.stopService(intent)
            }
        } catch (_: Exception) {
            // 后台启动前台服务受限时忽略，下次打开 App 会按开关状态重新拉起。
        }
    }

    /**
     * 临时关闭后台：立即停掉常驻保活服务与那条约占位的无声通知，释放它占用的内存，
     * 让系统可以彻底回收后台进程。
     *
     * 只作用于本次运行、**不改动上方开关**，所以是「临时」而非永久：
     * 下次打开应用（或开机后）仍会按开关状态自动重新拉起，即「后面再自启动」。
     * 若想永久关闭，请把「常驻保活」开关关掉。
     */
    fun releaseBackgroundNow() {
        try {
            appContext.stopService(Intent(appContext, TodoKeepAliveService::class.java))
        } catch (_: Exception) {
            // 服务本就未运行或已被系统回收时忽略。
        }
    }

    /** 检查 GitHub 上的最新版本，有新版本则弹窗确认。 */
    fun checkForUpdate() {
        if (_updateState.value.checking || _updateState.value.downloading) return
        _updateState.value = _updateState.value.copy(checking = true, message = null, available = null)
        viewModelScope.launch {
            when (val result = appUpdater.checkForUpdate()) {
                is UpdateCheckResult.UpToDate -> _updateState.value = _updateState.value.copy(
                    checking = false,
                    message = "已是最新版本 v${result.currentVersion}"
                )

                is UpdateCheckResult.Available -> _updateState.value = _updateState.value.copy(
                    checking = false,
                    available = result.info
                )

                is UpdateCheckResult.Failed -> _updateState.value = _updateState.value.copy(
                    checking = false,
                    message = result.message
                )
            }
        }
    }

    /**
     * 下载并调起安装。
     *
     * WiFi 下直接下载；移动数据（按流量计费）下先弹窗让用户确认是否用流量，
     * 用户同意后再继续（见 [confirmDownloadOverData]）。未授权「未知来源安装」时先提示去系统设置放行。
     */
    fun downloadAndInstallUpdate() {
        val info = _updateState.value.available ?: return
        if (_updateState.value.downloading) return
        if (appUpdater.isMeteredConnection()) {
            _updateState.value = _updateState.value.copy(awaitingDataConfirm = true)
            return
        }
        startDownload(info)
    }

    /** 用户在「用移动数据下载」弹窗里确认后继续下载。 */
    fun confirmDownloadOverData() {
        _updateState.value = _updateState.value.copy(awaitingDataConfirm = false)
        val info = _updateState.value.available ?: return
        startDownload(info)
    }

    /** 关闭「用移动数据下载」弹窗，停在原处不下载。 */
    fun dismissDataConfirm() {
        _updateState.value = _updateState.value.copy(awaitingDataConfirm = false)
    }

    private fun startDownload(info: UpdateInfo) {
        if (_updateState.value.downloading) return
        if (!appUpdater.canInstallPackages()) {
            _updateState.value = _updateState.value.copy(needInstallPermission = true)
            return
        }
        _updateState.value = _updateState.value.copy(downloading = true, progress = 0f, message = null)
        viewModelScope.launch {
            runCatching {
                val file = appUpdater.downloadApk(info) { downloaded, total ->
                    val progress = if (total > 0) downloaded.toFloat() / total else -1f
                    _updateState.value = _updateState.value.copy(progress = progress)
                }
                appUpdater.installApk(file)
            }.onSuccess {
                _updateState.value = _updateState.value.copy(
                    downloading = false,
                    available = null,
                    message = "已开始安装，请按系统提示完成更新"
                )
            }.onFailure { error ->
                _updateState.value = _updateState.value.copy(
                    downloading = false,
                    message = error.message ?: "下载失败，请稍后重试"
                )
            }
        }
    }

    /** 关闭「新版本」弹窗，保留用户稍后再更新。 */
    fun dismissUpdateDialog() {
        _updateState.value = _updateState.value.copy(available = null)
    }

    /** 去系统设置允许「安装未知来源应用」。 */
    fun openInstallPermission() {
        appUpdater.openInstallPermissionSettings()
        _updateState.value = _updateState.value.copy(needInstallPermission = false)
    }

    fun dismissInstallPermissionHint() {
        _updateState.value = _updateState.value.copy(needInstallPermission = false)
    }

    fun consumeUpdateMessage() {
        _updateState.value = _updateState.value.copy(message = null)
    }
}