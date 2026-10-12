package com.goodyaoshi.lemonbox.data.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.goodyaoshi.lemonbox.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** 新版本信息：版本号、更新说明、APK 下载地址与体积。 */
data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long
)

/** 检查更新结果：已是最新 / 有新版本 / 检查失败。 */
sealed interface UpdateCheckResult {
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

/**
 * 基于 GitHub Release 的在线更新。
 *
 * 国内直连 GitHub 常超时，这里所有请求都优先走 gh-proxy.com 的加速前缀，
 * 失败再回退直连：接口元数据只有几 KB、安装包较大，代理能显著提升成功率与速度。
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 检查最新发布版本：拉到 release 后与当前版本比对，
     * 只有远端版本更高才返回 [UpdateCheckResult.Available]。
     */
    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val release = parseRelease(fetchText(latestApiUrls()))
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return@withContext UpdateCheckResult.Failed("这次发布没有找到安装包")
            val tagVersion = normalizeVersion(release.tagName)
            if (compareVersions(tagVersion, normalizeVersion(BuildConfig.VERSION_NAME)) <= 0) {
                return@withContext UpdateCheckResult.UpToDate(BuildConfig.VERSION_NAME)
            }
            UpdateCheckResult.Available(
                UpdateInfo(
                    versionName = tagVersion,
                    notes = release.body.trim(),
                    apkUrl = apkAsset.downloadUrl,
                    sizeBytes = apkAsset.size
                )
            )
        } catch (e: Exception) {
            UpdateCheckResult.Failed(e.message ?: "检查更新失败，请检查网络后重试")
        }
    }

    /**
     * 下载新版本 APK 到应用内缓存目录，边下边回调进度。
     * 代理失败时自动回退直连地址。
     *
     * 这里不限制网络类型：WiFi 下由调用方直接下载；移动数据（按流量计费）下
     * 调用方先看 [isMeteredConnection]，为 true 就先弹窗征得用户同意，同意后再调用本方法。
     */
    suspend fun downloadApk(
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, "lemonbox-${info.versionName}.apk")
        var lastError: Exception? = null
        for (url in downloadUrls(info.apkUrl)) {
            try {
                downloadTo(url, target, onProgress)
                return@withContext target
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("下载失败，请稍后重试")
    }

    /** 是否已允许「安装未知来源应用」；未允许时需先把用户引导到系统设置页。 */
    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /**
     * 当前是否处于按流量计费的移动数据网络。
     *
     * 安装包通常几十 MB，移动数据下应由调用方先弹窗让用户确认，避免悄悄消耗流量；
     * WiFi / 以太网（含 WiFi 热点）返回 false，直接下载即可。
     */
    fun isMeteredConnection(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /** 打开「安装未知来源应用」设置页，让用户为柠檬百宝箱放行。 */
    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * 浏览器兜底下载可用的安装包地址：与应用内下载同源，加速优先、直连兜底。
     *
     * 本应用不发布到应用商店，应用内调起的系统安装器在部分机型上会被额外拦截
     * （纯净模式、安装审计等）；交给浏览器下载是各 ROM 放行度最高的兜底路径——
     * 浏览器下载完成后用户在通知栏点开即可安装，安装权限由浏览器侧自行引导。
     */
    fun browserDownloadUrls(apkUrl: String): List<String> = downloadUrls(apkUrl)

    /** 用系统浏览器打开下载链接，返回是否成功调起（无浏览器等场景返回 false）。 */
    fun openInBrowser(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 调起系统安装器安装已下载好的 APK。 */
    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private suspend fun downloadTo(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val conn = openConnection(url, readTimeoutMs = READ_TIMEOUT_MS)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = conn.contentLengthLong
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
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 依次尝试的元数据地址：gh-proxy 加速优先，直连兜底。 */
    private fun latestApiUrls(): List<String> = listOf(
        PROXY_PREFIX + LATEST_API,
        LATEST_API
    )

    /** 依次尝试的安装包地址：gh-proxy 加速优先，直连兜底。 */
    private fun downloadUrls(apkUrl: String): List<String> = listOf(
        PROXY_PREFIX + apkUrl,
        apkUrl
    )

    private fun fetchText(urls: List<String>): String {
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val conn = openConnection(url, readTimeoutMs = CONNECT_TIMEOUT_MS)
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    return conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("网络请求失败")
    }

    private fun openConnection(url: String, readTimeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            this.readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            // GitHub API 必须带 User-Agent。
            setRequestProperty("User-Agent", "LemonBox/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    private fun parseRelease(json: String): GhRelease =
        jsonCodec.decodeFromString(GhRelease.serializer(), json)

    /** 去掉前缀 v/V 与多余空白，得到纯数字版本串。 */
    private fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    /** 逐段比较版本号：a 大于 b 返回正数，相等返回 0，小于返回负数。 */
    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val diff = (left.getOrElse(i) { 0 }) - (right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private companion object {
        const val REPO_OWNER = "Goodyaoshi"
        const val REPO_NAME = "lemonbox"
        const val PROXY_PREFIX = "https://gh-proxy.com/"
        const val LATEST_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000

        val jsonCodec = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tagName: String = "",
    val body: String = "",
    val assets: List<GhAsset> = emptyList()
)

@Serializable
private data class GhAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0L
)
