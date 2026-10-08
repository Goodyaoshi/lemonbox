package com.goodyaoshi.lemonbox.data.sync

import android.content.Context
import android.net.Uri
import com.goodyaoshi.lemonbox.data.backup.AppBackupManager
import com.goodyaoshi.lemonbox.data.backup.BackupMergeResult
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** 一次局域网同步的双方结果：本地合并了对方多少，对方合并了本机多少。 */
data class SyncOutcome(
    val localMerge: BackupMergeResult,
    val peerMerge: BackupMergeResult
)

/**
 * 局域网同步的发起方：把自己的变更推给对方合并，再拉取对方的变更合并到本地。
 *
 * 增量策略：以「同伴」为粒度记录两条水位线（存在本机 SharedPreferences）：
 *  - 推送水位线：上次成功推送给对方时的本机时间，只推晚于它的记录；
 *  - 拉取水位线：上次导入对方数据时对方的 exportedAt，只拉晚于它的记录。
 *
 * 首次同步（无水位线）或水位线过旧时自动退回全量，保证不会漏传。
 */
@Singleton
class SyncClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: AppBackupManager,
    private val appPreferences: AppPreferences
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun syncWith(host: String, port: Int, token: String): SyncOutcome =
        withContext(Dispatchers.IO) {
            val base = "http://$host:$port"

            // 1. 握手：拿到对方 deviceId，作为水位线的键。
            val peerId = step("连接对方设备") {
                fetchPeerInfo("$base/info?token=$token").deviceId
            }

            // 2. 推送本机变更。pushBaseTime 必须在生成 ZIP 之前取，否则会漏掉
            //    「读取快照期间被修改」的记录。
            val pushSince = resolveSince(appPreferences.pushWatermark(peerId))
            val pushBaseTime = System.currentTimeMillis()
            val peerMerge = step("推送本机数据") {
                val archive = backupManager.createBackupZip(pushSince)
                try {
                    postBackup("$base/merge?token=$token", archive.file)
                } finally {
                    archive.file.delete()
                }
            }
            appPreferences.setPushWatermark(peerId, pushBaseTime)

            // 3. 拉取对方变更。
            val pullSince = resolveSince(appPreferences.pullWatermark(peerId))
            val backupUrl = buildString {
                append("$base/backup?token=$token")
                if (pullSince != null) append("&since=$pullSince")
            }
            val incoming = File(context.cacheDir, "sync-out-${System.currentTimeMillis()}.zip")
            val localMerge = step("拉取对方数据") {
                try {
                    val exportedAt = downloadBackup(backupUrl, incoming)
                    val result = backupManager.importBackup(Uri.fromFile(incoming))
                    if (exportedAt != null) {
                        appPreferences.setPullWatermark(peerId, exportedAt)
                    }
                    result
                } finally {
                    incoming.delete()
                }
            }

            SyncOutcome(localMerge = localMerge, peerMerge = peerMerge).also {
                appPreferences.setLastSyncAt(System.currentTimeMillis())
            }
        }

    /** 包住单个步骤，把底层异常翻译成带环节说明的错误，便于定位失败位置。 */
    private inline fun <T> step(label: String, block: () -> T): T =
        try {
            block()
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

    private fun fetchPeerInfo(url: String): PeerInfo {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                error("无法识别对方设备（错误码 $code）")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return json.decodeFromString<PeerInfo>(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun postBackup(url: String, zip: File): BackupMergeResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/zip")
            setFixedLengthStreamingMode(zip.length())
        }
        try {
            connection.outputStream.use { output ->
                zip.inputStream().use { input -> input.copyTo(output) }
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                error("对方拒绝了同步请求（错误码 $code）")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return json.decodeFromString<BackupMergeResult>(body)
        } finally {
            connection.disconnect()
        }
    }

    /** 拉取备份并写入 [target]，返回响应头里的 exportedAt；缺失时返回 null（不更新水位线）。 */
    private fun downloadBackup(url: String, target: File): Long? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                error("无法获取对方数据（错误码 $code）")
            }
            val exportedAt = connection
                .getHeaderField(SyncServer.HEADER_EXPORTED_AT)
                ?.toLongOrNull()
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return exportedAt
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 120_000

        /** 水位线换算时回退的时间，容忍两台设备之间的时钟偏差。 */
        private const val SKEW_BUFFER_MS = 2 * 60 * 1000L

        /** 水位线超过该时长未更新则退回全量，避免长期离线后时钟漂移导致漏传。 */
        private const val MAX_WATERMARK_AGE_MS = 30 * AppPreferences.DAY_MS
    }
}