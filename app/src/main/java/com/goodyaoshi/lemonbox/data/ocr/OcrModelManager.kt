package com.goodyaoshi.lemonbox.data.ocr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.goodyaoshi.lemonbox.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/** 模型安装状态，供 UI 观察。 */
sealed interface OcrModelState {
    /** 尚未安装（或已删除）。 */
    data object NotInstalled : OcrModelState

    /** 下载中。[downloadedBytes]/[totalBytes] 为全部文件的累计进度。 */
    data class Downloading(
        val currentFile: String,
        val fileIndex: Int,
        val fileCount: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : OcrModelState {
        val fraction: Float
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /** 已安装且可离线识别。 */
    data object Installed : OcrModelState

    /** 下载或校验失败。[message] 用于提示用户。 */
    data class Failed(val message: String) : OcrModelState
}

/**
 * 离线 OCR 模型管理（F01 · 方案 A：运行时下载）。
 *
 * 设计要点（严格遵守「用户有感知」原则）：
 * - **不提供任何后台/静默自动下载**：只暴露显式 [startDownload]，由 UI 在用户点击并确认后调用。
 * - 下载过程通过 [state] 暴露进度，UI 可展示；[cancelDownload] 随时可中断。
 * - 每个文件下载到 `*.tmp`，校验大小与 SHA-256 后才原子改名，最后写 `installed.json`。
 * - 模型存于 `noBackupFilesDir/ocr-models/{版本}`，不参与系统备份，[delete] 可清除。
 * - 下载作业由本类自持协程作用域（应用生命周期），离开屏幕不会中断大文件下载。
 */
@Singleton
class OcrModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow(computeInitialState())
    val state: StateFlow<OcrModelState> = _state.asStateFlow()

    /** 是否已在 `local.properties` 配置模型下载地址。 */
    val isConfigured: Boolean get() = OcrModelCatalog.isConfigured

    /** 模型是否已安装且文件齐备。 */
    fun isInstalled(): Boolean {
        if (!OcrModelCatalog.markerFile(context).exists()) return false
        val dir = OcrModelCatalog.modelDir(context)
        return OcrModelCatalog.requiredFiles.all { File(dir, it).exists() }
    }

    /** 已安装版本号；未安装返回 null。 */
    fun installedVersion(): String? {
        val marker = OcrModelCatalog.markerFile(context)
        if (!marker.exists()) return null
        return runCatching {
            jsonCodec.decodeFromString(OcrInstalledMarker.serializer(), marker.readText()).version
        }.getOrNull()
    }

    /** 指定模型文件的本地路径。 */
    fun filePath(name: String): File = File(OcrModelCatalog.modelDir(context), name)

    /** 模型安装目录。 */
    fun modelDir(): File = OcrModelCatalog.modelDir(context)

    /** 当前是否处于按流量计费的移动数据网络（下载前应弹窗确认）。 */
    fun isMeteredConnection(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * 显式开始下载模型（必须在用户确认后调用）。
     * 已在下载中或已安装时不重复执行。
     */
    fun startDownload() {
        synchronized(lock) {
            if (downloadJob?.isActive == true) return
            if (!isConfigured) {
                _state.value = OcrModelState.Failed("未配置模型下载地址")
                return
            }
            if (isInstalled()) {
                _state.value = OcrModelState.Installed
                return
            }
            downloadJob = scope.launch { runDownload() }
        }
    }

    /** 取消正在进行的下载。状态回落到已安装或未安装。 */
    fun cancelDownload() {
        synchronized(lock) {
            downloadJob?.cancel()
            downloadJob = null
        }
        _state.value = if (isInstalled()) OcrModelState.Installed else OcrModelState.NotInstalled
    }

    /** 删除已安装模型并清理临时文件。 */
    fun delete() {
        synchronized(lock) {
            downloadJob?.cancel()
            downloadJob = null
        }
        OcrModelCatalog.modelDir(context).deleteRecursively()
        _state.value = if (isInstalled()) OcrModelState.Installed else OcrModelState.NotInstalled
    }

    private suspend fun runDownload() {
        val dir = OcrModelCatalog.modelDir(context)
        try {
            val manifest = fetchManifest()
            val byName = manifest.files.associateBy { it.name }
            val missing = OcrModelCatalog.requiredFiles.filter { !byName.containsKey(it) }
            if (missing.isNotEmpty()) {
                _state.value = OcrModelState.Failed("模型清单缺少文件：${missing.joinToString()}")
                return
            }
            dir.mkdirs()
            val specs = OcrModelCatalog.requiredFiles.map { byName.getValue(it) }
            val totalBytes = specs.sumOf { it.size }
            val fileCount = specs.size

            var completedBytes = 0L
            specs.forEachIndexed { index, spec ->
                val base = completedBytes
                _state.value = OcrModelState.Downloading(
                    currentFile = spec.name,
                    fileIndex = index,
                    fileCount = fileCount,
                    downloadedBytes = base,
                    totalBytes = totalBytes
                )
                val target = File(dir, spec.name)
                val tmp = File(dir, "${spec.name}.tmp")
                downloadWithFallback(OcrModelCatalog.fileUrl(spec.name), tmp) { downloaded, _ ->
                    _state.value = OcrModelState.Downloading(
                        currentFile = spec.name,
                        fileIndex = index,
                        fileCount = fileCount,
                        downloadedBytes = base + downloaded,
                        totalBytes = totalBytes
                    )
                }
                verifyFile(tmp, spec)
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                completedBytes = base + spec.size
            }

            writeMarker(manifest)
            _state.value = OcrModelState.Installed
        } catch (e: CancellationException) {
            cleanTmp(dir)
            _state.value = if (isInstalled()) OcrModelState.Installed else OcrModelState.NotInstalled
            throw e
        } catch (e: Exception) {
            cleanTmp(dir)
            _state.value = OcrModelState.Failed(e.message ?: "模型下载失败，请稍后重试")
        }
    }

    private suspend fun fetchManifest(): OcrManifest = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        for (url in downloadUrls(OcrModelCatalog.manifestUrl)) {
            try {
                val conn = openConnection(url)
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    return@withContext jsonCodec.decodeFromString(OcrManifest.serializer(), text)
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("模型清单下载失败")
    }

    /** 依次尝试的下载地址：gh-proxy 加速优先，直连兜底（与 AppUpdater 同一策略）。 */
    private fun downloadUrls(url: String): List<String> = listOf(PROXY_PREFIX + url, url)

    /** 逐个候选地址尝试下载，全部失败时抛出最后一个异常。 */
    private suspend fun downloadWithFallback(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        var lastError: Exception? = null
        for (candidate in downloadUrls(url)) {
            try {
                downloadTo(candidate, target, onProgress)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("模型下载失败")
    }

    private suspend fun downloadTo(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val conn = openConnection(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, conn.contentLengthLong)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LemonBox/${BuildConfig.VERSION_NAME}")
        }

    private fun verifyFile(file: File, spec: OcrManifestFile) {
        if (spec.size > 0 && file.length() != spec.size) {
            throw IOException("文件 ${spec.name} 大小校验失败")
        }
        if (spec.sha256.isNotBlank()) {
            val actual = sha256Hex(file)
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                throw IOException("文件 ${spec.name} 校验失败，请重试")
            }
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeMarker(manifest: OcrManifest) {
        val marker = OcrModelCatalog.markerFile(context)
        val data = OcrInstalledMarker(
            version = manifest.version.ifBlank { OcrModelCatalog.MODEL_VERSION },
            files = manifest.files.map { OcrInstalledFile(it.name, it.sha256) }
        )
        marker.writeText(jsonCodec.encodeToString(OcrInstalledMarker.serializer(), data))
    }

    private fun computeInitialState(): OcrModelState =
        if (isInstalled()) OcrModelState.Installed else OcrModelState.NotInstalled

    private fun cleanTmp(dir: File) {
        dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000

        /** 与 AppUpdater 一致的 GitHub 加速前缀；模型托管在 GitHub Release。 */
        const val PROXY_PREFIX = "https://gh-proxy.com/"

        val jsonCodec = Json { ignoreUnknownKeys = true }
    }
}

/** 服务端 `manifest.json` 结构。 */
@Serializable
data class OcrManifest(
    val version: String = "",
    val files: List<OcrManifestFile> = emptyList()
)

@Serializable
data class OcrManifestFile(
    val name: String = "",
    val size: Long = 0L,
    val sha256: String = ""
)

/** 安装完成标记。 */
@Serializable
data class OcrInstalledMarker(
    val version: String = "",
    val files: List<OcrInstalledFile> = emptyList()
)

@Serializable
data class OcrInstalledFile(
    val name: String = "",
    val sha256: String = ""
)
