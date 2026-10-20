package com.goodyaoshi.lemonbox.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.goodyaoshi.lemonbox.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次登录的会话：连哪台服务端、拿什么令牌、以谁的身份。
 *
 * [baseUrl] 一并存下来，是为了在**换了服务端地址的新版本**上自动失效——
 * 旧地址发的令牌拿到新地址去用只会是 401，与其让用户看到莫名其妙的报错，
 * 不如直接当作没登录、重新登一次。
 */
@Serializable
data class RemoteSession(
    val baseUrl: String,
    val token: String,
    val userId: String,
    val username: String,
    /** 令牌到期时刻（毫秒）；[token] 是真凭据，此处只用于提前提示与本地失效判断。 */
    val expiresAt: Long
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAt <= now
}

/**
 * 登录会话的本地加密存储。
 *
 * 会话令牌是能读写她全部家庭数据的凭据，因此与图片凭据同样处理：
 * 用 Android Keystore 的 AES-256-GCM 加密后落本机（密钥材料不出安全硬件），
 * 明文既不进安装包，也不进日志。未引入已弃用的 androidx.security:security-crypto，
 * 手写一对加解密调用依赖更少。
 *
 * Keystore 密钥可能因「清除数据 / 恢复出厂 / 锁屏凭据变更」失效，
 * 此时一律按「没登录」返回（不抛异常），界面引导重新登录即可。
 */
@Singleton
class SyncSessionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val endpointStore: SyncEndpointStore
) {

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 当前会话；未登录 / 令牌过期 / 服务端地址变了都为 null。
     * 直接暴露成流，界面（登录页、数据中心）据此显示登录态。
     */
    private val _session = MutableStateFlow(loadValid())

    val session: StateFlow<RemoteSession?> = _session.asStateFlow()

    /** 是否处于已登录状态（令牌未过期且指向当前服务端）。 */
    val isLoggedIn: Boolean
        get() = _session.value != null

    fun current(): RemoteSession? = _session.value

    /** 保存会话；Keystore 不可用时返回 false，由调用方提示用户。 */
    fun save(session: RemoteSession): Boolean {
        val blob = encrypt(json.encodeToString(RemoteSession.serializer(), session)) ?: return false
        preferences.edit().putString(KEY_SESSION, blob).apply()
        _session.value = session
        return true
    }

    /** 退出登录：只清本机凭据，不动服务端的任何数据。 */
    fun clear() {
        preferences.edit().remove(KEY_SESSION).apply()
        _session.value = null
    }

    /**
     * 读取并校验本机会话：解不开、过期、或指向别的服务端都算没登录，
     * 并顺手清掉这份脏数据，免得每次都白试一遍。
     */
    private fun loadValid(): RemoteSession? {
        val blob = preferences.getString(KEY_SESSION, null) ?: return null
        val session = decrypt(blob)
            ?.let { runCatching { json.decodeFromString(RemoteSession.serializer(), it) }.getOrNull() }
        val valid = session != null &&
            session.token.isNotBlank() &&
            !session.isExpired() &&
            endpointStore.isKnownBaseUrl(session.baseUrl)
        if (!valid) {
            preferences.edit().remove(KEY_SESSION).apply()
            return null
        }
        return session
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // IV 每次由 Cipher 随机生成，必须与密文一起保存才能解密。
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(cipherText, Base64.NO_WRAP)
        "$iv:$body"
    }.getOrNull()

    private fun decrypt(blob: String): String? = runCatching {
        val separator = blob.indexOf(':')
        if (separator <= 0) return null
        val iv = Base64.decode(blob.substring(0, separator), Base64.NO_WRAP)
        val body = Base64.decode(blob.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    /** 取（或首次生成）Keystore 里的 AES 密钥；密钥材料不进入应用可读内存。 */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.secretKey
            ?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求用户验证：同步可能在后台发生，不能被锁屏打断。
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "lemon_sync_session"
        const val KEY_SESSION = "session_v1"
        const val KEY_ALIAS = "lemonbox_sync_session"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}
