package com.goodyaoshi.lemonbox.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.goodyaoshi.lemonbox.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** 新版本信息：版本号、更新说明、APK 下载地址与体积。 */
data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    /**
     * release 同时发布的 `<apk>.sha256` 内容（小写十六进制）。
     *
     * 旧版本发布没有该资产时为 null：应用内安装仍会做**签名校验**（更强的防投毒保证），
     * 只是少了「下载内容是否与发布者上传一致」这一层完整性校验。
     */
    val sha256: String? = null
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

/** 应用内下载并校验的结果。 */
sealed interface ApkDownloadResult {
    data class Success(val file: File, val sha256: String) : ApkDownloadResult
    data class Failed(val message: String) : ApkDownloadResult
}

/** 调起系统安装器的结果。 */
sealed interface ApkInstallResult {
    data object Started : ApkInstallResult
    data object NeedsPermission : ApkInstallResult
    data class Failed(val message: String) : ApkInstallResult
}

/**
 * 基于 GitHub Release 的在线更新。
 *
 * 两条下载路径并存：
 *  - **应用内下载并校验安装**（推荐）：下载到 cacheDir，先比对发布方提供的 SHA-256，
 *    再用 `PackageManager` 比对 APK 签名证书与当前应用是否一致；两者任一不符就删除并拒绝安装。
 *    即使走 gh-proxy 等第三方加速，签名无法伪造，代理投毒也装不上。
 *  - **浏览器下载**：保留为兜底路径。个别 ROM 对「应用内调起安装器」限制严格，
 *    浏览器侧引导授权更顺畅；但浏览器下载无法由应用校验，只作为用户明确选择。
 *
 * 国内直连 GitHub 常超时，元数据与安装包都优先走 gh-proxy.com 加速前缀，失败再回退直连。
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
            // 校验和资产是 CI 在发布时生成的同名 `.sha256`；失败不阻塞更新检查。
            val checksumAsset = release.assets.firstOrNull { it.name == apkAsset.name + ".sha256" }
            val sha256 = checksumAsset?.let { asset ->
                runCatching {
                    fetchText(downloadUrls(asset.downloadUrl))
                        .trim()
                        .substringBefore(' ')
                        .lowercase()
                }.getOrNull()?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
            }
            val info = UpdateInfo(
                versionName = tagVersion,
                notes = release.body.trim(),
                apkUrl = apkAsset.downloadUrl,
                sizeBytes = apkAsset.size,
                sha256 = sha256
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
     * 下载 APK 并校验 SHA-256 与签名；只有全部通过才返回可安装文件。
     *
     * [onProgress] 传入 0..1；发布方未提供大小时传入 -1f（界面用不确定进度）。
     */
    suspend fun downloadAndVerify(
        info: UpdateInfo,
        onProgress: (Float) -> Unit
    ): ApkDownloadResult = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // 只清自己的临时文件，避免旧版本残留占空间。
        dir.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
        val target = File(dir, "lemonbox-${info.versionName}.apk")
        val part = File(dir, target.name + ".part")
        var lastError: Exception? = null
        for (url in downloadUrls(info.apkUrl)) {
            try {
                downloadTo(part, url, info.sizeBytes, onProgress)
                val digest = sha256Hex(part)
                info.sha256?.let { expected ->
                    if (!digest.equals(expected, ignoreCase = true)) {
                        throw IOException("安装包 SHA-256 校验失败，可能被代理或镜像篡改")
                    }
                }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                verifySignature(target)
                return@withContext ApkDownloadResult.Success(target, digest)
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: Exception) {
                lastError = e
                part.delete()
            }
        }
        ApkDownloadResult.Failed(lastError?.message ?: "下载失败，请检查网络后重试")
    }

    /**
     * 浏览器下载可用的安装包地址：加速优先、直连兜底。
     *
     * 应用内校验安装不可用（未授权安装未知应用 / 个别 ROM 调不起安装器）时的兜底。
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

    /** 当前是否允许安装未知来源应用；Android 8 以下默认允许。 */
    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** 跳转系统「安装未知应用」授权页。 */
    fun openInstallPermissionSettings(): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.isSuccess

    /** 调起系统安装器；未授权时返回 [ApkInstallResult.NeedsPermission]。 */
    fun installApk(file: File): ApkInstallResult {
        if (!canInstallPackages()) return ApkInstallResult.NeedsPermission
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            ApkInstallResult.Started
        } catch (e: Exception) {
            ApkInstallResult.Failed(e.message ?: "无法打开系统安装器")
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

    private fun downloadTo(
        target: File,
        url: String,
        expectedBytes: Long,
        onProgress: (Float) -> Unit
    ) {
        val conn = openConnection(url).apply {
            readTimeout = DOWNLOAD_TIMEOUT_MS
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = expectedBytes.takeIf { it > 0 } ?: conn.contentLengthLong
            var read = 0L
            target.outputStream().use { output ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        onProgress(
                            if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f
                        )
                    }
                }
            }
            if (expectedBytes > 0 && read != expectedBytes) {
                throw IOException("安装包大小与发布信息不一致")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 签名校验：下载包的签名证书必须与当前安装包有交集。
     *
     * 这是防代理投毒的最后一道、也是最可靠的一道：攻击者能改 APK 内容与 sha256 文件，
     * 但没有发布者的签名私钥就无法伪造签名。校验失败一律删除，不交给安装器。
     */
    private fun verifySignature(apk: File) {
        val pm = context.packageManager
        val flags = signingFlags()
        val downloaded = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IOException("无法读取安装包信息")
        val current = pm.getPackageInfo(context.packageName, flags)
        val downloadedSigners = signerDigests(downloaded)
        val currentSigners = signerDigests(current)
        if (downloadedSigners.isEmpty() ||
            currentSigners.isEmpty() ||
            downloadedSigners.intersect(currentSigners).isEmpty()
        ) {
            apk.delete()
            throw IOException("安装包签名与当前应用不一致，已拒绝安装")
        }
    }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo
                ?.apkContentsSigners
                ?.map { sha256Hex(it.toByteArray()) }
                ?.toSet()
                .orEmpty()
        } else {
            info.signatures
                ?.map { sha256Hex(it.toByteArray()) }
                ?.toSet()
                .orEmpty()
        }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

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

        /** 安装包体积较大，读超时单独放宽。 */
        const val DOWNLOAD_TIMEOUT_MS = 60_000

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
