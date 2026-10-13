package com.goodyaoshi.lemonbox.data.imagecloud

import android.content.Context
import coil.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

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
 * 抽成不依赖 Android 的纯函数是为了可单测：这条判断一旦写错，
 * 表现是「新设备上图片一直空着」，从界面看不出原因，只能靠日志，
 * 所以把决策本身锁住比事后排查划算得多。
 *
 * [localAvailable] 由调用方传入（真实实现里是文件是否存在），
 * 这样本函数不需要碰文件系统，测试也不依赖真实文件。
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
 * 两个关键取舍：
 *  1. **缓存键用对象键而非 URL**。预签名 URL 每次都不同（时间戳与签名都在变），
 *     若以它作键，缓存永远不命中、每帧都可能重新下载。用内容哈希推导的对象键作
 *     内存与磁盘缓存键，同一张图无论取回多少次都命中同一份缓存。
 *  2. **签名结果与凭据都做短时缓存**。[ImageCloudConfig.resolve] 会走 Keystore 解密，
 *     绝不能每次渲染都调；预签名本身也有有效期，提前续签即可，不必每帧重算。
 */
@Singleton
class ImageUrlResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: ImageCloudConfig,
    private val client: S3ImageClient
) {

    private data class CachedUrl(val url: String, val usableUntilMillis: Long)

    /** 对象键 → 预签名 URL。数量级等于图片张数，无需淘汰策略。 */
    private val presignCache = ConcurrentHashMap<String, CachedUrl>()

    @Volatile
    private var cachedConfig: S3Config? = null

    @Volatile
    private var cachedConfigAtMillis = 0L

    /**
     * 解析出可直接喂给 `AsyncImage(model = ...)` 的值；无从取图时返回 null，
     * 由调用方的占位/错误态接管。
     *
     * 注意：会做一次文件存在性判断，调用方应配合 `remember(localPath, objectKey)`，
     * 避免每次重组都重新 stat。
     */
    fun model(localPath: String?, objectKey: String?): Any? {
        val location = decideImageLocation(
            localPath = localPath,
            localAvailable = localPath?.takeIf { it.isNotBlank() }?.let { File(it).isFile } == true,
            objectKey = objectKey
        )
        return when (location) {
            is ImageLocation.Local -> location.path
            is ImageLocation.Cloud -> cloudModel(location.objectKey)
            ImageLocation.None -> null
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
    }
}
