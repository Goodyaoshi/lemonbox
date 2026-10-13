package com.goodyaoshi.lemonbox.data.ocr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.goodyaoshi.lemonbox.BuildConfig
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
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

/** 模型安装状态，供 UI 观察；状态始终描述当前所选档位。 */
sealed interface OcrModelState {
    /** 尚未安装（或已删除）。 */
    data object NotInstalled : OcrModelState

    /** 下载中。[downloadedBytes]/[totalBytes] 为全部分发包的累计进度。 */
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
 * - 每个分发包从官方百度 BOS 直链下载到临时文件，校验大小与 SHA-256 后解压出 ONNX，
 *   最后写 `installed.json`；下载源与校验值固定在 [OcrModelTier]，无需远程清单。
 * - 两档模型各自独立目录安装，切换档位不删对方文件；升级后遗留的旧版本目录自动清理。
 * - 下载作业由本类自持协程作用域（应用生命周期），离开屏幕不会中断大文件下载。
 */
@Singleton
class OcrModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var downloadJob: Job? = null

    /** 当前所选档位，透传自 [AppPreferences]（设备本地状态，不进备份）。 */
    val selectedTier: StateFlow<OcrModelTier> = preferences.ocrModelTier

    private val _state = MutableStateFlow(computeInitialState(preferences.ocrModelTier.value))
    val state: StateFlow<OcrModelState> = _state.asStateFlow()

    /** 切换识别模型档位：中断进行中的下载，状态回落到新档位的安装情况。 */
    fun selectTier(tier: OcrModelTier) {
        synchronized(lock) {
            if (tier == selectedTier.value && downloadJob?.isActive != true) return
            downloadJob?.cancel()
            downloadJob = null
            preferences.setOcrModelTier(tier)
            _state.value = if (isInstalled(tier)) OcrModelState.Installed else OcrModelState.NotInstalled
        }
    }

    /** 指定档位是否已安装且文件齐备。 */
    fun isInstalled(tier: OcrModelTier = selectedTier.value): Boolean {
        if (!OcrModelCatalog.markerFile(context, tier).exists()) return false
        val dir = OcrModelCatalog.modelDir(context, tier)
        return tier.downloads.all { File(dir, it.fileName).exists() }
    }

    /** 当前档位下指定模型文件的本地路径。 */
    fun filePath(name: String): File = filePath(name, selectedTier.value)

    /** 指定档位下模型文件的本地路径。 */
    fun filePath(name: String, tier: OcrModelTier): File =
        File(OcrModelCatalog.modelDir(context, tier), name)

    /** 当前是否处于按流量计费的移动数据网络（下载前应弹窗确认）。 */
    fun isMeteredConnection(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * 显式开始下载当前档位的模型（必须在用户确认后调用）。
     * 已在下载中或已安装时不重复执行。
     */
    fun startDownload() {
        synchronized(lock) {
            if (downloadJob?.isActive == true) return
            val tier = selectedTier.value
            if (isInstalled(tier)) {
                _state.value = OcrModelState.Installed
                return
            }
            downloadJob = scope.launch { runDownload(tier) }
        }
    }

    /** 取消正在进行的下载。状态回落到当前档位已安装或未安装。 */
    fun cancelDownload() {
        synchronized(lock) {
            downloadJob?.cancel()
            downloadJob = null
        }
        _state.value = if (isInstalled()) OcrModelState.Installed else OcrModelState.NotInstalled
    }

    /** 删除当前档位已安装的模型并清理临时文件，另一档位的安装不受影响。 */
    fun delete() {
        synchronized(lock) {
            downloadJob?.cancel()
            downloadJob = null
        }
        OcrModelCatalog.modelDir(context, selectedTier.value).deleteRecursively()
        _state.value = if (isInstalled()) OcrModelState.Installed else OcrModelState.NotInstalled
    }

    private suspend fun runDownload(tier: OcrModelTier) {
        val dir = OcrModelCatalog.modelDir(context, tier)
        try {
            dir.mkdirs()
            val fileCount = tier.downloads.size
            val totalBytes = tier.totalBytes

            var completedBytes = 0L
            tier.downloads.forEachIndexed { index, spec ->
                val base = completedBytes
                _state.value = OcrModelState.Downloading(
                    currentFile = spec.fileName,
                    fileIndex = index,
                    fileCount = fileCount,
                    downloadedBytes = base,
                    totalBytes = totalBytes
                )
                val tmp = File(dir, "${spec.fileName}.download")
                downloadTo(spec.url, tmp) { downloaded, _ ->
                    _state.value = OcrModelState.Downloading(
                        currentFile = spec.fileName,
                        fileIndex = index,
                        fileCount = fileCount,
                        downloadedBytes = base + downloaded,
                        totalBytes = totalBytes
                    )
                }
                verifyArchive(tmp, spec)
                val target = File(dir, spec.fileName)
                tmp.inputStream().use { input ->
                    TarExtractor.extract(input, spec.entry, target)
                }
                if (!tmp.delete()) tmp.deleteOnExit()
                completedBytes = base + spec.size
            }

            writeMarker(tier)
            cleanupStaleInstalls(tier)
            _state.value = OcrModelState.Installed
        } catch (e: CancellationException) {
            cleanTmp(dir)
            _state.value = if (isInstalled(tier)) OcrModelState.Installed else OcrModelState.NotInstalled
            throw e
        } catch (e: Exception) {
            cleanTmp(dir)
            _state.value = OcrModelState.Failed(e.message ?: "模型下载失败，请稍后重试")
        }
    }

    /** 下载分发包并断言完整：大小与 SHA-256 都与编译期固定值一致。 */
    private suspend fun downloadTo(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val conn = openConnection(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("下载失败（HTTP $code）")
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

    private fun verifyArchive(file: File, spec: OcrModelDownload) {
        if (file.length() != spec.size) {
            throw IOException("${spec.fileName} 大小校验失败，请重试")
        }
        val actual = sha256Hex(file)
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            throw IOException("${spec.fileName} 完整性校验失败，请重试")
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LemonBox/${BuildConfig.VERSION_NAME}")
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

    private fun writeMarker(tier: OcrModelTier) {
        val marker = OcrModelCatalog.markerFile(context, tier)
        val data = OcrInstalledMarker(
            version = tier.modelVersion,
            files = tier.downloads.map { OcrInstalledFile(it.fileName, it.sha256) }
        )
        marker.writeText(jsonCodec.encodeToString(OcrInstalledMarker.serializer(), data))
    }

    /**
     * 清理升级遗留：旧版本目录（如 v0.0.8 之前的 `ppocr-v6`）直接删除；
     * 另一档位若已完整安装则保留，只清掉它的半成品目录。
     */
    private fun cleanupStaleInstalls(current: OcrModelTier) {
        val root = OcrModelCatalog.modelDir(context, current).parentFile ?: return
        root.listFiles()
            ?.filter { it.isDirectory && it.name != current.modelVersion }
            ?.forEach { stale ->
                val otherTier = OcrModelTier.entries.firstOrNull { it.modelVersion == stale.name }
                if (otherTier != null && isInstalled(otherTier)) return@forEach
                stale.deleteRecursively()
            }
    }

    private fun computeInitialState(tier: OcrModelTier): OcrModelState =
        if (isInstalled(tier)) OcrModelState.Installed else OcrModelState.NotInstalled

    private fun cleanTmp(dir: File) {
        dir.listFiles()?.filter { it.name.endsWith(".download") }?.forEach { it.delete() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000

        val jsonCodec = Json { ignoreUnknownKeys = true }
    }
}

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
