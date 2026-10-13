package com.goodyaoshi.lemonbox.data.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * [TarExtractor] 的纯 JVM 单测：手工构造 tar 字节流，
 * 覆盖常规文件提取、目录条目跳过、ustar 前缀拼接、块对齐 padding 与缺失条目报错。
 */
class TarExtractorTest {

    @Test
    fun `提取常规文件条目并跳过目录条目`() {
        val content = ByteArray(123_457) { (it % 251).toByte() } // 非整块大小，验证 padding
        val dirHeader = tarHeader("PP-OCRv6_tiny_det_onnx_infer/", 0, type = TYPE_DIR)
        val fileHeader = tarHeader("PP-OCRv6_tiny_det_onnx_infer/inference.onnx", content.size.toLong())
        val tar = tarBlock(dirHeader) + tarBlock(fileHeader, content) + eofBlocks()

        val target = newTarget()
        TarExtractor.extract(ByteArrayInputStream(tar), "PP-OCRv6_tiny_det_onnx_infer/inference.onnx", target)

        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `支持 ustar 前缀拼接条目名`() {
        val content = "onnx-bytes".toByteArray(Charsets.UTF_8)
        val fileHeader = tarHeader(
            name = "inference.onnx",
            size = content.size.toLong(),
            prefix = "PP-OCRv6_small_rec_onnx_infer"
        )
        val tar = tarBlock(fileHeader, content) + eofBlocks()

        val target = newTarget()
        TarExtractor.extract(ByteArrayInputStream(tar), "PP-OCRv6_small_rec_onnx_infer/inference.onnx", target)

        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `连续多文件时能精确提取目标条目`() {
        val first = "first-entry".toByteArray(Charsets.UTF_8)
        val second = ByteArray(1024) { 7 }
        val tar =
            tarBlock(tarHeader("dir/first.txt", first.size.toLong()), first) +
                tarBlock(tarHeader("dir/second.bin", second.size.toLong()), second) +
                eofBlocks()

        val target = newTarget()
        TarExtractor.extract(ByteArrayInputStream(tar), "dir/second.bin", target)

        assertArrayEquals(second, target.readBytes())
    }

    @Test
    fun `缺少目标条目时抛出 IOException`() {
        val content = "other".toByteArray(Charsets.UTF_8)
        val tar = tarBlock(tarHeader("dir/other.txt", content.size.toLong()), content) + eofBlocks()

        val target = newTarget()
        val thrown = runCatching {
            TarExtractor.extract(ByteArrayInputStream(tar), "dir/missing.onnx", target)
        }.exceptionOrNull()

        assertTrue("应抛出 IOException", thrown is IOException)
    }

    // ---- 构造工具 ----

    private val TYPE_DIR: Byte = '5'.code.toByte()

    private fun newTarget(): File =
        File.createTempFile("tar-extract", ".tmp").also { it.delete() }

    /** 组一个 512 字节的 ustar 头：只填解析器关心的 name / size / typeflag / prefix。 */
    private fun tarHeader(name: String, size: Long, type: Byte = '0'.code.toByte(), prefix: String = ""): ByteArray {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        check(nameBytes.size <= 100) { "测试用的条目名不应超过 100 字节" }
        nameBytes.copyInto(header)
        if (prefix.isNotEmpty()) {
            val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
            check(prefixBytes.size <= 155)
            prefixBytes.copyInto(header, 345)
        }
        size.toString(8).padStart(11, '0').toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        header[156] = type
        return header
    }

    /** 条目 = 512 字节头 + 数据块（补齐到 512 倍数）。 */
    private fun tarBlock(header: ByteArray, content: ByteArray = ByteArray(0)): ByteArray {
        val padding = ((512 - content.size % 512) % 512)
        return header + content + ByteArray(padding)
    }

    /** tar 结束标志：两个全零块。 */
    private fun eofBlocks(): ByteArray = ByteArray(1024)
}
