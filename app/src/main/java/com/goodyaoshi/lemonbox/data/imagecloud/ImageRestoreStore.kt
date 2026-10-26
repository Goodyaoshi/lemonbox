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
 * 云端图片的本地落点（F20）。
 *
 * 两个入口都落在这里：
 *  1. 云端取回（[CloudImageFetcher]）：显示时本地没有就走网络，取回即落盘，
 *     同一张图第二次显示直接读本地，不再联网；
 *  2. 上传完成后的本地副本收编（[admit]）：与其删掉，不如挪进来——展示端先查
 *     images/、查不到就查这里，挪入后离线可用，效果等同「下载回来落盘」但零网络。
 *
 * 为什么目录与 [com.goodyaoshi.lemonbox.util.ImageUtil] 的用户图片分开：这些副本
 * 都能从云端再生，语义上与「用户原始照片的工作目录」不同；分开存放便于按需整体清理，
 * 也不会被「删除物品时清理图片」之类的逻辑误删。
 */
@Singleton
class ImageRestoreStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 某个对象的本地副本位置；不保证已存在（不存在即表示尚未取回过）。 */
    fun fileFor(objectKey: String): File =
        File(dir(), restoreFileNameFor(objectKey) ?: RETURN_NONE)

    /**
     * 把一份本地副本收编进回填目录（改名挪入，尽量不复制字节）。
     *
     * 上传确认云端可读后调用：源文件（images/ 下的 `item_<hash>.jpg`）挪到这里后，
     * 展示端查不到原路径就会命中本目录，离线可用——等于把「云端下载回来」这件事
     * 用一次本地改名替代了。target 已有同名文件时直接丢弃源（内容寻址，同名即同内容）。
     * 返回 true 表示源文件已被消化（挪入或丢弃）；失败返回 false，调用方自行兜底。
     */
    fun admit(source: File, objectKey: String): Boolean {
        val name = restoreFileNameFor(objectKey) ?: return false
        return runCatching {
            val target = File(dir(), name)
            if (target.isFile) {
                source.delete()
                return@runCatching true
            }
            dir().mkdirs()
            if (source.renameTo(target)) return@runCatching true
            // rename 可能在个别文件系统上失败，退回复制 + 删源。
            source.copyTo(target, overwrite = true)
            source.delete()
            true
        }.getOrDefault(false)
    }

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
