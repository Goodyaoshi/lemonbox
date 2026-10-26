package com.goodyaoshi.lemonbox.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

object ImageUtil {

    /**
     * 把选中的图片压成 JPEG 落到本地图片目录，返回其路径。
     *
     * 这份是本机**上传前**的副本：上传成功、云端确认可读后会被删除（见
     * [com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncManager]），
     * 因此不会长期占用空间。用 `filesDir` 而非 `cacheDir` 的理由见 [workingImagesDir]。
     */
    fun saveImageToWorkingDir(context: Context, uri: Uri): String? {
        return saveImage(
            context = context,
            inputStreamProvider = { context.contentResolver.openInputStream(uri) }
        )
    }

    fun saveImageToWorkingDir(context: Context, file: File): String? {
        return saveImage(
            context = context,
            inputStreamProvider = { file.inputStream() }
        )
    }

    /**
     * 落盘图片并返回其**内容哈希**（F22）——这正是图片槽位要存的值。
     *
     * 落盘名本就由哈希决定，所以这里只是把它取出来交给调用方；
     * 万一文件名不符预期（理论不会），回退读盘算一次，绝不返回一个取不到图的标识。
     */
    fun saveImageHash(context: Context, uri: Uri): String? =
        saveImageToWorkingDir(context, uri)?.let { path ->
            val file = File(path)
            hashFromFileName(file.name) ?: contentHash(file)
        }

    /**
     * 落盘并返回最终路径（F22）。
     *
     * 命名规则改成**按内容哈希**：先压到临时文件，算出内容的 SHA-256，再落到
     * `item_<hash>.jpg`。同一份内容无论如何重选、重拍、从哪台设备导入，都落到同一个文件。
     *
     * 为什么必须这样：此前用 UUID 命名，同一张图重选一次就多一个文件；而同步侧按内容哈希
     * 归并、只保留一个代表去删，其余文件成了永不消失的残件——每次同步都被重新扫到、重算哈希，
     * 表现就是「图都传上去了，本地却一直在、还每次都要过一遍」。按内容命名后同内容只有一个文件，
     * 既不产生残件，对象键也能直接由文件名得出，同步不必再读盘算哈希。
     */
    private fun saveImage(
        context: Context,
        inputStreamProvider: () -> InputStream?
    ): String? {
        return try {
            val source = inputStreamProvider()?.use { stream ->
                BitmapFactory.decodeStream(stream) ?: return null
            } ?: return null
            // BitmapFactory 不读 EXIF：横拍的图直接压回去会整体躺倒，展示与文字识别都歪。
            // 落盘前先把方向烘进像素；本应用产出的 JPEG 不再携带 EXIF，后续链路所见即所得。
            val degrees = readExifRotation(inputStreamProvider)
            val oriented = applyRotation(source, degrees)
            if (oriented !== source) source.recycle()
            val path = saveBitmapByContent(context, oriented)
            oriented.recycle()
            path
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 把图片顺时针旋转 90° 后按内容另存，返回新路径；失败返回 null。
     *
     * 这是给用户的「自行选择旋转」兜底：自动方向判断（EXIF / 持机姿态）覆盖不到的场景，
     * 让用户在界面上手动转到正为止。源文件一律不动——外部 content:// 不属于本应用，
     * 本应用暂存文件是否清理由调用方决定（[deleteStagedCapture]）。
     */
    fun rotateImageByContent(context: Context, uri: Uri): String? {
        return try {
            val source = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return null
            // 源自带 EXIF 时先烘正再转，保证转的是用户「看到的」方向，而不是传感器原始方向。
            val degrees = readExifRotation { context.contentResolver.openInputStream(uri) }
            val oriented = applyRotation(source, degrees)
            if (oriented !== source) source.recycle()
            val rotated = applyRotation(oriented, 90)
            if (rotated !== oriented) oriented.recycle()
            val path = saveBitmapByContent(context, rotated)
            rotated.recycle()
            path
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 清理本应用暂存的拍照 / 旋转产物（filesDir/images 下的 file:// 源）。
     * 相册等外部内容源不属于本应用，一律不动。
     */
    fun deleteStagedCapture(context: Context, uri: Uri) {
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        if (File(path).absolutePath.startsWith(workingImagesDir(context).absolutePath)) {
            deleteImage(path)
        }
    }

    /** 读取图片的 EXIF 方向并换算为旋转角；读不到或异常时按 0 处理（维持原状）。 */
    private fun readExifRotation(inputStreamProvider: () -> InputStream?): Int {
        val orientation = try {
            inputStreamProvider()?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (_: Exception) {
            null
        } ?: ExifInterface.ORIENTATION_NORMAL
        return exifRotationDegrees(orientation)
    }

    /** EXIF 方向标记 → 顺时针旋转角。TRANSPOSE/TRANSVERSE 还含镜像，手机相机基本不产出，只取旋转分量。 */
    private fun exifRotationDegrees(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }

    /** 把旋转角烘进位图；角度为 0 时原样返回（调用方据此判断要不要 recycle 旧图）。 */
    private fun applyRotation(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 把位图压成 JPEG 并按内容哈希落盘（`item_<sha256>.jpg`），返回路径。
     *
     * 命名即内容指纹：同一份内容无论如何重选、重拍、从哪台设备导入，都落到同一个文件，
     * 同步侧才不会留下永不清理的残件（背景见 [saveImageToWorkingDir]）。
     */
    private fun saveBitmapByContent(context: Context, bitmap: Bitmap): String? {
        var staging: File? = null
        return try {
            val file = File(workingImagesDir(context), "tmp_${UUID.randomUUID()}.jpg")
            staging = file
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            val hash = contentHash(file) ?: return null
            val target = File(workingImagesDir(context), "$FILE_PREFIX$hash$FILE_SUFFIX")
            if (target.isFile) {
                // 本地已有同一张图：丢弃刚压好的临时件，复用既有文件。
                file.delete()
                target.absolutePath
            } else if (file.renameTo(target)) {
                staging = null
                target.absolutePath
            } else {
                // renameTo 跨文件系统/被占用时可能失败，退回复制后删除临时件。
                file.copyTo(target, overwrite = true)
                file.delete()
                target.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            // 任何失败路径都不留下临时件（成功时已置空或已删除）。
            staging?.delete()
        }
    }

    /**
     * 本地图片的中转目录（`filesDir` 下），不存在时创建。
     *
     * 为什么用 `filesDir` 而不是 `cacheDir`：图片上传成功之前，本地这份是它**唯一的副本**，
     * 而 `cacheDir` 会被系统在空间紧张时回收——那样这张图就永远传不上去了。
     * 上传成功后 [com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncManager] 会把它
     * 挪进回填缓存（cloud_restore）继续供展示离线使用，所以这里不会长期留存。
     * （`filesDir` 已在 backup_rules / data_extraction_rules 中整体排除，不会随系统备份外流。）
     */
    internal fun workingImagesDir(context: Context): File =
        File(context.filesDir, "images").apply { if (!exists()) mkdirs() }

    /**
     * 该字符串是否是一个内容的 SHA-256 十六进制表示（64 位、全小写十六进制）。
     *
     * 用于区分「槽位里存的是内容哈希」与「历史数据残留的旧文件名」——迁移时若旧图
     * 是 UUID 命名、解析不出哈希，槽位值会保留原文件名，调用方据此决定要不要读盘重算。
     */
    fun isContentHash(value: String): Boolean =
        value.length == SHA256_HEX_LENGTH && value.all { c -> c.isDigit() || c in 'a'..'f' }

    /**
     * 从落盘文件名解析内容哈希（F22）：`item_<sha256>.jpg` → `<sha256>`。
     *
     * 有了它，同步侧不必再为每张图读盘算 SHA-256——文件名本身就是哈希。
     * 返回 null 表示不是新格式（历史 UUID 命名或临时件），调用方回退到读盘计算，
     * 这样旧数据仍然能同步，不需要一次性迁移。
     */
    fun hashFromFileName(fileName: String): String? {
        if (!fileName.startsWith(FILE_PREFIX) || !fileName.endsWith(FILE_SUFFIX)) return null
        val hash = fileName.substring(FILE_PREFIX.length, fileName.length - FILE_SUFFIX.length)
        // 严格的 SHA-256 十六进制：避免把别的文件误判成新格式。
        return hash.takeIf { isContentHash(it) }
    }

    /**
     * 找出某个图片槽位对应的本地文件（F22）。
     *
     * 两级查找，第二级只为本方案落地前的历史数据而存在：
     *  1. 槽位是内容哈希 → `item_<hash>.jpg`（当前落盘格式）；
     *  2. 槽位是旧文件名（迁移保留的 UUID 命名）→ 退回该文件原名。
     * 都没有则返回 null，调用方按「本地已无此图」处理（它可能只在云端）。
     */
    fun findLocalFile(context: Context, slot: String): File? {
        if (slot.isBlank()) return null
        if (isContentHash(slot)) {
            val byHash = File(localPathForHash(context, slot))
            if (byHash.isFile) return byHash
        }
        return File(workingImagesDir(context), slot).takeIf { it.isFile }
    }

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
     * 与 [saveImageToWorkingDir] 的 UUID 命名不同，这里存在即复用：
     * 导入备份时若本地已有同一张图，就不会再复制一份（导入自己的备份曾使本地图片翻倍）。
     *
     * [hash] 由调用方传入，避免同一文件被重复读盘计算。
     */
    fun saveImageByContent(context: Context, sourceFile: File, hash: String): String? {
        return try {
            val target = File(workingImagesDir(context), "$FILE_PREFIX$hash$FILE_SUFFIX")
            // 已有同内容：直接复用，不复制、不覆盖。
            if (target.isFile) return target.absolutePath
            sourceFile.copyTo(target, overwrite = true)
            target.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 内容命名前缀：`item_<sha256>.jpg`（见 [hashFromFileName]）。 */
    private const val FILE_PREFIX = "item_"

    private const val FILE_SUFFIX = ".jpg"

    private const val SHA256_HEX_LENGTH = 64

    /**
     * 由内容哈希推导本地副本路径（F22）。
     *
     * 落盘统一命名为 `item_<hash>.jpg`（见 [saveImageToWorkingDir]），因此「这张图在本地的
     * 哪个文件」完全由哈希决定，不必单独存一列路径——这正是 `imageHashes` 一个字段
     * 就能取代原先「本地路径 + 对象键」三列的原因。
     *
     * 只做路径拼接，不判断文件是否存在：调用方关心的是「本地有没有」，
     * 而写库时也可能需要这个路径去登记一个尚未落盘的槽位。
     */
    fun localPathForHash(context: Context, hash: String): String =
        File(workingImagesDir(context), "$FILE_PREFIX${hash.trim()}$FILE_SUFFIX").absolutePath

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

            val decoded = BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            ) ?: return null
            // 历史落盘 / 外部来源的文件可能仍带 EXIF：识别与展示一样，先把方向烘正，
            // 否则横拍的小票在识别侧就是躺倒的，模型认不出文字。
            val orientation = try {
                ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val oriented = applyRotation(decoded, exifRotationDegrees(orientation))
            if (oriented !== decoded) decoded.recycle()
            oriented
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
