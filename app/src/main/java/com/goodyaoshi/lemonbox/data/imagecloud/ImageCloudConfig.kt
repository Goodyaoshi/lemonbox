package com.goodyaoshi.lemonbox.data.imagecloud

import com.goodyaoshi.lemonbox.BuildConfig
import com.goodyaoshi.lemonbox.data.remote.SyncSessionStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云存储的就绪度：分开表达「缺哪一项」，界面据此给出精确引导，
 * 而不是笼统说一句「未配置」。这几态也决定了上传路径是否应该动作。
 */
data class ImageCloudReadiness(
    /** 当前包是否支持该功能：仅正式包为 true（见 build.gradle.kts）。 */
    val supported: Boolean,
    /** 是否已登录同步服务端（登录凭证加密存在本机）。 */
    val loggedIn: Boolean,
    /** 用户是否已打开图片上传开关。 */
    val enabled: Boolean
) {
    /** 具备一切条件，可以上传。 */
    val ready: Boolean
        get() = supported && loggedIn && enabled

    /** 第一处待补齐的事项，用于界面文案；都齐时返回 null。 */
    val firstBlocker: String?
        get() = when {
            !supported -> "当前版本不支持云存储，请使用正式版"
            !loggedIn -> "请先登录柠檬账号"
            !enabled -> "图片上传未开启"
            else -> null
        }
}

/**
 * 云存储与多设备同步的配置来源。
 *
 * 与旧机制最大的区别：**客户端不再持有任何长期密钥**。对象存储的
 * endpoint / region / bucket / AccessKey / SecretKey 全部只存在服务端（Cloudflare Worker），
 * 客户端包内只有一个公开域名 [baseUrl] 与用户登录换来的会话令牌；
 * 每次要读写对象时才向 Worker 换**几分钟有效的预签名 URL**。
 * 因此这里不再有「口令解锁」，配置是否可用只取决于**登录态**。
 */
@Singleton
class ImageCloudConfig @Inject constructor(
    private val preferences: ImageCloudPreferences,
    private val sessionStore: SyncSessionStore
) {

    val supported: Boolean
        get() = BuildConfig.IMAGE_CLOUD_SUPPORTED

    /** 同步服务端地址（Worker 的公开域名）。 */
    val baseUrl: String
        get() = BuildConfig.SYNC_BASE_URL.trim().trimEnd('/')

    /** 是否已登录；未登录时所有云路径都必须不动作（纯本地）。 */
    fun isLoggedIn(): Boolean = sessionStore.isLoggedIn

    fun readiness(): ImageCloudReadiness = ImageCloudReadiness(
        supported = supported,
        loggedIn = isLoggedIn(),
        enabled = preferences.enabled.value
    )

    /**
     * 多设备同步（文字数据）的第一处待补齐事项；都齐时返回 null。
     *
     * **与 [readiness] 的区别是刻意的**：这里看 [ImageCloudPreferences.cloudSyncEnabled]，
     * 不看图片上传开关。两者取舍不同——图片占空间与流量，文字数据体积很小，
     * 有人只想让数据不丢、不愿把照片传上去。
     */
    fun remoteSyncBlocker(): String? = when {
        !supported -> "当前版本不支持云同步，请使用正式版"
        !isLoggedIn() -> "请先登录柠檬账号"
        !preferences.cloudSyncEnabled.value -> "多设备同步未开启"
        else -> null
    }

    /**
     * 对象键前缀：由服务端配置（Worker secret），**不下发到客户端**，故此处恒为空串。
     */
    fun prefix(): String = ""
}
