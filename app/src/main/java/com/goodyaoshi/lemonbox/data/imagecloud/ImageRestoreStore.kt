package com.goodyaoshi.lemonbox.data.imagecloud

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从对象键推导本地回填文件名（纯逻辑，便于单测）。
 *
 * 取末段而不是整串：对象键形如 `[prefix/]images/<sha256>.jpg`，末段就是
 * 「内容哈希 + 扩展名」。同一张图在任何设备、任何前缀下都得到同一个文件名，
 * 因此重复取回天然落到同一个文件，不会产生副本。
 * 键为空或末段为空时返回 null，调用方据此跳过（不拿空名去当文件名）。
 */
internal fun restoreFileNameFor(objectKey: String?): String? =
    objectKey.orEmpty().trim().substringAfterLast('/').takeIf { it.isNotBlank() }

/**
 * 云端图片取回后的本地落点（F20）。
 *
 * 解决的问题：图片上传成功后会删掉本地副本以省空间，于是每次显示都要走一趟网络——
 * 列表、详情、全屏预览各自都要等一次，表现就是「每个地方都白屏一会儿」。
 * 这里把取回过的图落盘：同一张图第二次显示直接读本地，既保留「不长期占本地空间」的初衷，
 * 又让重复显示不再联网。
 *
 * 为什么目录与 [com.goodyaoshi.lemonbox.util.ImageUtil] 的用户图片分开：这些是**可再生**
 * 的缓存，与用户原始照片的语义不同（丢了可以重新取回），分开存放便于按需整体清理，
 * 也不会被「删除物品时清理图片」之类的逻辑误删。
 */
@Singleton
class ImageRestoreStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 某个对象的本地副本位置；不保证已存在（不存在即表示尚未取回过）。 */
    fun fileFor(objectKey: String): File =
        File(dir(), restoreFileNameFor(objectKey) ?: RETURN_NONE)

    /** 删除某个对象的本地副本（云端对象被清理时一并调用）。 */
    fun delete(objectKey: String) {
        val name = restoreFileNameFor(objectKey) ?: return
        runCatching {
            val file = File(dir(), name)
            if (file.isFile) file.delete()
        }
    }

    private fun dir(): File = File(context.filesDir, DIR_NAME)

    private companion object {
        const val DIR_NAME = "cloud_restore"

        /** 空键的占位名：`dir()/` 本身是目录、`isFile` 恒为 false，因此不会误命中或误删。 */
        const val RETURN_NONE = ""
    }
}
