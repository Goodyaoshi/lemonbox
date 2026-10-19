package com.goodyaoshi.lemonbox.data.imagecloud

import com.goodyaoshi.lemonbox.data.remote.RemoteApiException
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** 一次对象存储操作的结果。「对象不存在」是探在的正常结果，与错误分开表达。 */
sealed interface S3Outcome {
    data object Success : S3Outcome

    /** 对象不存在（404）。对「探在」而言是正常结果，不是失败。 */
    data object NotFound : S3Outcome

    data class Error(val message: String, val httpCode: Int? = null) : S3Outcome
}

/** 列出对象时返回的一个条目。[lastModified] 是服务端记录的毫秒时间戳。 */
data class S3ObjectEntry(
    val key: String,
    val lastModified: Long,
    val size: Long
)

/** 列出对象的结果。 */
sealed interface ListObjectsResult {
    data class Success(val objects: List<S3ObjectEntry>) : ListObjectsResult
    data class Failure(val message: String, val httpCode: Int? = null) : ListObjectsResult
}

/** 一页 ListObjectsV2 响应的解析结果。 */
internal data class ListObjectsPage(
    val entries: List<S3ObjectEntry>,
    val isTruncated: Boolean,
    val nextContinuationToken: String?
)

/**
 * 解析 ListObjectsV2 的 XML 响应。
 *
 * 抽成顶层纯函数是为了能直接单测：这段逻辑的输入完全来自服务端，
 * 各家 S3 兼容实现（Filebase / R2 / MinIO）的字段顺序与空白并不一致，
 * 用真实响应样本锁住比在真机上试错可靠。
 *
 * 只取三样东西：对象键、LastModified、大小。键可能含中文（用户文件名），
 * 因此按 UTF-8 解码后再取；XML 实体转义也要还原（`&amp;` 等）。
 */
internal fun parseListObjectsXml(xml: String): ListObjectsPage {
    val contents = Regex("<Contents>(.*?)</Contents>", RegexOption.DOT_MATCHES_ALL)
        .findAll(xml)
        .mapNotNull { match ->
            val block = match.groupValues[1]
            val key = xmlText(block, "Key") ?: return@mapNotNull null
            val lastModified = xmlText(block, "LastModified")
                ?.let { parseAmzDate(it) } ?: 0L
            val size = xmlText(block, "Size")?.toLongOrNull() ?: 0L
            S3ObjectEntry(key = unescapeXml(key), lastModified = lastModified, size = size)
        }
        .toList()

    val truncated = xmlText(xml, "IsTruncated")?.trim().equals("true", ignoreCase = true)
    // 未截断时服务端可能仍回一个空 token，这里统一归一成 null，调用方只看 null 判断结束。
    val token = xmlText(xml, "NextContinuationToken")?.trim()?.takeIf { it.isNotEmpty() }

    return ListObjectsPage(
        entries = contents,
        isTruncated = truncated,
        nextContinuationToken = token
    )
}

/** 取 `<tag>...</tag>` 的文本内容；缺失返回 null。 */
private fun xmlText(xml: String, tag: String): String? =
    Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        .find(xml)
        ?.groupValues
        ?.get(1)

/** 解析形如 `2026-10-14T12:00:00.000Z` 的时间戳（ISO-8601，S3 固定用 UTC）。 */
private fun parseAmzDate(value: String): Long? =
    runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()

/** 还原 XML 实体转义；顺序有讲究，`&amp;` 必须最后处理，否则会把转义过的实体二次还原。 */
private fun unescapeXml(value: String): String =
    value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

/** `/v1/blob/url` 的响应：一条短时预签名 URL。 */
data class PresignedBlobUrl(
    val url: String,
    val expiresAt: Long,
    val method: String
)

/** `/v1/blob/probe` 的响应。[status] 为 0 表示请求本身没走通（见 [message]）。 */
data class BlobProbe(
    val exists: Boolean = false,
    val status: Int = 0,
    val size: Long = 0,
    val message: String? = null
)

/** `/v1/blob/selfcheck` 的响应。 */
data class BlobSelfCheck(
    val ok: Boolean = false,
    val status: Int = 0,
    val message: String? = null
)

@Serializable
private data class BlobUrlRequest(
    val key: String,
    val op: String,
    val ttlSeconds: Long
)

@Serializable
private data class BlobKeyRequest(val key: String)

@Serializable
private data class BlobUrlResponse(
    val url: String,
    val expiresAt: Long,
    val method: String
)

@Serializable
private data class BlobProbeResponse(
    val exists: Boolean = false,
    val status: Int = 0,
    val size: Long = 0,
    val message: String? = null
)

@Serializable
private data class BlobDeleteResponse(val ok: Boolean = false)

@Serializable
private data class BlobSelfCheckResponse(
    val ok: Boolean = false,
    val status: Int = 0,
    val message: String? = null
)

/**
 * 对象存储网关（P8）：客户端不再持有对象存储凭据，也不参与 SigV4 签名。
 *
 * 职责分两层：
 *  1. **换凭据**：向 Worker 的 `/v1/blob/url` 换一条几分钟有效的预签名 URL（鉴权走登录会话）；
 *  2. **直传直取**：拿预签名 URL 对自建桶做裸 PUT/GET，数据不经过 Worker。
 *
 * probe / delete / list / selfcheck 这类轻量操作直接走 Worker 代理——它们要么需要
 * 服务端凭据（探在、删除、列举），要么本来就是服务端自检。
 */
@Singleton
class BlobGateway @Inject constructor(
    private val remoteClient: RemoteSyncClient
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 取一条预签名 URL（[op] 为 `get` / `put`）。
     * 有效期取 [PRESIGN_TTL_SECONDS]：Worker 侧钳制在 30 秒—10 分钟之间。
     * 失败抛 [RemoteApiException]（未登录、网络不通等，文案可直接展示），由调用方决定如何兜底。
     */
    suspend fun presignedUrl(objectKey: String, op: String): PresignedBlobUrl {
        val body = json.encodeToString(
            BlobUrlRequest(key = objectKey, op = op, ttlSeconds = PRESIGN_TTL_SECONDS)
        )
        val response = json.decodeFromString(
            BlobUrlResponse.serializer(),
            remoteClient.authedJsonRequest("POST", "/v1/blob/url", body)
        )
        return PresignedBlobUrl(response.url, response.expiresAt, response.method)
    }

    /**
     * 探测对象是否已存在（走 Worker）。请求没走通时 [BlobProbe.status] 为 0、
     * [BlobProbe.message] 带原因，调用方按「非 404 的不存在」处理即可。
     */
    suspend fun probe(objectKey: String): BlobProbe = try {
        val body = json.encodeToString(BlobKeyRequest(objectKey))
        json.decodeFromString(
            BlobProbeResponse.serializer(),
            remoteClient.authedJsonRequest("POST", "/v1/blob/probe", body)
        ).let { BlobProbe(it.exists, it.status, it.size, it.message) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteApiException) {
        BlobProbe(message = e.message)
    } catch (e: Exception) {
        BlobProbe(message = "连不上服务器，检查网络或稍后再试")
    }

    /**
     * 删除对象（走 Worker）。S3 对不存在的键同样返回成功，返回 true 即删除意图已达成；
     * 网络失败返回 false，调用方保留记录下次再试。
     */
    suspend fun deleteObject(objectKey: String): Boolean = try {
        val body = json.encodeToString(BlobKeyRequest(objectKey))
        json.decodeFromString(
            BlobDeleteResponse.serializer(),
            remoteClient.authedJsonRequest("POST", "/v1/blob/delete", body)
        ).ok
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * 列出指定前缀下的对象（走 Worker，返回原始 ListObjectsV2 XML，复用既有解析器），
     * 自动翻页直到取完。上限兜底防服务端异常导致死循环。
     */
    suspend fun listObjects(prefix: String): ListObjectsResult = try {
        val all = mutableListOf<S3ObjectEntry>()
        var token: String? = null
        repeat(MAX_LIST_PAGES) {
            val params = mutableListOf<String>()
            if (prefix.isNotBlank()) {
                params += "prefix=" + URLEncoder.encode(prefix, "UTF-8")
            }
            token?.let {
                params += "continuation-token=" + URLEncoder.encode(it, "UTF-8")
            }
            val path = if (params.isEmpty()) "/v1/blob/list" else "/v1/blob/list?${params.joinToString("&")}"
            val page = parseListObjectsXml(remoteClient.authedJsonRequest("GET", path, body = null))
            all += page.entries
            token = page.nextContinuationToken ?: return ListObjectsResult.Success(all)
            if (!page.isTruncated) return ListObjectsResult.Success(all)
        }
        ListObjectsResult.Success(all)
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteApiException) {
        ListObjectsResult.Failure(e.message)
    } catch (e: Exception) {
        ListObjectsResult.Failure(e.message ?: "列举失败，请检查网络后重试")
    }

    /** 服务端连桶自检：验证 Worker 侧对象存储配置是否走得通。 */
    suspend fun selfCheck(): BlobSelfCheck = try {
        json.decodeFromString(
            BlobSelfCheckResponse.serializer(),
            remoteClient.authedJsonRequest("GET", "/v1/blob/selfcheck", body = null)
        ).let { BlobSelfCheck(it.ok, it.status, it.message) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteApiException) {
        BlobSelfCheck(message = e.message)
    } catch (e: Exception) {
        BlobSelfCheck(message = "连不上服务器，检查网络或稍后再试")
    }

    /**
     * 上传对象：换一条 PUT 预签名后直传自建桶。同键重复 PUT 是幂等覆盖，不会产生副本。
     */
    suspend fun upload(objectKey: String, file: File): S3Outcome = withContext(Dispatchers.IO) {
        val presigned = presignOrError(objectKey, OP_PUT)
            ?: return@withContext S3Outcome.Error("无法获取上传地址，请稍后再试")
        var conn: HttpURLConnection? = null
        try {
            conn = openPresigned(presigned.url, "PUT")
            conn.doOutput = true
            // 预签名只签 host 头，其余头由本端补齐（对象存储不校验未签名的请求头）。
            conn.setRequestProperty("Content-Type", CONTENT_TYPE)
            // 对象键由图片内容的哈希推导，同一键的内容永不改变，因此对象元数据可长期缓存。
            conn.setRequestProperty("Cache-Control", CACHE_CONTROL_IMMUTABLE)
            conn.setFixedLengthStreamingMode(file.length())
            file.inputStream().use { input ->
                conn.outputStream.use { output -> input.copyTo(output) }
            }
            when (val code = conn.responseCode) {
                in 200..299 -> S3Outcome.Success
                else -> S3Outcome.Error(errorText(conn, code), code)
            }
        } catch (e: Exception) {
            S3Outcome.Error(e.message ?: "上传失败，请检查网络后重试")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 下载对象到 [target]：换一条 GET 预签名后直取自建桶。
     * 先写 `.download` 临时文件、成功后再改名：中途断网若是直接写目标文件，
     * 会留下一个「看起来存在、其实残缺」的图片，被当成有效缓存长期用下去。
     */
    suspend fun downloadTo(objectKey: String, target: File): S3Outcome = withContext(Dispatchers.IO) {
        val presigned = presignOrError(objectKey, OP_GET)
            ?: return@withContext S3Outcome.Error("无法获取下载地址，请稍后再试")
        var conn: HttpURLConnection? = null
        var tmp: File? = null
        try {
            conn = openPresigned(presigned.url, "GET")
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

    /**
     * 验证「换预签名 + GET」这条读取链路是否真的走得通。
     *
     * 为什么必须单独验证：上传成功推不出读取可用——两者是不同的链路。
     * 删除本地副本前先确认这条链路通，否则一旦取不回来，就会出现
     * 「云端有、本地删了、图却看不见」的不可逆损失。
     * 用 GET 而不是 HEAD：读一个字节即断开，只验证可达性，不把整张图拉下来。
     */
    suspend fun verifyReadable(objectKey: String): Boolean = withContext(Dispatchers.IO) {
        val presigned = presignOrError(objectKey, OP_GET) ?: return@withContext false
        var conn: HttpURLConnection? = null
        try {
            conn = openPresigned(presigned.url, "GET")
            val ok = conn.responseCode in 200..299
            if (ok) runCatching { conn.inputStream.use { it.read() } }
            ok
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 换预签名 URL，任何失败（未登录 / 网络不通 / 服务端异常）都归一成 null。
     * 挂起取消原样上抛，调用方无需重复处理。
     */
    private suspend fun presignOrError(objectKey: String, op: String): PresignedBlobUrl? =
        try {
            presignedUrl(objectKey, op)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun openPresigned(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
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
        const val OP_GET = "get"
        const val OP_PUT = "put"

        /** 客户端请求的预签名有效期：5 分钟，够一次读写；Worker 侧钳制在 30 秒—10 分钟。 */
        const val PRESIGN_TTL_SECONDS = 300L

        /** 下载中的临时文件后缀，成功改名后才成为正式文件。 */
        const val DOWNLOAD_SUFFIX = ".download"

        /** 列表翻页上限，防服务端异常导致死循环。 */
        const val MAX_LIST_PAGES = 100

        /**
         * 上传时给对象打上的缓存策略：内容寻址（键=内容哈希）意味着一经写入内容永不改变，
         * 因此可被长期、不可变地缓存。
         */
        const val CACHE_CONTROL_IMMUTABLE = "public, max-age=31536000, immutable"
    }
}
