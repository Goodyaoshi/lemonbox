package com.goodyaoshi.lemonbox.data.imagecloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** 对象存储的访问凭据。 */
data class ImageCredentials(
    val accessKey: String,
    val secretKey: String
) {
    val isComplete: Boolean
        get() = accessKey.isNotBlank() && secretKey.isNotBlank()
}

/**
 * S3 访问凭据的本地加密存储（F20）。
 *
 * 安全前提：**凭据绝不进 APK**——BuildConfig 是明文常量，反编译即可读出。
 * 因此 endpoint / bucket 等非敏感项随包走，AccessKey / SecretKey 只存在用户设备上、由用户自己填。
 *
 * 加密方式：Android Keystore 的 AES-256-GCM（密钥材料不出安全硬件），密文与随机 IV 存进
 * 独立 SharedPreferences。未引入已弃用的 androidx.security:security-crypto——只需一对
 * 加解密调用，手写依赖更少。Keystore 密钥可能因「清除数据 / 恢复出厂 / 锁屏凭据变更」失效，
 * 此时一律按「没有凭据」返回 null（不抛异常），由界面引导重新填写。
 */
@Singleton
class ImageCredentialStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val preferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读取凭据；未配置或解密失败（如 Keystore 密钥失效）时返回 null，
     * 并顺手清掉无法解密的脏数据，避免每次都白试一遍。
     */
    fun load(): ImageCredentials? {
        val blob = preferences.getString(KEY_CREDENTIALS, null) ?: return null
        val plain = decrypt(blob)
        if (plain == null) {
            preferences.edit().remove(KEY_CREDENTIALS).apply()
            return null
        }
        // 明文格式：accessKey\nsecretKey（两者都不含换行）。
        val parts = plain.split('\n', limit = 2)
        if (parts.size != 2) {
            preferences.edit().remove(KEY_CREDENTIALS).apply()
            return null
        }
        return ImageCredentials(parts[0], parts[1]).takeIf { it.isComplete }
    }

    /** 加密保存凭据；加密失败（Keystore 不可用）返回 false，由界面提示用户。 */
    fun save(credentials: ImageCredentials): Boolean {
        val blob = encrypt("${credentials.accessKey}\n${credentials.secretKey}") ?: return false
        preferences.edit().putString(KEY_CREDENTIALS, blob).apply()
        return true
    }

    fun clear() {
        preferences.edit().remove(KEY_CREDENTIALS).apply()
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
        const val KEY_CREDENTIALS = "credentials_v1"
        const val KEY_ALIAS = "lemonbox_image_cloud_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}
