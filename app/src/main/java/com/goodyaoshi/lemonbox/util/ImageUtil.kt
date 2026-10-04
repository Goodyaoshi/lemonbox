package com.goodyaoshi.lemonbox.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
            val imagesDir = File(context.filesDir, "images")
            if (!imagesDir.exists()) imagesDir.mkdirs()

            val fileName = "item_${UUID.randomUUID()}.jpg"
            val file = File(imagesDir, fileName)

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

    fun copyImageInto(context: Context, sourcePath: String, targetName: String): String? {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return null
        return try {
            val imagesDir = File(context.filesDir, "images")
            if (!imagesDir.exists()) imagesDir.mkdirs()
            val targetFile = File(imagesDir, targetName)
            sourceFile.copyTo(targetFile, overwrite = true)
            targetFile.absolutePath
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
