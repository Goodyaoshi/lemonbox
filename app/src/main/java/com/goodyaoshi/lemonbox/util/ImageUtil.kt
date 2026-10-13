package com.goodyaoshi.lemonbox.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

object ImageUtil {

    fun saveImageToInternal(context: Context, uri: Uri): String? {
        return saveImageToInternal(
            context = context,
            inputStreamProvider = { context.contentResolver.openInputStream(uri) }
        )
    }

    fun saveImageToInternal(context: Context, file: File): String? {
        return saveImageToInternal(
            context = context,
            inputStreamProvider = { file.inputStream() }
        )
    }

    private fun saveImageToInternal(
        context: Context,
        inputStreamProvider: () -> InputStream?
    ): String? {
        return try {
            val inputStream = inputStreamProvider() ?: return null
            val file = File(imagesDir(context), "item_${UUID.randomUUID()}.jpg")

            BitmapFactory.decodeStream(inputStream)?.let { bitmap ->
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                inputStream.close()
                file.absolutePath
            } ?: run {
                inputStream.close()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun ensureImagePaths(context: Context, paths: List<String>): List<String> {
        return paths.mapNotNull { path ->
            when {
                path.startsWith("content://") -> saveImageToInternal(context, path.toUri())
                path.startsWith("file://") -> saveImageToInternal(context, path.toUri())
                File(path).exists() -> path
                else -> null
            }
        }.distinct()
    }

    /** 图片落盘目录（内部存储），不存在时创建。 */
    internal fun imagesDir(context: Context): File =
        File(context.filesDir, "images").apply { if (!exists()) mkdirs() }

    /**
     * 文件内容的 SHA-256（小写十六进制）；读取失败返回 null。
     *
     * 「是不是同一张图」一律以此判定，而不是文件名或路径：
     * 云端对象键、导入去重都靠它，因而同一份内容在各处推导出的标识必然一致。
     */
    fun contentHash(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * 按内容落盘：文件名取内容的 SHA-256，**同一内容永远落到同一个文件**。
     *
     * 与 [saveImageToInternal] 的 UUID 命名不同，这里存在即复用：
     * 导入备份时若本地已有同一张图，就不会再复制一份（导入自己的备份曾使本地图片翻倍）。
     *
     * [hash] 由调用方传入，避免同一文件被重复读盘计算。
     */
    fun saveImageByContent(context: Context, sourceFile: File, hash: String): String? {
        return try {
            val target = File(imagesDir(context), "item_$hash.jpg")
            // 已有同内容：直接复用，不复制、不覆盖。
            if (target.isFile) return target.absolutePath
            sourceFile.copyTo(target, overwrite = true)
            target.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteImage(path: String) {
        try {
            val file = File(path)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 按最长边降采样解码，避免把整张原图读进内存。
     * 用于本地模型推理前的预处理，调用方负责在结束后 recycle 返回的 Bitmap。
     */
    fun decodeDownsampledBitmap(path: String, maxSize: Int): Bitmap? {
        if (!File(path).exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (longestSide / sampleSize > maxSize) {
                sampleSize *= 2
            }

            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
