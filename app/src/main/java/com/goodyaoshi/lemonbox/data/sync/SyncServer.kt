package com.goodyaoshi.lemonbox.data.sync

import android.content.Context
import android.net.Uri
import com.goodyaoshi.lemonbox.data.backup.AppBackupManager
import com.goodyaoshi.lemonbox.data.backup.BackupChanges
import com.goodyaoshi.lemonbox.data.backup.BackupMergeResult
import com.goodyaoshi.lemonbox.data.backup.SyncChangeKind
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FilterInputStream
import java.net.InetAddress
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 客户端发起配对时提交的本机信息。 */
@Serializable
data class PairRequestBody(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String
)

/** 配对响应：会话号（后续请求的凭证）、双方要对照的匹配码、以及本机标识。 */
@Serializable
data class PairResponse(
    val sessionId: String,
    val matchCode: String,
    val hostDeviceId: String,
    val hostName: String,
    val hostModel: String
)

/** 配对会话状态查询结果；[hostMerge] 仅在服务端已应用对方数据后给出。 */
@Serializable
data class PairStatus(
    val state: String,
    val hostMerge: BackupMergeResult? = null
)

/**
 * `/ping` 的应答：只暴露「我是谁」，用于子网扫描时辨认对端。
 * 不含配对码等鉴权信息，因此对局域网匿名开放也不会降低安全性。
 */
@Serializable
data class PingResponse(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String
)

/** 供界面展示的一次待处理配对会话（含对照用的匹配码）。 */
data class SyncPairRequest(
    val sessionId: String,
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val matchCode: String
)

/**
 * 局域网同步的服务端：只监听内网端口。
 *
 * 采用「像蓝牙一样」的配对流程，不再一收到请求就直接合并：
 *  1. `POST /pair` 客户端发起，服务端生成会话并返回匹配码；本机界面弹出「是否接受」；
 *  2. 两端分别确认匹配码一致（本机点接受、对方点确认），双方都确认后才进入数据交换；
 *  3. `POST /stage` 客户端上传本机 ZIP，服务端只暂存并算出预览（不改库）；
 *  4. `GET  /backup` 客户端拉取本机 ZIP，用于在自己一侧预览；
 *  5. 两端各自在变更列表里逐条勾选要落地的内容，确认后分别落地；
 *  6. `POST /pair/close` 收尾清理。
 *
 * 只有 `GET /backup`（数据出口）与配对相关端点需要会话语义，会话号是一次性随机 UUID，
 * 起到能力凭证的作用；`/pair` 本身对局域网开放，这是蓝牙式「可被发现」的前提，
 * 真正的安全边界是两端的匹配码人工对照 + 本机显式接受。
 *
 * 另有 `GET /ping`：不带任何鉴权，仅返回本机设备号/型号，供对方在「子网扫描」时
 * 辨认「这个 IP 上到底是不是一台柠檬百宝箱」，是 mDNS 失效（AP 隔离 / 热点）时的兜底发现手段。
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

    /** 服务端侧异步落地（应用对方暂存数据）用的作用域。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var server: Impl? = null

    /** 本次开启共享时的匹配码：客户端 `/pair` 时回给它，供两端人工对照。 */
    private var matchCode: String = ""

    /** 当前待处理的配对会话；同一时刻只保留一个。 */
    @Volatile
    private var session: PairSession? = null

    private val _pairRequest = MutableStateFlow<SyncPairRequest?>(null)

    /** 待处理的配对请求；本机界面据此弹出「是否接受」。 */
    val pairRequest: StateFlow<SyncPairRequest?> = _pairRequest.asStateFlow()

    private val _hostAccepted = MutableStateFlow(false)

    /** 本机是否已确认匹配（点了「接受」）。 */
    val hostAccepted: StateFlow<Boolean> = _hostAccepted.asStateFlow()

    private val _stagedPreview = MutableStateFlow<BackupChanges?>(null)

    /** 对方数据合并到本机的逐条预览；本机界面据此展示变更列表并让用户勾选。 */
    val stagedPreview: StateFlow<BackupChanges?> = _stagedPreview.asStateFlow()

    private val _hostMergeResult = MutableStateFlow<BackupMergeResult?>(null)

    /** 本机落地对方数据后的统计结果。 */
    val hostMergeResult: StateFlow<BackupMergeResult?> = _hostMergeResult.asStateFlow()

    private val _hostError = MutableStateFlow<String?>(null)

    /** 本机落地对方数据时的错误信息。 */
    val hostError: StateFlow<String?> = _hostError.asStateFlow()

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
        matchCode = token
        // 先用固定端口，方便对方手动输入 IP；被占用时退回系统分配的随机端口
        if (tryStart(Impl(DEFAULT_PORT))) return true
        return tryStart(Impl(0))
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
        clearSession()
    }

    fun clearIncomingSummary() {
        _incomingSummary.value = null
    }

    /** 本机界面：接受这次配对。 */
    fun acceptPairing() {
        session?.hostAccepted = true
        _hostAccepted.value = true
    }

    /** 本机界面：拒绝这次配对。 */
    fun rejectPairing() {
        session?.let { active ->
            // 已经收到对方数据后再取消的，留一条「已取消」回执；纯配对拒绝不算一次同步（S5）。
            if (active.stagedFile != null) {
                appPreferences.recordSyncReport(
                    SyncReport(
                        time = System.currentTimeMillis(),
                        role = SyncReport.Role.HOST,
                        outcome = SyncReport.Outcome.CANCELLED,
                        peerLabel = active.request.deviceName
                    )
                )
            }
        }
        session?.rejected = true
        clearSession()
    }

    /**
     * 本机界面：按用户勾选的白名单把对方暂存的数据合并进本机。
     * 落地完成后 [hostMergeResult] 会给出统计（含跳过条数），客户端轮询状态即可拿到。
     */
    fun applyIncoming(keepKeys: Set<String>) {
        val active = session ?: return
        val file = active.stagedFile ?: run {
            _hostError.value = "还没收到对方数据，先等一下"
            return
        }
        val preview = _stagedPreview.value
        val acceptedChanges = preview?.changes?.filter { it.key in keepKeys } ?: emptyList()
        val peerName = active.request.deviceName
        appScope.launch {
            runCatching {
                withTimeout(SYNC_TIMEOUT_MS) {
                    // P11：LAN 收到的改动服务端还没见过，标脏让下一轮远程同步把它们推上去。
                    backupManager.importBackup(Uri.fromFile(file), keepKeys = keepKeys, markDirty = true)
                }
            }.onSuccess { result ->
                // 把「本机跳过几条」带给发起方：它据此决定推送水位线是否前进。
                val skipped = preview?.changes?.size
                    ?.minus(keepKeys.size)?.coerceAtLeast(0) ?: 0
                val outcome = result.copy(userSkipped = skipped)
                active.hostMerge = outcome
                active.applied = true
                _hostMergeResult.value = outcome
                _incomingSummary.value = outcome
                appPreferences.setLastSyncAt(System.currentTimeMillis())
                appPreferences.recordSyncReport(
                    SyncReport(
                        time = System.currentTimeMillis(),
                        role = SyncReport.Role.HOST,
                        outcome = SyncReport.Outcome.SUCCESS,
                        added = result.totalAdded,
                        updated = result.totalUpdated,
                        deleted = acceptedChanges.count { it.kind == SyncChangeKind.DELETED },
                        conflicts = acceptedChanges.count { it.conflict },
                        skipped = skipped,
                        peerLabel = peerName,
                        // GAP-1：留下逐条回执（勾选了哪些、跳过了哪些），历史里可点开回放。
                        entries = preview?.changes.orEmpty().map { change ->
                            SyncReportEntry(
                                entity = change.entity,
                                kind = change.kind,
                                title = change.title,
                                conflict = change.conflict,
                                adopted = change.key in keepKeys
                            )
                        }
                    )
                )
            }.onFailure { error ->
                _hostError.value = error.message ?: "合并对方数据失败了"
                appPreferences.recordSyncReport(
                    SyncReport(
                        time = System.currentTimeMillis(),
                        role = SyncReport.Role.HOST,
                        outcome = SyncReport.Outcome.FAILED,
                        peerLabel = peerName
                    )
                )
            }
        }
    }

    fun clearHostError() {
        _hostError.value = null
    }

    private fun clearSession() {
        session?.stagedFile?.let { runCatching { it.delete() } }
        session = null
        _pairRequest.value = null
        _hostAccepted.value = false
        _stagedPreview.value = null
        _hostMergeResult.value = null
        _hostError.value = null
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

    private inner class Impl(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            // 收敛暴露面：服务端仅接受来自局域网（私网 / 链路本地 / 回环 / IPv6 ULA）的请求。
            if (!isLanAddress(session.remoteIpAddress)) {
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    MIME_PLAINTEXT,
                    "仅允许局域网设备访问"
                )
            }
            return runCatching {
                when {
                    session.uri == "/ping" && session.method == Method.GET -> servePing()
                    session.uri == "/pair" && session.method == Method.POST -> servePair(session)
                    session.uri == "/pair/confirm" && session.method == Method.POST -> serveConfirm(session)
                    session.uri == "/pair/status" && session.method == Method.GET -> serveStatus(session)
                    session.uri == "/pair/close" && session.method == Method.POST -> serveClose(session)
                    session.uri == "/stage" && session.method == Method.POST -> serveStage(session)
                    session.uri == "/backup" && session.method == Method.GET -> serveBackup(session)
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

        /** 轻量应答：不需要会话语义，供局域网子网扫描时辨认对端。 */
        private fun servePing(): Response {
            val model = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "本机"
            val response = PingResponse(
                deviceId = appPreferences.deviceId,
                deviceName = model,
                deviceModel = model
            )
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.encodeToString(response))
        }

        private fun servePair(httpSession: IHTTPSession): Response {
            val body = readBodyText(httpSession) ?: return badRequest("缺少请求内容")
            val request = runCatching { json.decodeFromString<PairRequestBody>(body) }
                .getOrElse { return badRequest("配对请求格式不对") }
            // 同一时刻只保留一个会话：新请求顶掉旧的未完成会话。
            session?.stagedFile?.let { runCatching { it.delete() } }
            val id = UUID.randomUUID().toString()
            val pairRequest = SyncPairRequest(
                sessionId = id,
                deviceId = request.deviceId,
                deviceName = request.deviceName.ifBlank { "对方的设备" },
                deviceModel = request.deviceModel.ifBlank { request.deviceName },
                matchCode = matchCode
            )
            session = PairSession(id, pairRequest)
            _pairRequest.value = pairRequest
            _hostAccepted.value = false
            _stagedPreview.value = null
            _hostMergeResult.value = null
            _hostError.value = null
            val response = PairResponse(
                sessionId = id,
                matchCode = matchCode,
                hostDeviceId = appPreferences.deviceId,
                hostName = android.os.Build.MODEL ?: "本机",
                hostModel = android.os.Build.MODEL ?: "本机"
            )
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.encodeToString(response))
        }

        private fun serveConfirm(httpSession: IHTTPSession): Response {
            val active = requireSession(httpSession) ?: return sessionNotFound()
            active.clientConfirmed = true
            return statusResponse(active)
        }

        private fun serveStatus(httpSession: IHTTPSession): Response {
            val active = requireSession(httpSession) ?: return sessionNotFound()
            return statusResponse(active)
        }

        private fun serveClose(httpSession: IHTTPSession): Response {
            val id = httpSession.parameters["sessionId"]?.firstOrNull()
            if (session?.id == id) clearSession()
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
        }

        private fun serveStage(httpSession: IHTTPSession): Response {
            val active = requireSession(httpSession) ?: return sessionNotFound()
            val contentLength = httpSession.headers["content-length"]?.toLongOrNull()
                ?: return badRequest("缺少 content-length")
            val tempFile = File(context.cacheDir, "sync-stage-${active.id}.zip")
            try {
                // 只按 content-length 读完请求体，绝不能关闭 session.inputStream：
                // 它底层是 socket 的输入流，关闭会连带关闭整个 socket。
                tempFile.outputStream().use { output ->
                    copyExactly(httpSession.inputStream, output, contentLength)
                }
                active.stagedFile?.delete()
                active.stagedFile = tempFile
                val preview = awaitSync {
                    backupManager.previewBackup(
                        sourceUri = Uri.fromFile(tempFile),
                        // 以本端上次同步完成时间为基线：双方都改过的条目标记为真冲突（S4）。
                        syncedAfter = appPreferences.lastSyncAt.value
                    )
                }
                _stagedPreview.value = preview
                return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.encodeToString(preview))
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        }

        private fun serveBackup(httpSession: IHTTPSession): Response {
            requireSession(httpSession) ?: return sessionNotFound()
            val since = httpSession.parameters["since"]?.firstOrNull()?.toLongOrNull()
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

        private fun requireSession(httpSession: IHTTPSession): PairSession? {
            val id = httpSession.parameters["sessionId"]?.firstOrNull() ?: return null
            val active = session ?: return null
            return active.takeIf { it.id == id && !it.rejected }
        }

        private fun statusResponse(active: PairSession): Response {
            val status = PairStatus(
                state = active.stateName(),
                hostMerge = if (active.applied) active.hostMerge else null
            )
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.encodeToString(status))
        }

        private fun sessionNotFound(): Response =
            newFixedLengthResponse(Response.Status.CONFLICT, MIME_PLAINTEXT, "配对会话已结束")

        private fun badRequest(message: String): Response =
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, message)

        /** 读取请求体文本（按 content-length，避免读到下一个请求）。 */
        private fun readBodyText(httpSession: IHTTPSession): String? {
            val contentLength = httpSession.headers["content-length"]?.toLongOrNull()
                ?: return null
            val buffer = java.io.ByteArrayOutputStream()
            copyExactly(httpSession.inputStream, buffer, contentLength)
            return buffer.toString(Charsets.UTF_8.name())
        }
    }

    /** 一次配对会话的运行时状态。 */
    private class PairSession(
        val id: String,
        val request: SyncPairRequest
    ) {
        @Volatile var hostAccepted = false
        @Volatile var clientConfirmed = false
        @Volatile var rejected = false
        @Volatile var stagedFile: File? = null
        @Volatile var applied = false
        @Volatile var hostMerge: BackupMergeResult? = null

        fun stateName(): String = when {
            rejected -> "REJECTED"
            applied -> "APPLIED"
            !hostAccepted -> "WAITING_HOST"
            !clientConfirmed -> "WAITING_CLIENT"
            else -> "READY"
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
