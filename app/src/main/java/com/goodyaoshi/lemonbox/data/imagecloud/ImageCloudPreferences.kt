package com.goodyaoshi.lemonbox.data.imagecloud

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云存储的用户偏好：图片上传开关、自动上传、后台补传，以及**独立**的多设备同步开关。
 *
 * 独立的 SharedPreferences 文件：与其他偏好零耦合，且不会被备份 / 导出流程顺带打包走。
 *
 * 存储连接参数与密钥不在这里、也不在本机任何地方——它们只存在服务端（Cloudflare Worker），
 * 由 [ImageCloudConfig] 依据登录态判断可用性。
 */
@Singleton
class ImageCloudPreferences @Inject constructor(
    @ApplicationContext context: Context
) {

    private val preferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))

    /** 是否启用**图片上传**；关闭时图片上传路径都不动作（已传的图仍可从云端读取）。 */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    /**
     * 是否启用**多设备数据同步**（物品、记账等文字数据，不含图片）。
     *
     * 与 [enabled] 相互独立：图片上传耗空间与流量，文字同步体积很小，
     * 两者的取舍不同——有人只想让数据不丢、不愿把照片传上去。
     * 默认开启：真正的门槛是「是否已输口令解锁」，没解锁时本就不会动作。
     */
    private val _cloudSyncEnabled = MutableStateFlow(preferences.getBoolean(KEY_CLOUD_SYNC_ENABLED, true))

    val cloudSyncEnabled: StateFlow<Boolean> = _cloudSyncEnabled.asStateFlow()

    fun setCloudSyncEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_CLOUD_SYNC_ENABLED, value).apply()
        _cloudSyncEnabled.value = value
    }

    private val _autoUpload = MutableStateFlow(preferences.getBoolean(KEY_AUTO_UPLOAD, true))

    /** 保存物品后是否自动上传该物品的图片。存量图片由手动全量同步负责。 */
    val autoUpload: StateFlow<Boolean> = _autoUpload.asStateFlow()

    fun setAutoUpload(value: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_UPLOAD, value).apply()
        _autoUpload.value = value
    }

    private val _backgroundSync = MutableStateFlow(preferences.getBoolean(KEY_BACKGROUND_SYNC, true))

    /** 是否允许后台定期补传；后台任务受限于 WiFi，不消耗移动数据。 */
    val backgroundSync: StateFlow<Boolean> = _backgroundSync.asStateFlow()

    fun setBackgroundSync(value: Boolean) {
        preferences.edit().putBoolean(KEY_BACKGROUND_SYNC, value).apply()
        _backgroundSync.value = value
    }

    private companion object {
        const val PREFS_NAME = "lemon_image_cloud"
        const val KEY_ENABLED = "enabled"
        const val KEY_AUTO_UPLOAD = "auto_upload"
        const val KEY_BACKGROUND_SYNC = "background_sync"
        const val KEY_CLOUD_SYNC_ENABLED = "cloud_sync_enabled"
    }
}
