package com.goodyaoshi.lemonbox.data.sync

import android.content.Context
import android.net.Uri
import com.goodyaoshi.lemonbox.data.backup.AppBackupManager
import com.goodyaoshi.lemonbox.data.backup.BackupMergeResult
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FilterInputStream
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/** 同步握手信息：对方据此知道用哪个 peerId 记录水位线。 */
@Serializable
data class PeerInfo(val deviceId: String)

/**
 * 局域网同步的服务端：只监听内网端口，用 6 位配对码鉴权。
 *
 * 只提供三个端点，复用手动导入导出已有的能力：
 *  - `GET  /info?token=`   返回本机 deviceId，供对方作为水位线的键
 *  - `GET  /backup?token=&since=` 返回本机备份 ZIP（since 非空则为增量），
 *    响应头 `X-Exported-At` 告知对方本次导出的时间戳（对方存为拉取水位线）
 *  - `POST /merge?token=`  接收对方备份 ZIP 并合并，返回合并统计
 *
 * 水位线存在发起方（客户端）一侧，服务端无状态。若两台设备交换发起角色，
 * 新发起方本地没有水位线时会自动走一次全量，之后恢复增量。
 */
@Singleton
class SyncServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: AppBackupManager,
    private val appPreferences: AppPreferences
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 备份 / 合并都是磁盘密集型任务，统一在独立的 IO 调度器上执行（F12）。
     * 用 IO 池承载阻塞等待，而不是让 NanoHTTPD 的工作线程在自己的事件循环里空转。
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private var server: Impl? = null

    private val _incomingSummary = MutableStateFlow<BackupMergeResult?>(null)

    /** 作为“被连接方”时，对方数据合并进本机的结果，用于界面上提示。 */
    val incomingSummary: StateFlow<BackupMergeResult?> = _incomingSummary.asStateFlow()

    val isRunning: Boolean
        get() = server?.isAlive == true

    /** 当前监听端口；未启动时返回 0。 */
    val listeningPort: Int
        get() = server?.listeningPort ?: 0

    fun start(token: String): Boolean {
        if (isRunning) return true
        // 先用固定端口，方便对方手动输入 IP；被占用时退回系统分配的随机端口
        if (tryStart(Impl(DEFAULT_PORT, token))) return true
        return tryStart(Impl(0, token))
    }

    private fun tryStart(instance: Impl): Boolean {
        return runCatching {
            instance.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = instance
            true
        }.getOrElse {
            instance.stop()
            false
        }
    }

    fun stop() {
        server?.stop()
        server = null
        _incomingSummary.value = null
    }

    fun clearIncomingSummary() {
        _incomingSummary.value = null
    }

    /**
     * 把挂起式的备份 / 合并桥接到 NanoHTTPD 的同步响应模型（F12）。
     *
     * NanoHTTPD 的 `serve()` 必须同步返回 `Response`，「等待结果」这一步无法取消；
     * 但旧实现直接在 `serve()` 里 `runBlocking`，会带来三个问题：
     *  1) 阻塞发生在请求线程自身的事件循环上，请求一多就相互挤占，只能排队甚至超时；
     *  2) 完全没有超时，备份 / 合并一旦卡住，请求会一直悬挂，界面表现为「同步没反应」；
     *  3) 异常被笼统地包成 500，客户端拿不到可判断的原因。
     *
     * 现在把桥接收敛到这一个入口：固定跑在 IO 池上并统一加超时，
     * 超时由调用方翻译成明确的响应码（不再让请求无限期挂着）。
     */
    private fun <T> awaitSync(block: suspend () -> T): T =
        runBlocking(ioDispatcher) { withTimeout(SYNC_TIMEOUT_MS) { block() } }

    private inner class Impl(port: Int, private val expectedToken: String) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            // 收敛暴露面：服务端仅接受来自局域网（私网 / 链路本地 / 回环 / IPv6 ULA）的请求。
            // 明文 HTTP 放行范围无法用 network-security-config 按网段表达，
            // 因此在这里做运行时兜底：非局域网来源一律拒绝，避免被公网或热点外设备访问。
            if (!isLanAddress(session.remoteIpAddress)) {
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    MIME_PLAINTEXT,
                    "仅允许局域网设备访问"
                )
            }
            val token = session.parameters["token"]?.firstOrNull()
            if (token != expectedToken) {
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    MIME_PLAINTEXT,
                    "配对码不正确"
                )
            }
            return runCatching {
                when {
                    session.uri == "/info" && session.method == Method.GET -> serveInfo()
                    session.uri == "/backup" && session.method == Method.GET -> serveBackup(session)
                    session.uri == "/merge" && session.method == Method.POST -> serveMerge(session)
                    else -> newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        MIME_PLAINTEXT,
                        "not found"
                    )
                }
            }.getOrElse { error ->
                // 超时单独识别：客户端据此提示“对端响应超时，请重试”，
                // 而不是和普通异常一样笼统当成服务端 500（F12）。
                val timedOut = error is TimeoutCancellationException
                newFixedLengthResponse(
                    if (timedOut) Response.Status.REQUEST_TIMEOUT else Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    if (timedOut) "同步超时了，你再试一次" else error.message ?: "同步失败"
                )
            }
        }

        private fun serveInfo(): Response {
            val info = PeerInfo(deviceId = appPreferences.deviceId)
            return newFixedLengthResponse(
                Response.Status.OK,
                MIME_JSON,
                json.encodeToString(info)
            )
        }

        private fun serveBackup(session: IHTTPSession): Response {
            val since = session.parameters["since"]?.firstOrNull()?.toLongOrNull()
            // 走 awaitSync：在 IO 池上等待并带超时，避免请求线程被长时间占用（F12）
            val archive = awaitSync { backupManager.createBackupZip(since) }
            // 流式发出，读完即删除临时文件
            val stream = object : FilterInputStream(archive.file.inputStream()) {
                override fun close() {
                    super.close()
                    archive.file.delete()
                }
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                MIME_ZIP,
                stream,
                archive.file.length()
            ).apply {
                addHeader(HEADER_EXPORTED_AT, archive.exportedAt.toString())
            }
        }

        private fun serveMerge(session: IHTTPSession): Response {
            val contentLength = session.headers["content-length"]?.toLongOrNull()
                ?: return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "缺少 content-length"
                )
            val tempFile = File(context.cacheDir, "sync-in-${System.currentTimeMillis()}.zip")
            try {
                // 只按 content-length 读完请求体，绝不能关闭 session.inputStream：
                // 它底层是 socket 的输入流，关闭会连带关闭整个 socket，
                // 导致 NanoHTTPD 无法把响应写回，客户端报 "unexpected end of stream"。
                tempFile.outputStream().use { output ->
                    copyExactly(session.inputStream, output, contentLength)
                }
                val result = awaitSync {
                    backupManager.importBackup(Uri.fromFile(tempFile))
                }
                _incomingSummary.value = result
                appPreferences.setLastSyncAt(System.currentTimeMillis())
                return newFixedLengthResponse(
                    Response.Status.OK,
                    MIME_JSON,
                    json.encodeToString(result)
                )
            } finally {
                tempFile.delete()
            }
        }
    }

    companion object {
        /** 首选监听端口，便于对方直接输入 IP。 */
        const val DEFAULT_PORT = 8737

        /**
         * 单次同步请求的最长等待时间（F12）。
         * 备份 / 合并涉及大量磁盘读写与数据库事务，正常耗时在秒级；
         * 给到 30s 是为了容忍大库首次全量，同时避免请求无限期挂起。
         */
        private const val SYNC_TIMEOUT_MS = 30_000L

        /** `/backup` 响应头：本次备份的 exportedAt，对方据此更新拉取水位线。 */
        const val HEADER_EXPORTED_AT = "X-Exported-At"

        private const val MIME_PLAINTEXT = "text/plain"
        private const val MIME_JSON = "application/json"
        private const val MIME_ZIP = "application/zip"

        /**
         * 判断对端是否处于局域网：接受 IPv4 私网（10/8、172.16/12、192.168/16）、
         * 链路本地（169.254/16、fe80::/10）、回环，以及 IPv6 唯一本地地址（fc00::/7）。
         */
        private fun isLanAddress(remote: String?): Boolean {
            if (remote.isNullOrBlank()) return false
            return runCatching {
                val address = InetAddress.getByName(remote)
                address.isSiteLocalAddress ||
                    address.isLinkLocalAddress ||
                    address.isLoopbackAddress ||
                    isUniqueLocalIpv6(address)
            }.getOrDefault(false)
        }

        /** IPv6 唯一本地地址：最高 7 位为 1111110，即 fc00::/7。 */
        private fun isUniqueLocalIpv6(address: InetAddress): Boolean {
            val bytes = address.address
            return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
        }

        /** 只读取 content-length 指定的字节数，避免读到下一个请求。 */
        private fun copyExactly(
            input: java.io.InputStream,
            output: java.io.OutputStream,
            length: Long
        ) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }
}