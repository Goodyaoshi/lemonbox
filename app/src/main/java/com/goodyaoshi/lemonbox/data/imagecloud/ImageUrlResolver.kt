package com.goodyaoshi.lemonbox.data.imagecloud

import android.content.Context
import coil.request.ImageRequest
import com.goodyaoshi.lemonbox.util.ImageUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * 一张图该从哪里取的决策结果（纯数据，不含任何 IO）。
 * [Local] 与 [Cloud] 都保留各自的定位信息，便于排查「为什么这张图没显示」。
 */
internal sealed interface ImageLocation {
    /** 本地文件可用，直接用路径加载——离线也能看。 */
    data class Local(val path: String) : ImageLocation

    /** 本地文件不在了，但知道云端对象键，可临时取回。 */
    data class Cloud(val objectKey: String) : ImageLocation

    /** 既没有可用本地文件、也没有对象键，无从取图。 */
    data object None : ImageLocation
}

/**
 * 决定图片来源：**本地优先，云端兜底**。
 *
 * 抽成不依赖 Android 的纯函数以便单测：这条判断写错的表现是「新设备上图片一直空着」，
 * 从界面看不出原因。[localAvailable] 由调用方传入，本函数因此不碰文件系统。
 */
internal fun decideImageLocation(
    localPath: String?,
    localAvailable: Boolean,
    objectKey: String?
): ImageLocation {
    val path = localPath.orEmpty().trim()
    if (path.isNotEmpty() && localAvailable) return ImageLocation.Local(path)

    val key = objectKey.orEmpty().trim()
    if (key.isNotEmpty()) return ImageLocation.Cloud(key)

    // 本地文件没了、又没有对象键：仍然回退到本地路径，让图片加载器走它的
    // 错误/占位分支。比直接返回 null 好——至少路径信息还在，便于定位问题。
    return if (path.isNotEmpty()) ImageLocation.Local(path) else ImageLocation.None
}

/**
 * 把「本地路径 + 云端对象键」解析成图片加载器能直接使用的 model（F20）。
 *
 * 三个关键取舍：
 *  1. **取回过的图落盘复用**：上传成功后本地副本会被删掉，若不落盘，列表 / 详情 / 预览
 *     每一处首次显示都要等一趟网络（还要先解密 Keystore 生成预签名 URL）。因此云端取回的图
 *     会存进 [ImageRestoreStore]，之后任何入口直接读本地文件，不再联网。
 *  2. **缓存键用对象键而非 URL**：预签名 URL 每次都变（含时间戳与签名），以它作键永远不命中；
 *     用内容哈希推导的对象键作内存/磁盘缓存键，同一张图取回多少次都命中同一份。
 *  3. **签名结果与凭据都做短时缓存**：[ImageCloudConfig.resolve] 会走 Keystore 解密，不能每帧调。
 */
@Singleton
class ImageUrlResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: ImageCloudConfig,
    private val client: S3ImageClient,
    private val restoreStore: ImageRestoreStore
) {

    private data class CachedUrl(val url: String, val usableUntilMillis: Long)

    /** 对象键 → 预签名 URL。数量级等于图片张数，无需淘汰策略。 */
    private val presignCache = ConcurrentHashMap<String, CachedUrl>()

    /**
     * 回填用的后台作用域：只做落盘，不参与界面生命周期。
     *
     * 并发限制为 2 是刻意的：回填本身是为了让**后续**显示更快，若放任它一次性抢下所有
     * 带宽，反而会把用户正在等的这次显示拖慢——那就得不偿失了。列表一屏可能有几十张图，
     * 逐个慢慢补即可。
     */
    private val restoreScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(RESTORE_CONCURRENCY))

    /** 正在回填的对象键，避免同一张图被多次并发下载。 */
    private val restoreInFlight = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var cachedConfig: S3Config? = null

    @Volatile
    private var cachedConfigAtMillis = 0L

    /**
     * 由内容哈希解析出可直接喂给 `AsyncImage(model = ...)` 的值（F22）。
     *
     * 只收一个哈希是有意的：本地路径（`item_<hash>.jpg`）与云端对象键
     * （`[prefix/]images/<hash>.jpg`）都能由它算出来，调用方因此不必再各自维护
     * 「路径」与「键」两份数据——它们本就是同一个哈希的两种呈现，分开存放只会漂移。
     *
     * 注意：会做文件存在性判断，调用方应配合 `remember(hash)` 避免每次重组都重新 stat。
     */
    fun modelForHash(hash: String?): Any? {
        val contentHash = hash.orEmpty().trim()
        if (contentHash.isEmpty()) return null
        val localPath = ImageUtil.localPathForHash(context, contentHash)
        val location = decideImageLocation(
            localPath = localPath,
            localAvailable = File(localPath).isFile,
            objectKey = ImageObjectKeys.forContentHash(config.prefix(), contentHash)
        )
        return when (location) {
            is ImageLocation.Local -> location.path
            is ImageLocation.Cloud -> restoredOrCloudModel(location.objectKey)
            ImageLocation.None -> null
        }
    }

    /**
     * 云端取图：先看有没有之前落盘的副本，有就直接用本地文件（完全不联网）；
     * 没有才走预签名 URL，并顺手在后台把图落盘，让后续显示不再等网络。
     */
    private fun restoredOrCloudModel(objectKey: String): Any? {
        val restored = restoreStore.fileFor(objectKey)
        if (restored.isFile) return restored.absolutePath
        ensureRestored(objectKey)
        return cloudModel(objectKey)
    }

    /**
     * 后台把云端对象落盘，供后续显示直接读本地。
     *
     * 刻意不等结果：这是「下次更快」的优化，本次显示仍走 URL，不该让界面等它。
     * 失败也不上报——网络恢复后下次显示会再试；同一对象键同时只下一次。
     */
    private fun ensureRestored(objectKey: String) {
        if (!restoreInFlight.add(objectKey)) return
        restoreScope.launch {
            try {
                val target = restoreStore.fileFor(objectKey)
                if (target.isFile) return@launch
                val s3 = currentConfig() ?: return@launch
                client.getObject(s3, objectKey, target)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 落盘失败只是少了一次优化，本次仍能通过 URL 正常显示。
            } finally {
                restoreInFlight.remove(objectKey)
            }
        }
    }

    private fun cloudModel(objectKey: String): Any? {
        val url = presignedUrl(objectKey) ?: return null
        return ImageRequest.Builder(context)
            .data(url)
            // 缓存键固定为对象键：URL 每次都变，用对象键才能真正命中缓存、支持离线。
            .memoryCacheKey(objectKey)
            .diskCacheKey(objectKey)
            .build()
    }

    private fun presignedUrl(objectKey: String): String? {
        val now = System.currentTimeMillis()
        presignCache[objectKey]?.let { cached ->
            if (cached.usableUntilMillis > now) return cached.url
        }
        val s3 = currentConfig() ?: return null
        val url = client.presignedGetUrl(s3, objectKey) ?: return null
        presignCache[objectKey] = CachedUrl(url, now + SIGNED_URL_TTL_MILLIS)
        return url
    }

    /**
     * 取当前连接配置，最多每分钟重新解析一次。
     * [ImageCloudConfig.resolve] 内部要解一次 Keystore 才能拿到凭据，
     * 这个代价不能压在每一次图片渲染上。
     */
    private fun currentConfig(): S3Config? {
        val now = System.currentTimeMillis()
        if (now - cachedConfigAtMillis < CONFIG_TTL_MILLIS) return cachedConfig
        val resolved = config.resolve()
        cachedConfig = resolved
        cachedConfigAtMillis = now
        return resolved
    }

    private companion object {
        /**
         * 预签名 URL 的复用窗口：略短于实际有效期（1 小时），
         * 留出余量避免"刚签好就过期"。
         */
        const val SIGNED_URL_TTL_MILLIS = 55 * 60 * 1000L

        /** 连接配置缓存 1 分钟：用户改配置后最多这么久生效，换取不做逐帧解密。 */
        const val CONFIG_TTL_MILLIS = 60 * 1000L

        /** 回填的并发上限：少占带宽，优先保证用户当前这次显示。 */
        const val RESTORE_CONCURRENCY = 2
    }
}
