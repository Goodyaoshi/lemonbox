package com.goodyaoshi.lemonbox.data.imagecloud

import android.content.Context
import com.goodyaoshi.lemonbox.data.settings.ImageLoadingMode
import com.goodyaoshi.lemonbox.util.ImageUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
 * 把「本地路径 + 云端对象键」解析成图片加载器能直接使用的 model（F22 / P8）。
 *
 * [modelForHash] 是**同步**调用（Compose 渲染路径，见 rememberImageModel），而 P8 之后
 * 预签名 URL 需要一次网络往返（边缘函数 `blob-sign`）才能取得，无法在渲染线程同步完成。
 * 因此云端分支不再返回 URL，而是返回纯数据模型 [CloudImageModel]（只装对象键）：
 * 真正的网络动作交给注册进 Coil 的 [CloudImageFetcherFactory]，在其挂起阶段完成。
 *
 * 其余取舍与旧版一致：
 *  1. **取回过的图落盘复用**：云端取回的图会存进 [ImageRestoreStore]，之后任何入口直接读
 *     本地文件，不再联网（下载动作本身就由取图器落盘，见 CloudImageFetcher）。
 *  2. **缓存键用对象键而非 URL**：预签名 URL 每次都变，以它作键永远不命中；
 *     用内容哈希推导的对象键作缓存键，同一张图取回多少次都命中同一份（见 CloudImageModelKeyer）。
 *
 * 只收一个哈希是有意的：本地路径（`item_<hash>.jpg`）与云端对象键
 * （`[prefix/]images/<hash>.jpg`）都能由它算出来，调用方因此不必再各自维护
 * 「路径」与「键」两份数据——它们本就是同一个哈希的两种呈现，分开存放只会漂移。
 *
 * 注意：会做文件存在性判断，调用方应配合 `remember(hash)` 避免每次重组都重新 stat。
 */
@Singleton
class ImageUrlResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: ImageCloudConfig,
    private val restoreStore: ImageRestoreStore
) {

    fun modelForHash(
        hash: String?,
        mode: ImageLoadingMode = ImageLoadingMode.STANDARD
    ): Any? {
        // 无图模式在任何一步之前就退出：连本地文件都不读，保证列表渲染零 IO。
        if (mode == ImageLoadingMode.NO_IMAGE) return null

        val contentHash = hash.orEmpty().trim()
        if (contentHash.isEmpty()) return null
        val localPath = ImageUtil.localPathForHash(context, contentHash)
        val location = decideImageLocation(
            localPath = localPath,
            localAvailable = File(localPath).isFile,
            objectKey = ImageObjectKeys.forContentHash(config.prefix(), contentHash)
        )
        return when (location) {
            is ImageLocation.Local -> when (mode) {
                // 省流量 / 省存储下本地副本照常显示，只是不再为它准备云端兜底。
                ImageLoadingMode.SAVE_DATA, ImageLoadingMode.SAVE_STORAGE -> location.path
                else -> localModelWithCloudFallback(location.path, contentHash)
            }
            is ImageLocation.Cloud -> restoredOrCloudModel(location.objectKey, mode)
            ImageLocation.None -> null
        }
    }

    /**
     * 本地路径包一层「加载失败自动改走云端」的模型（见 LocalImageWithCloudFallback）。
     *
     * 兜两件事：其一，保存后几秒内同步会把本地副本挪进回填缓存，「解析时文件在、
     * 加载时已挪走」的窗口期不再让图片空到下次重组——回退分支会命中回填缓存，零网络；
     * 其二，真不在时改走云端取回。云端兜底沿用与 [restoredOrCloudModel] 相同的门槛：
     * 配置不支持或未登录时不包装，直接返回路径让占位态接管（保持旧行为）。
     */
    private fun localModelWithCloudFallback(path: String, hash: String): Any {
        if (!config.supported || !config.isLoggedIn()) return path
        return LocalImageWithCloudFallback(
            localPath = path,
            objectKey = ImageObjectKeys.forContentHash(config.prefix(), hash)
        )
    }

    /**
     * 云端取图：先看有没有之前落盘的副本，有就直接用本地文件（完全不联网）；
     * 没有才交给取图器。与旧链路一致：取不到云端凭据（未登录等）时返回 null，
     * 让占位态接管，而不是让加载器报错。
     */
    private fun restoredOrCloudModel(objectKey: String, mode: ImageLoadingMode): Any? {
        val restored = restoreStore.fileFor(objectKey)
        if (restored.isFile) return restored.absolutePath
        if (!config.supported || !config.isLoggedIn()) return null
        return when (mode) {
            // 省流量：已有副本可用就够了，不再为缺图发起下载。
            ImageLoadingMode.SAVE_DATA -> null
            // 省存储：照常取图，但只落临时缓存，系统清理时自动释放。
            ImageLoadingMode.SAVE_STORAGE -> CloudImageModel(objectKey, cacheOnly = true)
            else -> CloudImageModel(objectKey)
        }
    }
}
