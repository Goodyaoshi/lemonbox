package com.goodyaoshi.lemonbox.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.goodyaoshi.lemonbox.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** 新版本信息：版本号、更新说明、APK 下载地址与体积。 */
data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long
)

/** 检查更新结果：已是最新 / 有新版本 / 检查失败。 */
sealed interface UpdateCheckResult {
    /**
     * 已是最新。**仍带上 [info]**：界面据此提供「下载安装包」入口
     * （重装、分享安装包给他人等场景需要），而不是只给一句文字提示。
     */
    data class UpToDate(val currentVersion: String, val info: UpdateInfo) : UpdateCheckResult

    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

/**
 * 基于 GitHub Release 的在线更新。
 *
 * 国内直连 GitHub 常超时，所有请求都优先走 gh-proxy.com 的加速前缀，失败再回退直连。
 *
 * 只负责「查版本 + 交给浏览器下载」：设备上普遍拿不到「安装未知来源应用」授权，
 * 应用内调起的安装器在多数机型上根本走不通，留着只会让用户卡在授权页。
 * 改为交给浏览器下载，完成后用户在通知栏点开安装包，是各 ROM 放行度最高的路径，
 * 安装授权也由浏览器侧引导。
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 检查最新发布版本并对照当前版本。
     *
     * 无论远端是否更高都会带回 [UpdateInfo]，只有 [UpdateCheckResult.Failed] 没有：
     * 「没有新版」不等于「不需要安装包」，重装与分享同样要下载地址。
     */
    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val release = parseRelease(fetchText(latestApiUrls()))
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return@withContext UpdateCheckResult.Failed("这次发布没有找到安装包")
            val tagVersion = normalizeVersion(release.tagName)
            val info = UpdateInfo(
                versionName = tagVersion,
                notes = release.body.trim(),
                apkUrl = apkAsset.downloadUrl,
                sizeBytes = apkAsset.size
            )
            if (compareVersions(tagVersion, normalizeVersion(BuildConfig.VERSION_NAME)) <= 0) {
                UpdateCheckResult.UpToDate(BuildConfig.VERSION_NAME, info)
            } else {
                UpdateCheckResult.Available(info)
            }
        } catch (e: Exception) {
            UpdateCheckResult.Failed(e.message ?: "检查更新失败，请检查网络后重试")
        }
    }

    /**
     * 浏览器下载可用的安装包地址：加速优先、直连兜底。
     *
     * 应用自身不再下载安装——安装授权在各机型上普遍拿不到，交给浏览器是放行度最高的路径；
     * 两个地址都给出，是为了让用户在加速服务不可用时还能直连。
     */
    fun browserDownloadUrls(apkUrl: String): List<String> = downloadUrls(apkUrl)

    /** 用系统浏览器打开下载链接，返回是否成功调起（无浏览器等场景返回 false）。 */
    fun openInBrowser(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
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
                val conn = openConnection(url)
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

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = CONNECT_TIMEOUT_MS
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
