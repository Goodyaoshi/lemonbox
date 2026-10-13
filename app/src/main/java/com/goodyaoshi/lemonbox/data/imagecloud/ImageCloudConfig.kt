package com.goodyaoshi.lemonbox.data.imagecloud

import com.goodyaoshi.lemonbox.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 图片云端存储的**配置就绪度**：分开表达「缺哪一项」，界面据此给出精确引导，
 * 而不是笼统说一句「未配置」。这四态也决定了上传路径是否应该动作。
 */
data class ImageCloudReadiness(
    /** 当前包是否支持该功能：仅正式包为 true（见 build.gradle.kts）。 */
    val supported: Boolean,
    /** endpoint / region / bucket 是否齐备（内置值或用户覆盖任一即可）。 */
    val hasConnectionConfig: Boolean,
    /** 是否已保存访问凭据（AccessKey / SecretKey）。 */
    val hasCredentials: Boolean,
    /** 用户是否已打开开关。 */
    val enabled: Boolean
) {
    /** 具备一切条件，可以上传。 */
    val ready: Boolean
        get() = supported && hasConnectionConfig && hasCredentials && enabled

    /** 第一处待补齐的事项，用于界面文案；都齐时返回 null。 */
    val firstBlocker: String?
        get() = when {
            !supported -> "当前版本不支持图片云端存储，请使用正式版"
            !hasConnectionConfig -> "缺少存储地址配置"
            !hasCredentials -> "还未填写访问凭据"
            !enabled -> "图片云端存储未开启"
            else -> null
        }
}

/**
 * 把三处来源合成一份可直接使用的 [S3Config]（F20）：
 *  1. **内置配置**（BuildConfig.S3_*）——由 CI Secrets 注入 release 包，属非敏感项；
 *  2. **用户覆盖**（[ImageCloudPreferences]）——自建存储或 debug 包时填写，留空即沿用内置；
 *  3. **访问凭据**（[ImageCredentialStore]）——用户填写、Keystore 加密，绝不进包。
 *
 * 这样「配置进包、凭据手填」的分工落到一处分派，其余代码只依赖本类的 [resolve]。
 */
@Singleton
class ImageCloudConfig @Inject constructor(
    private val preferences: ImageCloudPreferences,
    private val credentialStore: ImageCredentialStore
) {

    val supported: Boolean
        get() = BuildConfig.IMAGE_CLOUD_SUPPORTED

    /** 生效的存储地址：用户覆盖优先，否则用内置值。 */
    fun endpoint(): String = effective(preferences.endpointOverride.value, BuildConfig.S3_ENDPOINT)

    /**
     * 生效的区域。Cloudflare R2 用固定的 `auto`；内置值为空时也回落到 `auto`，
     * 因为 R2 是当前主用目标，而 AWS / 其他厂商必然会在内置配置里显式给出区域。
     */
    fun region(): String =
        effective(preferences.regionOverride.value, BuildConfig.S3_REGION).ifBlank { DEFAULT_REGION }

    fun bucket(): String = effective(preferences.bucketOverride.value, BuildConfig.S3_BUCKET)

    /** 对象键前缀；允许为空（表示直接落在桶根的 `images/` 下）。 */
    fun prefix(): String = effective(preferences.prefixOverride.value, BuildConfig.S3_PREFIX)

    /** 连接配置是否齐备；区域有默认值，故只检查地址与桶。 */
    fun hasConnectionConfig(): Boolean =
        endpoint().isNotBlank() && bucket().isNotBlank()

    fun readiness(): ImageCloudReadiness = ImageCloudReadiness(
        supported = supported,
        hasConnectionConfig = hasConnectionConfig(),
        hasCredentials = credentialStore.load() != null,
        enabled = preferences.enabled.value
    )

    /**
     * 合成最终配置；任一条件不满足返回 null。
     * 调用方（同步管理器）遇到 null 一律不动作，避免在配置残缺时发出必然失败的请求。
     */
    fun resolve(): S3Config? {
        if (!supported || !hasConnectionConfig()) return null
        val credentials = credentialStore.load() ?: return null
        return S3Config(
            endpoint = endpoint(),
            region = region(),
            bucket = bucket(),
            accessKey = credentials.accessKey,
            secretKey = credentials.secretKey
        )
    }

    private fun effective(override: String, builtIn: String): String =
        override.trim().ifBlank { builtIn.trim() }

    private companion object {
        const val DEFAULT_REGION = "auto"
    }
}
