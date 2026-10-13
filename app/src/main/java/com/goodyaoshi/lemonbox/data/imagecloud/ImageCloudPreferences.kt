package com.goodyaoshi.lemonbox.data.imagecloud

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 图片云端存储的**非敏感**偏好（F20）。
 *
 * 为什么不并入 AppPreferences：那是一份 1200 余行的全局单例，而图片云配置与其他偏好
 * 零耦合；独立成一份文件既让这块功能自洽，也把改动风险隔离在新增文件里。
 *
 * 独立 SharedPreferences 文件另有一个安全上的好处：它不会被任何备份 / 导出流程
 * 顺带打包走（凭据同理，见 [ImageCredentialStore]）。
 *
 * [endpointOverride] 等四项默认空串，语义是「沿用 APK 内置值」——正式包已随包带上
 * endpoint / region / bucket / prefix（见 build.gradle.kts 的 LEMON_S3_* 注入），
 * 用户开箱只需填凭据；只有自建存储或跑 debug 包时才需要在这里覆盖。
 */
@Singleton
class ImageCloudPreferences @Inject constructor(
    @ApplicationContext context: Context
) {

    private val preferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))

    /** 是否启用图片云端存储；关闭时一切上传路径都不动作。 */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    private val _autoUpload = MutableStateFlow(preferences.getBoolean(KEY_AUTO_UPLOAD, true))

    /** 保存物品后是否自动上传该物品的图片。存量图片仍由设置页的手动全量同步负责。 */
    val autoUpload: StateFlow<Boolean> = _autoUpload.asStateFlow()

    fun setAutoUpload(value: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_UPLOAD, value).apply()
        _autoUpload.value = value
    }

    private val _backgroundSync = MutableStateFlow(preferences.getBoolean(KEY_BACKGROUND_SYNC, true))

    /**
     * 是否允许后台定期补传。默认开启，但后台任务被约束为「仅 WiFi」，
     * 所以不会消耗移动数据；关闭后只剩手动同步与保存时的即时上传。
     */
    val backgroundSync: StateFlow<Boolean> = _backgroundSync.asStateFlow()

    fun setBackgroundSync(value: Boolean) {
        preferences.edit().putBoolean(KEY_BACKGROUND_SYNC, value).apply()
        _backgroundSync.value = value
    }

    private val _endpointOverride = MutableStateFlow(loadString(KEY_ENDPOINT))

    /** 存储地址覆盖值；空表示沿用内置配置。 */
    val endpointOverride: StateFlow<String> = _endpointOverride.asStateFlow()

    private val _regionOverride = MutableStateFlow(loadString(KEY_REGION))

    /** 区域覆盖值；Cloudflare R2 填 `auto`。 */
    val regionOverride: StateFlow<String> = _regionOverride.asStateFlow()

    private val _bucketOverride = MutableStateFlow(loadString(KEY_BUCKET))

    val bucketOverride: StateFlow<String> = _bucketOverride.asStateFlow()

    private val _prefixOverride = MutableStateFlow(loadString(KEY_PREFIX))

    /** 对象键前缀；留空表示直接放在桶根下的 `images/`。 */
    val prefixOverride: StateFlow<String> = _prefixOverride.asStateFlow()

    /** 一次性写入四项连接配置；各字段留空即表示沿用内置值。 */
    fun setConnectionOverrides(
        endpoint: String,
        region: String,
        bucket: String,
        prefix: String
    ) {
        preferences.edit()
            .putString(KEY_ENDPOINT, endpoint.trim())
            .putString(KEY_REGION, region.trim())
            .putString(KEY_BUCKET, bucket.trim())
            .putString(KEY_PREFIX, prefix.trim())
            .apply()
        _endpointOverride.value = endpoint.trim()
        _regionOverride.value = region.trim()
        _bucketOverride.value = bucket.trim()
        _prefixOverride.value = prefix.trim()
    }

    private fun loadString(key: String): String =
        preferences.getString(key, "").orEmpty()

    private companion object {
        const val PREFS_NAME = "lemon_image_cloud"
        const val KEY_ENABLED = "enabled"
        const val KEY_AUTO_UPLOAD = "auto_upload"
        const val KEY_BACKGROUND_SYNC = "background_sync"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_REGION = "region"
        const val KEY_BUCKET = "bucket"
        const val KEY_PREFIX = "prefix"
    }
}
