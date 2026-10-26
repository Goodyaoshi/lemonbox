package com.goodyaoshi.lemonbox.data.imagecloud

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云端图片的加载模型：只携带对象键，不含任何网络结果。
 *
 * 之所以是一个专用类型而非 URL 字符串：预签名 URL 要一次网络往返才能拿到，
 * 而渲染路径（modelForHash）必须保持同步；把「待取的键」交给 Coil，
 * 网络动作挪进 [CloudImageFetcherFactory] 产出的取图器挂起阶段完成。
 */
data class CloudImageModel(val objectKey: String)

/**
 * 内存缓存键固定为对象键：预签名 URL 每次都变、以它作键永远不命中；
 * 对象键由内容哈希推导，同一张图取回多少次都命中同一份。
 */
class CloudImageModelKeyer : Keyer<CloudImageModel> {
    override fun key(data: CloudImageModel, options: Options): String = data.objectKey
}

/**
 * Coil 的云端取图器（P8）。
 *
 * 下载直接落进 [ImageRestoreStore]：本次显示与「取回落盘」合并成一次网络传输
 * （旧链路里 Coil 拉一遍、后台回填再拉一遍），落盘后后续任何入口完全离线可用。
 */
@Singleton
class CloudImageFetcherFactory @Inject constructor(
    private val gateway: BlobGateway,
    private val restoreStore: ImageRestoreStore
) : Fetcher.Factory<CloudImageModel> {

    override fun create(data: CloudImageModel, options: Options, imageLoader: ImageLoader): Fetcher =
        CloudImageFetcher(data, gateway, restoreStore)
}

private class CloudImageFetcher(
    private val model: CloudImageModel,
    private val gateway: BlobGateway,
    private val restoreStore: ImageRestoreStore
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val target = restoreStore.fileFor(model.objectKey)
        val existed = target.isFile
        if (!existed) {
            downloadToRestoreStore(model.objectKey, target, gateway)
        }
        SourceResult(
            source = ImageSource(target.absolutePath.toPath(), FileSystem.SYSTEM),
            mimeType = CONTENT_TYPE,
            dataSource = if (existed) DataSource.DISK else DataSource.NETWORK
        )
    }
}

/**
 * 「本地优先，失败自动回退云端」的图片模型。
 *
 * 为什么需要：解析（stat 本地文件）与加载是两个时刻。保存后几秒内同步会把本地副本
 * 挪进回填缓存（cloud_restore，见 ImageCloudSyncManager.settleLocalCopyIfSafe），窗口期里
 * 解析出的本地路径在加载时文件已经不在了——Coil 对失败的请求不会自己重试，图片就
 * 一直空到用户离开再回来。把回退装进取图器后，这一竞态与离线/瞬时失败都能自动接住，
 * 回退分支优先命中回填缓存（零网络），真不在才走云端取回，无需用户操作。
 *
 * 内存缓存键与纯本地路径模型一致（都是路径字符串）：同一张图无论以哪种模型解析，
 * 命中的都是同一份内存缓存。
 */
data class LocalImageWithCloudFallback(val localPath: String, val objectKey: String)

class LocalImageWithCloudFallbackKeyer : Keyer<LocalImageWithCloudFallback> {
    override fun key(data: LocalImageWithCloudFallback, options: Options): String = data.localPath
}

@Singleton
class LocalImageWithCloudFallbackFetcherFactory @Inject constructor(
    private val gateway: BlobGateway,
    private val restoreStore: ImageRestoreStore
) : Fetcher.Factory<LocalImageWithCloudFallback> {

    override fun create(
        data: LocalImageWithCloudFallback,
        options: Options,
        imageLoader: ImageLoader
    ): Fetcher = LocalImageWithCloudFallbackFetcher(data, gateway, restoreStore)
}

private class LocalImageWithCloudFallbackFetcher(
    private val model: LocalImageWithCloudFallback,
    private val gateway: BlobGateway,
    private val restoreStore: ImageRestoreStore
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val local = File(model.localPath)
        if (local.isFile) {
            SourceResult(
                source = ImageSource(model.localPath.toPath(), FileSystem.SYSTEM),
                mimeType = CONTENT_TYPE,
                dataSource = DataSource.DISK
            )
        } else {
            // 本地副本不在了（被同步挪进回填缓存 / 缓存被系统清理）：先查回填缓存，
            // 命中即零网络；没有才走云端取回，与 CloudImageFetcher 同一条路。
            val target = restoreStore.fileFor(model.objectKey)
            val existed = target.isFile
            if (!existed) {
                downloadToRestoreStore(model.objectKey, target, gateway)
            }
            SourceResult(
                source = ImageSource(target.absolutePath.toPath(), FileSystem.SYSTEM),
                mimeType = CONTENT_TYPE,
                dataSource = if (existed) DataSource.DISK else DataSource.NETWORK
            )
        }
    }
}

/**
 * 下载到 [ImageRestoreStore]（带一次重试）。
 *
 * 首次取图的链路是「签名（Edge Function 往返）+ 下载」两跳，冷启动或弱网下单次失败
 * 并不少见，而 Coil 对失败的请求不会自己重试。404 不重试：那是云端真没有这张图。
 * （BlobGateway.downloadTo 先写临时文件、成功后才改名，重试不会留下半成品缓存。）
 */
private suspend fun downloadToRestoreStore(
    objectKey: String,
    target: File,
    gateway: BlobGateway
): DataSource {
    var lastMessage: String? = null
    repeat(DOWNLOAD_ATTEMPTS) {
        when (val outcome = gateway.downloadTo(objectKey, target)) {
            S3Outcome.Success -> return DataSource.NETWORK
            S3Outcome.NotFound -> throw FileNotFoundException("云端还没有这张图片")
            is S3Outcome.Error -> lastMessage = outcome.message
        }
        delay(RETRY_DELAY_MS)
    }
    throw IOException(lastMessage ?: "取图失败，请稍后再试")
}

private const val CONTENT_TYPE = "image/jpeg"
private const val DOWNLOAD_ATTEMPTS = 2
private const val RETRY_DELAY_MS = 400L
