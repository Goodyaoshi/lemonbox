package com.goodyaoshi.lemonbox.data.imagecloud

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 签名（S3 兼容对象存储通用，见 F20 云存储）。
 *
 * 自实现而不引 AWS SDK：SDK for Android 会带入数 MB 依赖，而本项目以直接分发 APK 为主、
 * 对包体敏感（见 build.gradle.kts 的 ABI 收敛与 .so 压缩），且这里只需 PUT / HEAD 两种签名。
 * 按官方算法实现并用官方测试向量做单测，比引入整个 SDK 更可控。
 *
 * 不持有凭据（每次由调用方传入），全部为纯 JVM 计算，可直接单测。
 */
object S3Signer {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val SERVICE = "s3"
    private const val TERMINATOR = "aws4_request"

    /** 空请求体的 SHA-256（GET / HEAD 用），避免每次重复计算。 */
    const val EMPTY_PAYLOAD_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /**
     * 预签名（query 式签名）时的载荷摘要占位符。
     * 预签名不覆盖正文——签名是事先算好的，签名时并不知道将来会传什么，
     * 因此 AWS 规定此处固定填该常量，而不是真实正文的摘要。
     */
    const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    /** AWS 规定预签名有效期上限为 7 天。 */
    const val MAX_PRESIGN_EXPIRES_SECONDS = 604_800L

    /**
     * 生成预签名 URL（query 式签名）。
     *
     * 与 [authorization] 的分工：请求头式签名用于我们自己发起的 PUT / HEAD；
     * 预签名 URL 用于**把读权限临时授出去**——私有桶里 Coil 要直接 GET 图片，
     * 又不能让 App 把凭据交给它，于是把签名放进 URL 的查询串，谁拿到这条链接谁就能读，
     * 且到期自动失效。
     *
     * 与 [authorization] 共用同一套密钥链与 string-to-sign 结构，因此
     * 后者的官方测试向量同时也为这里的正确性背书；唯一差别是载荷摘要固定为
     * [UNSIGNED_PAYLOAD]、且签名信息进查询串而非请求头。
     *
     * @param host 参与签名的 Host 头值（含端口，如果 URL 带端口）。
     * @param baseUrl 形如 `https://host[:port]`，仅用于拼出最终 URL。
     * @param canonicalPath 已按 S3 规则编码、以 `/` 开头的路径。
     */
    fun presignedUrl(
        method: String,
        host: String,
        baseUrl: String,
        canonicalPath: String,
        region: String,
        accessKey: String,
        secretKey: String,
        amzDate: String,
        expiresSeconds: Long
    ): String {
        val dateStamp = amzDate.substring(0, 8)
        val scope = "$dateStamp/$region/$SERVICE/$TERMINATOR"
        val query = linkedMapOf(
            "X-Amz-Algorithm" to ALGORITHM,
            "X-Amz-Credential" to "$accessKey/$scope",
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to expiresSeconds
                .coerceIn(1L, MAX_PRESIGN_EXPIRES_SECONDS)
                .toString(),
            "X-Amz-SignedHeaders" to "host"
        )
        val canonicalQuery = canonicalQuery(query)
        val canonicalRequest = listOf(
            method,
            canonicalPath,
            canonicalQuery,
            "host:$host\n",
            "host",
            UNSIGNED_PAYLOAD
        ).joinToString("\n")

        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            scope,
            sha256Hex(canonicalRequest)
        ).joinToString("\n")

        val signature = hmacHex(signingKey(secretKey, dateStamp, region), stringToSign)
        // 返回串直接复用 canonicalQuery，保证「签的那份查询串」与「发出去的那份」逐字一致。
        return "$baseUrl$canonicalPath?$canonicalQuery&X-Amz-Signature=$signature"
    }

    /**
     * 生成 `Authorization` 头。
     *
     * @param canonicalPath 已按 S3 规则编码的路径，以 `/` 开头（斜杠本身不编码）。
     * @param query 查询参数，键值均为**未编码**原文，本方法内部按规则编码并排序。
     * @param payloadHash 请求体的十六进制 SHA-256；GET / HEAD 传 [EMPTY_PAYLOAD_SHA256]。
     * @param amzDate 形如 `20130524T000000Z` 的 UTC 时间戳。
     * @param extraSignedHeaders 除 host 外需要参与签名的头（键为头名，如 `x-amz-content-sha256`）。
     */
    fun authorization(
        method: String,
        host: String,
        canonicalPath: String,
        query: Map<String, String>,
        payloadHash: String,
        region: String,
        accessKey: String,
        secretKey: String,
        amzDate: String,
        extraSignedHeaders: Map<String, String> = emptyMap()
    ): String {
        val dateStamp = amzDate.substring(0, 8)
        val canonicalQuery = canonicalQuery(query)
        val normalizedHeaders = extraSignedHeaders.mapKeys { it.key.lowercase() }
        val canonicalHeaders = buildString {
            append("host:").append(host).append('\n')
            normalizedHeaders.entries
                .sortedBy { it.key }
                .forEach { append(it.key).append(':').append(it.value.trim()).append('\n') }
        }
        val signedHeaders = (normalizedHeaders.keys + "host").sorted().joinToString(";")
        val canonicalRequest = listOf(
            method,
            canonicalPath,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ).joinToString("\n")

        val scope = "$dateStamp/$region/$SERVICE/$TERMINATOR"
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            scope,
            sha256Hex(canonicalRequest)
        ).joinToString("\n")

        val signature = hmacHex(signingKey(secretKey, dateStamp, region), stringToSign)
        // 与 AWS 文档的格式逐字对齐：逗号分隔且不带空格，避免严格实现拒收。
        return "$ALGORITHM Credential=$accessKey/$scope," +
            "SignedHeaders=$signedHeaders,Signature=$signature"
    }

    /** 规范化查询串：按键排序，键值都做 URI 编码。 */
    private fun canonicalQuery(query: Map<String, String>): String =
        query.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) ->
                "${uriEncode(key, encodeSlash = true)}=${uriEncode(value, encodeSlash = true)}"
            }

    /**
     * 供调用方拼实际请求 URL 用：与签名内部的规范查询串**是同一份实现**，
     * 保证「签的那串」与「发的这串」逐字一致（差一个字符就是 403）。
     */
    fun canonicalQueryString(query: Map<String, String>): String = canonicalQuery(query)

    /**
     * S3 的 URI 编码：仅 `A-Za-z0-9-_.~` 原样保留，其余（含非 ASCII、空格、`+`、`=`）
     * 一律按 UTF-8 字节百分号编码。`/` 是否编码由 [encodeSlash] 决定——
     * 路径里保留斜杠、查询串里编码斜杠。
     */
    fun uriEncode(value: String, encodeSlash: Boolean): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val ch = code.toChar()
            val unreserved = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~'
            when {
                unreserved -> out.append(ch)
                ch == '/' && !encodeSlash -> out.append('/')
                else -> out.append('%').append(String.format("%02X", code))
            }
        }
        return out.toString()
    }

    /** 把对象键编码成 S3 规范路径（保留斜杠），并确保以 `/` 开头。 */
    fun canonicalPathFor(key: String, bucket: String, includeBucket: Boolean): String {
        val encodedKey = uriEncode(key, encodeSlash = false)
        return if (includeBucket) "/$bucket/$encodedKey" else "/$encodedKey"
    }

    fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** 派生签名密钥：HMAC 链 AWS4 + date + region + service，最后得到 aws4_request 密钥。 */
    private fun signingKey(secretKey: String, dateStamp: String, region: String): ByteArray {
        val kDate = hmac("AWS4$secretKey".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, SERVICE)
        return hmac(kService, TERMINATOR)
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacHex(key: ByteArray, data: String): String = hmac(key, data).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
}
