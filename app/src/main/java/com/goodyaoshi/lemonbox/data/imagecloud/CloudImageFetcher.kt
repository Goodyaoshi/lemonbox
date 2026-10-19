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
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
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
            when (val outcome = gateway.downloadTo(model.objectKey, target)) {
                S3Outcome.Success -> Unit
                S3Outcome.NotFound -> throw FileNotFoundException("云端还没有这张图片")
                is S3Outcome.Error -> throw IOException(outcome.message)
            }
        }
        SourceResult(
            source = ImageSource(target.absolutePath.toPath(), FileSystem.SYSTEM),
            mimeType = CONTENT_TYPE,
            dataSource = if (existed) DataSource.DISK else DataSource.NETWORK
        )
    }

    private companion object {
        const val CONTENT_TYPE = "image/jpeg"
    }
}
