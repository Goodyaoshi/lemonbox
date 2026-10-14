package com.goodyaoshi.lemonbox.data.imagecloud

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** 一次对象存储操作的结果。「对象不存在」是 HEAD 的正常结果，与错误分开表达。 */
sealed interface S3Outcome {
    data object Success : S3Outcome

    /** HEAD 未命中（404）。对「探在」而言是正常结果，不是失败。 */
    data object NotFound : S3Outcome

    data class Error(val message: String, val httpCode: Int? = null) : S3Outcome
}

/**
 * 一次对象存储操作所需的全部参数：连接配置 + 凭据。
 * 由 [ImageCloudConfig] 在运行时组装（配置来自 BuildConfig，凭据来自加密存储）。
 */
data class S3Config(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String
) {
    /** 去掉尾部斜杠的 endpoint；空白视为未配置。 */
    val normalizedEndpoint: String?
        get() = endpoint.trim().trimEnd('/').takeIf { it.isNotBlank() }

    /** 四项配置是否齐全（凭据单列，因为缺凭据的提示语不同）。 */
    val hasConnectionConfig: Boolean
        get() = normalizedEndpoint != null && region.isNotBlank() && bucket.isNotBlank()
}

/**
 * 极简 S3 兼容客户端：只实现图片同步真正需要的三个动作——
 * HEAD 探在、PUT 上传、连桶自检。刻意不引入 AWS SDK，理由见 [S3Signer]。
 *
 * 采用 path-style 寻址（`endpoint/bucket/key`）：Cloudflare R2、MinIO、阿里云 OSS、
 * 腾讯云 COS 都支持，省去 virtual-host 风格在各家 DNS 与证书上的差异。
 * 若某服务禁用了 path-style，只需改 [objectUrl] 与 [keyCanonicalPath] 两处。
 *
 * 网络栈用 [HttpURLConnection]（项目既有的 OcrModelManager / AppUpdater 同款），
 * 不额外引入 HTTP 客户端依赖。
 */
@Singleton
class S3ImageClient @Inject constructor() {

    private val amzDateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** 探测对象是否已存在：命中返回 [S3Outcome.Success]，未命中返回 [S3Outcome.NotFound]。 */
    fun headObject(config: S3Config, objectKey: String): S3Outcome {
        val base = config.normalizedEndpoint ?: return S3Outcome.Error("存储地址未配置")
        return request(
            config = config,
            method = "HEAD",
            canonicalPath = keyCanonicalPath(config.bucket, objectKey),
            urlText = objectUrl(base, config.bucket, objectKey),
            payloadSha256 = S3Signer.EMPTY_PAYLOAD_SHA256,
            file = null
        )
    }

    /**
     * 上传对象。[payloadSha256] 由调用方给出，与对象键用的是同一份内容哈希，
     * 既作签名摘要也保证键与内容一一对应。同键重复 PUT 是幂等覆盖，不会产生副本。
     */
    fun putObject(
        config: S3Config,
        objectKey: String,
        payloadSha256: String,
        file: File
    ): S3Outcome {
        val base = config.normalizedEndpoint ?: return S3Outcome.Error("存储地址未配置")
        return request(
            config = config,
            method = "PUT",
            canonicalPath = keyCanonicalPath(config.bucket, objectKey),
            urlText = objectUrl(base, config.bucket, objectKey),
            payloadSha256 = payloadSha256,
            file = file
        )
    }

    /**
     * 删除对象。S3 对不存在的键同样返回成功，因此「未命中」也算删除意图已达成。
     *
     * 用于永久删除物品后清理云端孤儿对象：本地副本在上传成功时已被删掉，云端就是唯一副本，
     * 不清理的话对象只增不减。
     */
    fun deleteObject(config: S3Config, objectKey: String): S3Outcome {
        val base = config.normalizedEndpoint ?: return S3Outcome.Error("存储地址未配置")
        return request(
            config = config,
            method = "DELETE",
            canonicalPath = keyCanonicalPath(config.bucket, objectKey),
            urlText = objectUrl(base, config.bucket, objectKey),
            payloadSha256 = S3Signer.EMPTY_PAYLOAD_SHA256,
            file = null
        )
    }

    /** 连桶自检：HEAD 桶根，用来验证 endpoint / region / 凭据是否走得通。 */
    fun checkBucket(config: S3Config): S3Outcome {
        val base = config.normalizedEndpoint ?: return S3Outcome.Error("存储地址未配置")
        if (config.bucket.isBlank()) return S3Outcome.Error("存储桶未配置")
        return request(
            config = config,
            method = "HEAD",
            canonicalPath = "/${config.bucket}",
            urlText = "$base/${config.bucket}",
            payloadSha256 = S3Signer.EMPTY_PAYLOAD_SHA256,
            file = null
        )
    }

    /** 签名用的规范路径：path-style，`/bucket/key`。 */
    private fun keyCanonicalPath(bucket: String, objectKey: String): String =
        "/$bucket/${S3Signer.uriEncode(objectKey, encodeSlash = false)}"

    /** 实际请求 URL，与规范路径保持同一份编码，避免「签的路径」与「发的路径」不一致。 */
    private fun objectUrl(base: String, bucket: String, objectKey: String): String =
        "$base/$bucket/${S3Signer.uriEncode(objectKey, encodeSlash = false)}"

    /**
     * 构造一个已按 SigV4 签好名的连接。
     * [request] 与 [getObject] 共用它，避免两处各写一遍签名（写歪一处就只在这一条路径上失败，
     * 症状还都是「403」，很难查）。
     */
    private fun openSigned(
        config: S3Config,
        method: String,
        canonicalPath: String,
        urlText: String,
        payloadSha256: String
    ): HttpURLConnection {
        val url = URL(urlText)
        // Host 头必须与签名里的一致：默认端口时 URL 不带端口，HttpURLConnection 也不会带。
        val hostHeader = if (url.port != -1) "${url.host}:${url.port}" else url.host
        val amzDate = ZonedDateTime.now(ZoneOffset.UTC).format(amzDateFormatter)
        val authorization = S3Signer.authorization(
            method = method,
            host = hostHeader,
            canonicalPath = canonicalPath,
            query = emptyMap(),
            payloadHash = payloadSha256,
            region = config.region,
            accessKey = config.accessKey,
            secretKey = config.secretKey,
            amzDate = amzDate,
            extraSignedHeaders = mapOf(
                "x-amz-content-sha256" to payloadSha256,
                "x-amz-date" to amzDate
            )
        )
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // 关闭透明压缩：对已签名的对象请求，中间层改写响应会干扰校验。
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("x-amz-content-sha256", payloadSha256)
            setRequestProperty("x-amz-date", amzDate)
            setRequestProperty("Authorization", authorization)
        }
    }

    /**
     * 生成一条可临时读取该对象的预签名 URL（默认 1 小时有效）。
     *
     * 用途：私有桶里让 Coil 直接加载图片——把签名放进 URL，凭据本身不必交给图片加载器，
     * 链接到期自动失效。配置不全时返回 null，调用方据此回退到本地文件。
     */
    fun presignedGetUrl(
        config: S3Config,
        objectKey: String,
        expiresSeconds: Long = DEFAULT_PRESIGN_SECONDS
    ): String? {
        val base = config.normalizedEndpoint ?: return null
        val url = runCatching { URL(objectUrl(base, config.bucket, objectKey)) }.getOrNull()
            ?: return null
        val hostHeader = if (url.port != -1) "${url.host}:${url.port}" else url.host
        val amzDate = ZonedDateTime.now(ZoneOffset.UTC).format(amzDateFormatter)
        return S3Signer.presignedUrl(
            method = "GET",
            host = hostHeader,
            baseUrl = base,
            canonicalPath = keyCanonicalPath(config.bucket, objectKey),
            region = config.region,
            accessKey = config.accessKey,
            secretKey = config.secretKey,
            amzDate = amzDate,
            expiresSeconds = expiresSeconds
        )
    }

    /**
     * 验证「预签名 URL + GET」这条读取链路是否真的走得通。
     *
     * 为什么必须单独验证：上传走的是「请求头签名 + PUT」，读取走的是「预签名 URL + GET」，
     * 是两条不同的签名路径——上传成功推不出读取可用。删除本地副本前先确认这条链路通，
     * 否则一旦签名不被服务端接受，就会出现「云端有、本地删了、图却取不回来」的不可逆损失。
     *
     * 用 GET 而不是 HEAD：预签名 URL 里的签名是针对 GET 方法算的，改发 HEAD 会因方法
     * 不匹配被判 403，从而把「能读」误判成「不能读」。
     */
    fun verifyPresignedReadable(config: S3Config, objectKey: String): Boolean {
        val url = presignedGetUrl(config, objectKey) ?: return false
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            val ok = conn.responseCode in 200..299
            // 读一个字节即断开：只验证权限与签名，不把整张图拉下来。
            if (ok) runCatching { conn.inputStream.use { it.read() } }
            ok
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 下载对象到 [target]。
     *
     * 先写 `.download` 临时文件、校验通过后再改名：中途断网若是直接写目标文件，
     * 会留下一个「看起来存在、其实残缺」的图片，被当成有效缓存长期用下去。
     */
    fun getObject(config: S3Config, objectKey: String, target: File): S3Outcome {
        val base = config.normalizedEndpoint ?: return S3Outcome.Error("存储地址未配置")
        var conn: HttpURLConnection? = null
        var tmp: File? = null
        return try {
            conn = openSigned(
                config = config,
                method = "GET",
                canonicalPath = keyCanonicalPath(config.bucket, objectKey),
                urlText = objectUrl(base, config.bucket, objectKey),
                payloadSha256 = S3Signer.EMPTY_PAYLOAD_SHA256
            )
            when (val code = conn.responseCode) {
                in 200..299 -> {
                    target.parentFile?.mkdirs()
                    tmp = File(target.path + DOWNLOAD_SUFFIX)
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    // 改名可能在个别文件系统上失败，退回「复制 + 删临时」。
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    S3Outcome.Success
                }

                404 -> S3Outcome.NotFound
                403 -> S3Outcome.Error("无权读取这张图片", code)
                else -> S3Outcome.Error(errorText(conn, code), code)
            }
        } catch (e: Exception) {
            S3Outcome.Error(e.message ?: "下载失败，请检查网络后重试")
        } finally {
            conn?.disconnect()
            // 失败路径留下的半成品一律清掉，别让它冒充有效缓存。
            tmp?.takeIf { it.exists() }?.delete()
        }
    }

    private fun request(
        config: S3Config,
        method: String,
        canonicalPath: String,
        urlText: String,
        payloadSha256: String,
        file: File?
    ): S3Outcome {
        var conn: HttpURLConnection? = null
        return try {
            conn = openSigned(config, method, canonicalPath, urlText, payloadSha256)
            if (file != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", CONTENT_TYPE)
                // 对象键由图片内容的哈希推导，同一键的内容永不改变，因此可以长期缓存。
                // 这一行不只是优化：若响应缺少可缓存指令，图片加载器可能拒绝写磁盘缓存，
                // 于是「缓存键按对象键固定」的设计就落空了——离线时依然看不到图。
                // Cache-Control 属标准 PUT 头，会存进对象元数据，之后的 GET（含预签名）都会带上。
                conn.setRequestProperty("Cache-Control", CACHE_CONTROL_IMMUTABLE)
                conn.setFixedLengthStreamingMode(file.length())
                file.inputStream().use { input ->
                    conn.outputStream.use { output -> input.copyTo(output) }
                }
            }
            when (val code = conn.responseCode) {
                in 200..299 -> S3Outcome.Success
                404 -> S3Outcome.NotFound
                403 -> S3Outcome.Error("凭据无效或无权访问该存储桶", code)
                else -> S3Outcome.Error(errorText(conn, code), code)
            }
        } catch (e: Exception) {
            S3Outcome.Error(e.message ?: "网络异常，请检查网络后重试")
        } finally {
            conn?.disconnect()
        }
    }

    /** 读服务端错误正文（截断），拿不到就退回状态码描述。 */
    private fun errorText(conn: HttpURLConnection, code: Int): String =
        runCatching {
            conn.errorStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.trim()?.take(200)?.takeIf { it.isNotBlank() } ?: "HTTP $code"

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
        const val CONTENT_TYPE = "image/jpeg"

        /** 预签名链接默认有效期：1 小时，够一次浏览；图片一旦被 Coil 缓存就不再重复拉取。 */
        const val DEFAULT_PRESIGN_SECONDS = 3_600L

        /** 下载中的临时文件后缀，成功改名后才成为正式文件。 */
        const val DOWNLOAD_SUFFIX = ".download"

        /**
         * 上传时给对象打上的缓存策略：内容寻址（键=内容哈希）意味着一经写入内容永不改变，
         * 因此可被长期、不可变地缓存。一年 + immutable 足以让图片加载器放心落盘并长期复用，
         * 也让「按对象键缓存」这条设计真正生效（离线可命中）。
         */
        const val CACHE_CONTROL_IMMUTABLE = "public, max-age=31536000, immutable"
    }
}
