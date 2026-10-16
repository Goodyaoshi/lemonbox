package com.goodyaoshi.lemonbox.data.imagecloud

import com.goodyaoshi.lemonbox.BuildConfig
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云存储的就绪度：分开表达「缺哪一项」，界面据此给出精确引导，
 * 而不是笼统说一句「未配置」。这几态也决定了上传路径是否应该动作。
 */
data class ImageCloudReadiness(
    /** 当前包是否支持该功能：仅正式包为 true（见 build.gradle.kts）。 */
    val supported: Boolean,
    /** 包内是否内置了加密的存储配置：缺它说明这个包没配（本地构建），用户自己无能为力。 */
    val hasBundledConfig: Boolean,
    /** 是否已用口令解锁（本机存有解出的完整配置）。 */
    val unlocked: Boolean,
    /** 用户是否已打开开关。 */
    val enabled: Boolean
) {
    /** 具备一切条件，可以上传。 */
    val ready: Boolean
        get() = supported && hasBundledConfig && unlocked && enabled

    /** 第一处待补齐的事项，用于界面文案；都齐时返回 null。 */
    val firstBlocker: String?
        get() = when {
            !supported -> "当前版本不支持云存储，请使用正式版"
            !hasBundledConfig -> "当前安装包未内置云存储配置"
            !unlocked -> "请输入口令解锁云存储"
            !enabled -> "图片上传未开启"
            else -> null
        }
}

/**
 * 云存储的配置来源（F20）。
 *
 * 连接参数与凭据**整份加密后随包内置**（密文由 `tools/encrypt-s3-config.mjs` 用口令生成、
 * 经 CI Secret `LEMON_S3_PAYLOAD_ENCRYPTED` 注入）：不提供修改入口，因为所有客户端必须
 * 指向同一处存储才能互相看见对方的数据；同时包内也不出现明文密钥——解开它需要用户的口令。
 *
 * 用户要做的事只有一件：输入口令。解开后由 [ImageCredentialStore] 加密落本机，
 * 之后同步直接读本机那份，不必反复输入口令。
 */
@Singleton
class ImageCloudConfig @Inject constructor(
    private val preferences: ImageCloudPreferences,
    private val credentialStore: ImageCredentialStore
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 已解锁配置的内存缓存。同步流程会**反复**取 prefix 与凭据（每个对象键都要一次），
     * 而每次读盘都要过一遍 Keystore 解密，故缓存在这里；写路径（unlock / lock）负责同步它。
     */
    @Volatile
    private var cached: ImageCloudCredentials? = null

    @Volatile
    private var cacheLoaded = false

    val supported: Boolean
        get() = BuildConfig.IMAGE_CLOUD_SUPPORTED

    /** 包内是否内置了密文配置；本地自建 release 包时为空，界面据此提示。 */
    fun hasBundledConfig(): Boolean = BuildConfig.S3_PAYLOAD_ENCRYPTED.isNotBlank()

    /** 已解锁的完整配置；未解锁返回 null。 */
    fun credentials(): ImageCloudCredentials? {
        if (cacheLoaded) return cached
        synchronized(this) {
            if (!cacheLoaded) {
                cached = credentialStore.load()
                cacheLoaded = true
            }
        }
        return cached
    }

    /** 是否已解锁。 */
    fun isUnlocked(): Boolean = credentials() != null

    /** 对象键前缀；未解锁时为空串（调用方此时本就不该发请求）。 */
    fun prefix(): String = credentials()?.prefix?.trim() ?: ""

    /**
     * 用口令解开包内密文，并把解出的配置加密存到本机。
     *
     * 注意：PBKDF2 十万次迭代是**刻意慢**的（百毫秒量级），调用方必须放到后台线程执行。
     */
    fun unlock(passphrase: String): UnlockOutcome {
        val payload = BuildConfig.S3_PAYLOAD_ENCRYPTED.trim()
        if (payload.isEmpty()) return UnlockOutcome.NotConfigured

        val plain = S3PayloadCipher.decrypt(payload, passphrase.toCharArray())
            ?: return UnlockOutcome.WrongPassphrase
        val credentials = runCatching {
            json.decodeFromString<ImageCloudCredentials>(plain)
        }.getOrNull()?.takeIf { it.isComplete } ?: return UnlockOutcome.WrongPassphrase

        if (!credentialStore.save(credentials)) return UnlockOutcome.StorageUnavailable
        cached = credentials
        cacheLoaded = true
        return UnlockOutcome.Success
    }

    /** 锁定：丢掉本机解出的配置，下次需重新输入口令。 */
    fun lock() {
        credentialStore.clear()
        cached = null
        cacheLoaded = true
    }

    fun readiness(): ImageCloudReadiness = ImageCloudReadiness(
        supported = supported,
        hasBundledConfig = hasBundledConfig(),
        unlocked = isUnlocked(),
        enabled = preferences.enabled.value
    )

    /**
     * 多设备同步（文字数据）的第一处待补齐事项；都齐时返回 null。
     *
     * **与 [readiness] 的区别是刻意的**：这里看 [ImageCloudPreferences.cloudSyncEnabled]，
     * 不看图片上传开关。两者取舍不同——图片占空间与流量，文字数据体积很小，
     * 有人只想让数据不丢、不愿把照片传上去。早先共用同一个开关时，
     * 关掉图片上传会把文字同步一并停掉，与用户意愿相悖。
     */
    fun cloudSyncBlocker(): String? = when {
        !supported -> "当前版本不支持云同步，请使用正式版"
        !hasBundledConfig() -> "当前安装包未内置云存储配置"
        !isUnlocked() -> "请输入口令解锁云存储"
        !preferences.cloudSyncEnabled.value -> "多设备同步未开启"
        else -> null
    }

    /**
     * 合成最终配置；未解锁、或解出的配置不完整时返回 null。
     * 调用方（同步管理器）遇到 null 一律不动作，避免在配置残缺时发出必然失败的请求。
     */
    fun resolve(): S3Config? {
        if (!supported) return null
        val credentials = credentials() ?: return null
        val endpoint = credentials.endpoint.trim().trimEnd('/')
        val bucket = credentials.bucket.trim()
        if (endpoint.isBlank() || bucket.isBlank()) return null
        return S3Config(
            endpoint = endpoint,
            region = credentials.region.trim().ifBlank { DEFAULT_REGION },
            bucket = bucket,
            accessKey = credentials.accessKey.trim(),
            secretKey = credentials.secretKey.trim()
        )
    }

    private companion object {
        /** Cloudflare R2 用固定的 `auto`；内置值为空时也回落到它。 */
        const val DEFAULT_REGION = "auto"
    }
}
