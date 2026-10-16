package com.goodyaoshi.lemonbox.data.imagecloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * 云存储的完整配置：连接参数 + 访问凭据。
 *
 * 它是「口令解锁」的产物——用户在界面里只填口令，[S3PayloadCipher] 从包内密文里解出
 * 这些字段，随即由 [ImageCredentialStore] 加密落到本机。之后每次同步都读本机这份，
 * 不再需要口令。
 */
@Serializable
data class ImageCloudCredentials(
    val endpoint: String,
    val region: String = "",
    val bucket: String,
    val prefix: String = "",
    val accessKey: String,
    val secretKey: String
) {
    /**
     * 是否可用：地址、桶与一对凭据都不能缺。
     * region 有内置回落（见 [ImageCloudConfig]），prefix 允许为空，故二者不参与判定。
     */
    val isComplete: Boolean
        get() = endpoint.isNotBlank() && bucket.isNotBlank() &&
            accessKey.isNotBlank() && secretKey.isNotBlank()
}

/**
 * 解锁结果。界面据此给出不同提示；「密钥没配」与「口令不对」必须分开——
 * 前者用户无能为力（得换包），后者用户重输即可。
 */
sealed interface UnlockOutcome {
    data object Success : UnlockOutcome

    /** 包内没有内置密文（开发者本地构建，或 CI 未配 Secret）。 */
    data object NotConfigured : UnlockOutcome

    /** 口令不对，或密文被损坏。 */
    data object WrongPassphrase : UnlockOutcome

    /** 系统 Keystore 不可用，解出来也存不住。 */
    data object StorageUnavailable : UnlockOutcome
}

/**
 * 云存储配置的本地加密存储（F20）。
 *
 * 安全前提：**密钥绝不进 APK 明文**——BuildConfig 是明文字符串常量，反编译即可读出。
 * 因此包内只放 [S3PayloadCipher] 生成的密文（还需要用户的口令才能解开），
 * 口令解开后的完整配置则落在本机、由 Keystore 里的 AES-256-GCM 再加密一层。
 *
 * 加密方式：Android Keystore 的 AES-256-GCM（密钥材料不出安全硬件），密文与随机 IV 存进
 * 独立 SharedPreferences。未引入已弃用的 androidx.security:security-crypto——只需一对
 * 加解密调用，手写依赖更少。Keystore 密钥可能因「清除数据 / 恢复出厂 / 锁屏凭据变更」失效，
 * 此时一律按「没有配置」返回 null（不抛异常），由界面引导重新输入口令解锁。
 */
@Singleton
class ImageCredentialStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val preferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 读取已解锁的配置；未解锁或解密失败（如 Keystore 密钥失效）时返回 null，
     * 并顺手清掉无法解密的脏数据，避免每次都白试一遍。
     */
    fun load(): ImageCloudCredentials? {
        preferences.edit().remove(LEGACY_KEY_CREDENTIALS).apply()
        val blob = preferences.getString(KEY_CREDENTIALS, null) ?: return null
        val plain = decrypt(blob)
        if (plain == null) {
            preferences.edit().remove(KEY_CREDENTIALS).apply()
            return null
        }
        val credentials = runCatching { json.decodeFromString<ImageCloudCredentials>(plain) }.getOrNull()
        if (credentials == null || !credentials.isComplete) {
            preferences.edit().remove(KEY_CREDENTIALS).apply()
            return null
        }
        return credentials
    }

    /** 加密保存配置；加密失败（Keystore 不可用）返回 false，由调用方提示用户。 */
    fun save(credentials: ImageCloudCredentials): Boolean {
        val blob = encrypt(json.encodeToString(credentials)) ?: return false
        preferences.edit().putString(KEY_CREDENTIALS, blob).apply()
        return true
    }

    fun clear() {
        preferences.edit().remove(KEY_CREDENTIALS).remove(LEGACY_KEY_CREDENTIALS).apply()
    }

    fun hasCredentials(): Boolean = preferences.contains(KEY_CREDENTIALS)

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
                // 不要求用户验证：上传可能在后台发生，不能被锁屏打断。
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "lemon_image_cloud_secrets"
        // v2：存的是整份配置的 JSON（含连接参数）。v1 存的是「accessKey\nsecretKey」明文，
        // 那个格式在「口令解锁」模型下已无意义，读到就顺手清掉。
        const val KEY_CREDENTIALS = "credentials_v2"
        const val LEGACY_KEY_CREDENTIALS = "credentials_v1"
        const val KEY_ALIAS = "lemonbox_image_cloud_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}
