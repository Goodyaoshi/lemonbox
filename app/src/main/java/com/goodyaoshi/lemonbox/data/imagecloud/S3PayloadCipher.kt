package com.goodyaoshi.lemonbox.data.imagecloud

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 云存储配置的密文格式（F20）加解密。
 *
 * **为什么要有它**：存储地址与 AccessKey / SecretKey 原先或随包明文内置、或由用户手填。
 * 现在整份配置以密文随包分发，App 侧只需要用户输入一个**口令**即可解出全部字段——
 * 各设备不必再各填一份密钥，包内也不再出现明文凭据。
 *
 * 算法与参数（两端必须逐位一致，改动即等于更换格式版本）：
 * - 密钥派生：`PBKDF2WithHmacSHA256`，随机 16 字节 salt，100000 次迭代，派生 256 位；
 *   口令按 UTF-8 编码成字节（`PBEKeySpec(char[])` 在 JDK 与 Android Conscrypt 上都是 UTF-8）。
 * - 加密：`AES/GCM/NoPadding`，随机 12 字节 IV，认证标签 128 位。
 *
 * 密文形态（单行 ASCII，便于放进 GitHub Secret 的一个值里）：
 * ```
 * v1:<base64(salt)>:<base64(iv)>:<base64(ciphertext || tag)>
 * ```
 * 版本前缀 `v1` 让日后换参数时能分辨新旧格式，而不是把旧密文当成「口令错」。
 *
 * **它防不住什么**：密文与 salt / IV 都在 APK 里，拿到包的人可以离线穷举口令。
 * 安全性完全落在口令强度上——请用长口令，不要用字典词。
 *
 * 生成密文的入口见同包 `EncryptS3Payload`（`gradlew encryptS3Payload`）：它就是本文件的调用方，
 * 与 App 共用这一份实现，不存在两端漂移。`S3PayloadCipherTest` 另有固定向量把密文格式钉死。
 */
object S3PayloadCipher {

    private const val PREFIX = "v1"
    private const val KEY_FACTORY_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128

    private val random = SecureRandom()

    /**
     * 加密成密文串。
     *
     * 仅供测试与本地工具使用——正式包内只有密文，从不加密。允许注入 [salt] / [iv]
     * 是为了能复现固定向量（互操作测试），正常调用一律随机生成。
     */
    fun encrypt(
        plaintext: String,
        passphrase: CharArray,
        salt: ByteArray = randomBytes(SALT_LENGTH_BYTES),
        iv: ByteArray = randomBytes(IV_LENGTH_BYTES)
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        // GCM 的 doFinal 输出「密文 || 认证标签」；解密方按同一顺序切分，
        // 这个拼接顺序属于密文格式的一部分，改动即等于更换版本。
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return listOf(
            PREFIX,
            encoder.encodeToString(salt),
            encoder.encodeToString(iv),
            encoder.encodeToString(body)
        ).joinToString(":")
    }

    /**
     * 解密密文串；口令不对、密文被篡改、格式不合法都返回 null（不抛异常）。
     * 调用方据此提示「口令不对」，无需区分失败原因——对用户而言处置都一样。
     */
    fun decrypt(payload: String, passphrase: CharArray): String? = runCatching {
        val parts = payload.trim().split(':')
        if (parts.size != 4 || parts[0] != PREFIX) return null
        val decoder = Base64.getDecoder()
        val salt = decoder.decode(parts[1])
        val iv = decoder.decode(parts[2])
        val body = decoder.decode(parts[3])
        if (salt.isEmpty() || iv.isEmpty() || body.size <= TAG_LENGTH_BITS / 8) return null

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance(KEY_FACTORY_ALGORITHM).generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            // PBEKeySpec 自己复制了一份口令，及时清掉派生用的中间态。
            spec.clearPassword()
        }
    }

    private fun randomBytes(length: Int): ByteArray = ByteArray(length).also { random.nextBytes(it) }
}
