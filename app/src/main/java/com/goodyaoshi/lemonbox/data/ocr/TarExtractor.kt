package com.goodyaoshi.lemonbox.data.ocr

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 极简 tar 提取器：按精确路径提取常规文件条目，供模型分发包解压使用。
 *
 * 只需覆盖 Paddle 官方发布 tar 的特性：512 字节 ustar 头、条目名不超过 100 字符
 * （可带 ustar 前缀）、内容为常规文件与目录条目；pax 扩展头与 GNU 长名条目
 * 的名字对本文场景无意义，作为普通条目跳过其数据块即可。
 */
object TarExtractor {

    /** 从 [input] 中提取名为 [entryName] 的文件条目并写入 [target]；找不到时抛 [IOException]。 */
    fun extract(input: InputStream, entryName: String, target: File) {
        val header = ByteArray(BLOCK_SIZE)
        while (true) {
            if (!readFully(input, header)) throw IOException("tar 包内没有条目 $entryName")
            if (header.all { it.toInt() == 0 }) throw IOException("tar 包内没有条目 $entryName")

            val name = readName(header)
            val size = readSize(header)
            val dataBytes = (size + BLOCK_SIZE - 1) / BLOCK_SIZE * BLOCK_SIZE

            if (isRegularFile(header) && name == entryName) {
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    copyExactly(input, output, size)
                }
                skipExactly(input, dataBytes - size)
                return
            }
            skipExactly(input, dataBytes)
        }
    }

    /** 是否为常规文件条目：typeflag 为 '0' 或 NUL（部分打包工具省略 typeflag）。 */
    private fun isRegularFile(header: ByteArray): Boolean {
        val type = header[TYPEFLAG_OFFSET].toInt()
        return type == '0'.code || type == 0
    }

    /** 条目名：ustar 前缀（345..499）存在时以 `/` 拼接在名字之前。 */
    private fun readName(header: ByteArray): String {
        val prefix = readString(header, PREFIX_OFFSET, PREFIX_LENGTH)
        val name = readString(header, NAME_OFFSET, NAME_LENGTH)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end].toInt() != 0) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    /**
     * 条目大小（124..135）：八进制 ASCII；GNU 扩展的大文件用 base-256 编码
     * （首字节最高位为 1），一并支持以增强兼容性。
     */
    private fun readSize(header: ByteArray): Long {
        if (header[SIZE_OFFSET].toInt() and 0x80 != 0) {
            var value = header[SIZE_OFFSET].toLong() and 0x7F
            for (i in SIZE_OFFSET + 1 until SIZE_OFFSET + SIZE_LENGTH) {
                value = (value shl 8) or (header[i].toLong() and 0xFF)
            }
            return value
        }
        var value = 0L
        for (i in SIZE_OFFSET until SIZE_OFFSET + SIZE_LENGTH) {
            val b = header[i].toInt()
            if (b == 0 || b == ' '.code) break
            value = value * 8 + (b - '0'.code)
        }
        return value
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return offset == 0 // 流在块边界正常结束返回 false，读到半块算损坏
            offset += read
        }
        return true
    }

    private fun copyExactly(input: InputStream, target: java.io.OutputStream, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("tar 条目数据提前结束")
            target.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipExactly(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) throw IOException("tar 数据不完整")
            remaining -= skipped
        }
    }

    private const val BLOCK_SIZE = 512
    private const val NAME_OFFSET = 0
    private const val NAME_LENGTH = 100
    private const val SIZE_OFFSET = 124
    private const val SIZE_LENGTH = 12
    private const val TYPEFLAG_OFFSET = 156
    private const val PREFIX_OFFSET = 345
    private const val PREFIX_LENGTH = 155
}
