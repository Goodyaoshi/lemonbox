package com.goodyaoshi.lemonbox.data.sync

import android.content.Context
import android.net.Uri
import com.goodyaoshi.lemonbox.data.backup.AppBackupManager
import com.goodyaoshi.lemonbox.data.backup.BackupMergeResult
import com.goodyaoshi.lemonbox.data.backup.BackupPreview
import com.goodyaoshi.lemonbox.data.backup.SyncScope
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** 用户在同步途中的主动取消（拒绝匹配码 / 关闭范围选择），用于和网络错误区分。 */
class SyncCancelledException(message: String) : Exception(message)

/** 一次局域网同步的双方结果：本地合并了对方多少，对方合并了本机多少。 */
data class SyncOutcome(
    val localMerge: BackupMergeResult,
    /** 对方落地本机数据的统计；对方迟迟没落地或拒绝时为 null。 */
    val peerMerge: BackupMergeResult?
)

/** 同步分步，供界面实时展示进度；顺序即执行顺序。 */
enum class SyncStage(val label: String) {
    CONNECTING("连接对方设备"),
    CONFIRMING("双方核对匹配码"),
    EXCHANGING("交换数据并预览"),
    APPLYING("应用同步")
}

/**
 * 局域网同步的发起方：按「像配蓝牙一样」的流程与对方协商，双方都确认后才交换数据。
 *
 * 完整流程：
 *  1. `POST /pair` 把本机信息发给对方，拿回会话号与**匹配码**（对方屏幕上也能看到同一个码）；
 *  2. 界面弹出匹配码让用户人工对照，确认一致后 `POST /pair/confirm`；
 *  3. 轮询 `GET /pair/status` 直到对方也点了「接受」（READY），期间对方拒绝则终止；
 *  4. 把本机变更 `POST /stage` 上传给对方暂存，`GET /backup` 拉取对方 ZIP 到本地做预览；
 *  5. 界面展示「新增 / 修改 / 删除」并让用户选范围（全部 / 只新增 / 不删除），据此导入；
 *  6. 等待对方也落地，取回它的统计结果，最后 `POST /pair/close` 收尾。
 *
 * 增量策略沿用「同伴水位线」：以对方 deviceId 为键记录 push/pull 时间戳，
 * 首次同步或水位线过旧时退回全量，保证不会漏传。
 */
@Singleton
class SyncClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: AppBackupManager,
    private val appPreferences: AppPreferences
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 与对方设备做一次「双端确认 + 可预览 + 可选范围」的同步。
     *
     * [awaitMatchCode] 会挂起等待用户核对匹配码，返回 true 表示一致、继续；
     * [awaitScope] 会挂起等待用户选择范围，返回 null 表示取消。
     */
    suspend fun syncInteractive(
        host: String,
        port: Int,
        onProgress: (SyncStage) -> Unit = {},
        onTransfer: (done: Long, total: Long, label: String) -> Unit = { _, _, _ -> },
        awaitMatchCode: suspend (matchCode: String, peerLabel: String) -> Boolean,
        awaitScope: suspend (preview: BackupPreview, peerLabel: String) -> SyncScope?
    ): SyncOutcome = withContext(Dispatchers.IO) {
        val base = "http://$host:$port"
        var sessionId: String? = null
        try {
            // 1. 握手配对：拿会话号 / 匹配码 / 对方标识。
            onProgress(SyncStage.CONNECTING)
            val pair: PairResponse = step("连接对方设备") {
                val body = json.encodeToString(
                    PairRequestBody(
                        deviceId = appPreferences.deviceId,
                        deviceName = android.os.Build.MODEL ?: "本机",
                        deviceModel = android.os.Build.MODEL ?: "本机"
                    )
                )
                json.decodeFromString(postJson("$base/pair", body))
            }
            sessionId = pair.sessionId
            val peerLabel = pair.hostName.ifBlank { "对方的设备" }

            // 2. 双方核对匹配码：本机确认对方屏幕上显示的码和这里一致。
            onProgress(SyncStage.CONFIRMING)
            val accepted = step("核对匹配码") { awaitMatchCode(pair.matchCode, peerLabel) }
            if (!accepted) throw SyncCancelledException("你取消了这次同步")
            step("确认匹配码") { postJson("$base/pair/confirm?sessionId=${pair.sessionId}", "") }
            if (!waitForReady(base, pair.sessionId)) {
                throw java.io.IOException("对方没有同意这次同步")
            }

            // 3. 交换数据与预览。
            onProgress(SyncStage.EXCHANGING)
            val pushSince = resolveSince(appPreferences.pushWatermark(pair.hostDeviceId))
            val pushBaseTime = System.currentTimeMillis()
            step("推送本机数据") {
                val archive = backupManager.createBackupZip(pushSince)
                try {
                    postFile("$base/stage?sessionId=${pair.sessionId}", archive.file, "上传本机数据", onTransfer)
                } finally {
                    archive.file.delete()
                }
            }
            appPreferences.setPushWatermark(pair.hostDeviceId, pushBaseTime)

            val pullSince = resolveSince(appPreferences.pullWatermark(pair.hostDeviceId))
            val incoming = File(context.cacheDir, "sync-in-${System.currentTimeMillis()}.zip")
            try {
                val exportedAt = step("拉取对方数据") {
                    val url = buildString {
                        append("$base/backup?sessionId=${pair.sessionId}")
                        if (pullSince != null) append("&since=$pullSince")
                    }
                    downloadBackup(url, incoming, "下载对方数据", onTransfer)
                }
                val preview = step("分析对方数据") {
                    backupManager.previewBackup(Uri.fromFile(incoming))
                }
                // 有变化才让用户选范围；没有变化直接按「全部」处理（等同无操作）。
                val scope = if (preview.hasChanges) {
                    step("选择同步范围") { awaitScope(preview, peerLabel) }
                } else {
                    SyncScope.ALL
                }
                if (scope == null) throw SyncCancelledException("你取消了这次同步")

                onProgress(SyncStage.APPLYING)
                val localMerge = step("应用对方数据") {
                    backupManager.importBackup(Uri.fromFile(incoming), scope)
                }
                if (exportedAt != null) {
                    appPreferences.setPullWatermark(pair.hostDeviceId, exportedAt)
                }
                // 等对方也把本机数据落地，好把它的统计一并展示。
                val peerMerge = waitForHostMerge(base, pair.sessionId)

                SyncOutcome(localMerge = localMerge, peerMerge = peerMerge).also {
                    appPreferences.setLastSyncAt(System.currentTimeMillis())
                }
            } finally {
                incoming.delete()
            }
        } finally {
            sessionId?.let { sid ->
                runCatching { postJson("$base/pair/close?sessionId=$sid", "") }
            }
        }
    }

    /** 轮询会话状态，直到双方都确认（READY）或被拒绝；超时返回 false。 */
    private suspend fun waitForReady(base: String, sessionId: String): Boolean {
        val deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val status = runCatching {
                json.decodeFromString<PairStatus>(getJson("$base/pair/status?sessionId=$sessionId"))
            }.getOrNull()
            when (status?.state) {
                "READY", "APPLIED" -> return true
                "REJECTED" -> return false
                else -> delay(POLL_INTERVAL_MS)
            }
        }
        return false
    }

    /** 等对方落地本机数据后取回统计；超时或对方未落地时返回 null。 */
    private suspend fun waitForHostMerge(base: String, sessionId: String): BackupMergeResult? {
        val deadline = System.currentTimeMillis() + HOST_MERGE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val status = runCatching {
                json.decodeFromString<PairStatus>(getJson("$base/pair/status?sessionId=$sessionId"))
            }.getOrNull()
            status?.hostMerge?.let { return it }
            if (status?.state == "REJECTED") return null
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    /** 包住单个步骤，把底层异常翻译成带环节说明的错误，便于定位失败位置。 */
    private suspend inline fun <T> step(label: String, block: () -> T): T =
        try {
            block()
        } catch (e: SyncCancelledException) {
            throw e
        } catch (e: Exception) {
            throw java.io.IOException("${label}失败：${e.message ?: e.javaClass.simpleName}", e)
        }

    /**
     * 把存储的水位线换算成过滤用的 since：
     * 无水位线或水位线过旧（可能因设备时钟大幅漂移）→ null，退回全量；
     * 否则回退一个时间缓冲，容忍对方时钟略快造成的漏传。
     */
    private fun resolveSince(watermark: Long?): Long? {
        if (watermark == null) return null
        if (System.currentTimeMillis() - watermark > MAX_WATERMARK_AGE_MS) return null
        return (watermark - SKEW_BUFFER_MS).coerceAtLeast(0L)
    }

    private fun postJson(url: String, body: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            return readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            return readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun postFile(
        url: String,
        file: File,
        label: String,
        onTransfer: (done: Long, total: Long, label: String) -> Unit
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/zip")
            setFixedLengthStreamingMode(file.length())
        }
        try {
            connection.outputStream.use { output ->
                file.inputStream().use { input ->
                    copyWithProgress(input, output, file.length(), label, onTransfer)
                }
            }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    /** 拉取备份并写入 [target]，返回响应头里的 exportedAt；缺失时返回 null（不更新水位线）。 */
    private fun downloadBackup(
        url: String,
        target: File,
        label: String,
        onTransfer: (done: Long, total: Long, label: String) -> Unit
    ): Long? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
                error(message?.takeIf { it.isNotBlank() } ?: "无法获取对方数据（错误码 $code）")
            }
            val exportedAt = connection
                .getHeaderField(SyncServer.HEADER_EXPORTED_AT)
                ?.toLongOrNull()
            // 对方可能带 gzip 等压缩，contentLength 是压缩后的字节数；拿不到就传 -1（界面改用「已传」）。
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    copyWithProgress(input, output, total, label, onTransfer)
                }
            }
            return exportedAt
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 分块拷贝并上报进度：每累计 [TRANSFER_REPORT_STEP] 上报一次，避免高频刷新界面。
     * [total] 为 -1 时表示总量未知，回调里同样回传 -1。
     */
    private fun copyWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long,
        label: String,
        onTransfer: (done: Long, total: Long, label: String) -> Unit
    ) {
        val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
        var done = 0L
        var lastReported = 0L
        onTransfer(0L, total, label)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            done += read
            if (done - lastReported >= TRANSFER_REPORT_STEP) {
                lastReported = done
                onTransfer(done, total, label)
            }
        }
        // 收尾再报一次，确保进度能停在 100%（或准确的已传字节数）。
        onTransfer(done, total, label)
    }

    /** 读响应体；非 2xx 抛出带服务端说明的异常。 */
    private fun readResponse(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            error(text.ifBlank { "对方返回错误（$code）" })
        }
        return text
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 120_000

        /** 等待用户核对匹配码 / 对方接受的最长时间。 */
        private const val CONFIRM_TIMEOUT_MS = 120_000L

        /** 等对方落地本机数据的最长时间。 */
        private const val HOST_MERGE_TIMEOUT_MS = 60_000L

        /** 状态轮询间隔。 */
        private const val POLL_INTERVAL_MS = 1_500L

        /** 水位线换算时回退的时间，容忍两台设备之间的时钟偏差。 */
        private const val SKEW_BUFFER_MS = 2 * 60 * 1000L

        /** 水位线超过该时长未更新则退回全量，避免长期离线后时钟漂移导致漏传。 */
        private const val MAX_WATERMARK_AGE_MS = 30 * AppPreferences.DAY_MS

        /** 传输用的分块缓冲大小。 */
        private const val TRANSFER_BUFFER_SIZE = 64 * 1024

        /** 每累计传输这么多字节上报一次进度，避免过于频繁地刷新界面。 */
        private const val TRANSFER_REPORT_STEP = 256 * 1024L
    }
}
