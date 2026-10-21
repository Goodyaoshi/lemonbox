package com.goodyaoshi.lemonbox.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.settings.ThemeMode
import com.goodyaoshi.lemonbox.data.update.ApkDownloadResult
import com.goodyaoshi.lemonbox.data.update.ApkInstallResult
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

/** 在线更新的界面状态：检查中 / 待确认版本 / 可下载的安装包 / 提示语。 */
data class UpdateUiState(
    val checking: Boolean = false,
    /** 有新版本时非空：用于「发现新版本」弹窗。 */
    val available: UpdateInfo? = null,
    /**
     * 最近一次检查得到的安装包信息，**「已是最新」时同样非空**。
     * 常驻的「下载安装包」入口据此工作：重装、或把安装包分享给他人时都会用到，
     * 没有它就只能干看着一句「已是最新版本」。
     */
    val downloadable: UpdateInfo? = null,
    /** 应用内下载并校验安装包的进度；[downloadProgress] 为 -1 表示总大小未知。 */
    val downloading: Boolean = false,
    val downloadProgress: Float = -1f,
    val message: String? = null
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

    // 「在一起」起始日与计数前缀已下沉到纪念日域（IA-6），由 AnniversaryViewModel 暴露。

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

    /** 撤销「自启动已确认」的记录（SET1）：用户点撤销或重新设置时回退状态。 */
    fun clearAutoStartConfirmed() {
        appPreferences.setAutoStartConfirmed(false)
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

    /** 检查 GitHub 上的最新版本；有新版本则弹窗，没有则只更新可下载信息。 */
    fun checkForUpdate() {
        if (_updateState.value.checking) return
        _updateState.value = _updateState.value.copy(checking = true, message = null, available = null)
        viewModelScope.launch {
            when (val result = appUpdater.checkForUpdate()) {
                // 「已是最新」也带回安装包信息：常驻下载入口靠它保持可用。
                is UpdateCheckResult.UpToDate -> _updateState.value = _updateState.value.copy(
                    checking = false,
                    downloadable = result.info,
                    message = "已是最新版本 v${result.currentVersion}"
                )

                is UpdateCheckResult.Available -> _updateState.value = _updateState.value.copy(
                    checking = false,
                    available = result.info,
                    downloadable = result.info
                )

                is UpdateCheckResult.Failed -> _updateState.value = _updateState.value.copy(
                    checking = false,
                    message = result.message
                )
            }
        }
    }

    /**
     * 交给浏览器下载安装包（[direct] 为 false 走加速地址、true 走直连）。
     *
     * 应用内下载与安装已移除：设备上普遍拿不到「安装未知来源应用」授权，应用内调起的
     * 安装器在多数机型走不通。改为浏览器下载，完成后用户在通知栏点开安装包，是各 ROM
     * 放行度最高的路径，安装授权也由浏览器侧引导。
     *
     * 弹窗与常驻入口共用本方法：常驻入口可能在还没检查过版本时被点，此时先查一次再下载；
     * 用户点这个按钮的意图就是下载，因此不再弹确认窗打断。
     */
    fun downloadInBrowser(direct: Boolean) {
        val known = _updateState.value.available ?: _updateState.value.downloadable
        if (known != null) {
            openBrowser(known, direct)
            return
        }
        if (_updateState.value.checking) return
        _updateState.value = _updateState.value.copy(checking = true, message = null)
        viewModelScope.launch {
            when (val result = appUpdater.checkForUpdate()) {
                is UpdateCheckResult.UpToDate -> {
                    _updateState.value =
                        _updateState.value.copy(checking = false, downloadable = result.info)
                    openBrowser(result.info, direct)
                }

                is UpdateCheckResult.Available -> {
                    _updateState.value = _updateState.value.copy(
                        checking = false,
                        available = result.info,
                        downloadable = result.info
                    )
                    openBrowser(result.info, direct)
                }

                is UpdateCheckResult.Failed -> _updateState.value = _updateState.value.copy(
                    checking = false,
                    message = result.message
                )
            }
        }
    }

    /** 打开浏览器下载并给出结果提示；已跳转浏览器，弹窗使命结束，一并关掉。 */
    private fun openBrowser(info: UpdateInfo, direct: Boolean) {
        val urls = appUpdater.browserDownloadUrls(info.apkUrl)
        val url = if (direct) urls.lastOrNull() else urls.firstOrNull()
        if (url == null) {
            _updateState.value = _updateState.value.copy(message = "没有可用的下载地址")
            return
        }
        val launched = appUpdater.openInBrowser(url)
        _updateState.value = _updateState.value.copy(
            available = null,
            message = if (launched) {
                "已交给浏览器下载，完成后在通知栏点开安装包即可更新"
            } else {
                "没有找到可以打开链接的浏览器"
            }
        )
    }

    /**
     * 应用内下载并校验安装：先比对发布方 SHA-256，再比对 APK 签名与当前应用是否一致。
     *
     * 与 [downloadInBrowser] 的分工：这里能保证「装上的确实是发布者签名的包」，
     * 即使下载走了第三方加速；浏览器路径只作为个别 ROM 调不起安装器时的兜底。
     * 未授予「安装未知应用」时先跳系统授权页，不直接失败。
     */
    fun downloadAndInstall() {
        if (_updateState.value.downloading) return
        val known = _updateState.value.available ?: _updateState.value.downloadable
        if (known != null) {
            startVerifiedInstall(known)
            return
        }
        if (_updateState.value.checking) return
        _updateState.value = _updateState.value.copy(checking = true, message = null)
        viewModelScope.launch {
            when (val result = appUpdater.checkForUpdate()) {
                is UpdateCheckResult.UpToDate -> {
                    _updateState.value = _updateState.value.copy(
                        checking = false,
                        downloadable = result.info
                    )
                    startVerifiedInstall(result.info)
                }

                is UpdateCheckResult.Available -> {
                    _updateState.value = _updateState.value.copy(
                        checking = false,
                        available = null,
                        downloadable = result.info
                    )
                    startVerifiedInstall(result.info)
                }

                is UpdateCheckResult.Failed -> _updateState.value = _updateState.value.copy(
                    checking = false,
                    message = result.message
                )
            }
        }
    }

    private fun startVerifiedInstall(info: UpdateInfo) {
        if (!appUpdater.canInstallPackages()) {
            appUpdater.openInstallPermissionSettings()
            _updateState.value = _updateState.value.copy(
                available = null,
                message = "请先允许本应用安装未知应用；授权后回来再点「校验安装」"
            )
            return
        }
        _updateState.value = _updateState.value.copy(
            downloading = true,
            downloadProgress = -1f,
            available = null,
            message = null
        )
        viewModelScope.launch {
            val result = appUpdater.downloadAndVerify(info) { progress ->
                _updateState.value = _updateState.value.copy(
                    downloading = true,
                    downloadProgress = progress,
                    message = null
                )
            }
            when (result) {
                is ApkDownloadResult.Success -> {
                    _updateState.value = _updateState.value.copy(
                        downloading = false,
                        downloadProgress = -1f
                    )
                    when (val install = appUpdater.installApk(result.file)) {
                        ApkInstallResult.Started -> _updateState.value = _updateState.value.copy(
                            message = "已通过 SHA-256 与签名校验，正在打开系统安装器"
                        )

                        ApkInstallResult.NeedsPermission -> {
                            appUpdater.openInstallPermissionSettings()
                            _updateState.value = _updateState.value.copy(
                                message = "已通过校验，请先允许安装未知应用后再安装"
                            )
                        }

                        is ApkInstallResult.Failed -> _updateState.value = _updateState.value.copy(
                            message = "已通过校验，但无法打开系统安装器：${install.message}。" +
                                "可改用浏览器下载。"
                        )
                    }
                }

                is ApkDownloadResult.Failed -> _updateState.value = _updateState.value.copy(
                    downloading = false,
                    downloadProgress = -1f,
                    message = result.message
                )
            }
        }
    }

    /** 关闭「新版本」弹窗，保留用户稍后再更新。 */
    fun dismissUpdateDialog() {
        _updateState.value = _updateState.value.copy(available = null)
    }

    fun consumeUpdateMessage() {
        _updateState.value = _updateState.value.copy(message = null)
    }
}
