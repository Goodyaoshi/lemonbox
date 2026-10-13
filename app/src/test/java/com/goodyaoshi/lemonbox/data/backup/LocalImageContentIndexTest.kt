package com.goodyaoshi.lemonbox.data.backup

import com.goodyaoshi.lemonbox.util.ImageUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 导入去重的本地内容索引（F20）。
 *
 * 它决定「备份里的这张图，本地是不是已经有了」。判错的两种后果都不轻：
 * - 该复用却去复制 → 导入自己的备份就让本地图片翻倍（这正是要修的问题）；
 * - 不该复用却复用 → 把别的图当成同一张，直接显示成错的图片。
 *
 * 测试严格还原真实布局：**索引只扫本地图片目录**（`filesDir/images`），
 * 而被比对的图来自备份解压目录，两者不是一个地方——
 * 若图省事放同一个目录，被测文件会在索引里找到自己，断言就没有意义了。
 *
 * 索引只依赖文件系统与内容哈希，不碰 Android 框架，可直接在 JVM 跑。
 */
class LocalImageContentIndexTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 本地图片目录（索引扫描对象）。 */
    private fun imagesDir(): File = temp.newFolder("images")

    /** 备份解压目录（待导入图片的来源），与图片目录刻意分开。 */
    private fun sourceDir(): File = temp.newFolder("source")

    /** 可复现的内容：同样入参必得同样字节，便于构造「同内容」「不同内容」。 */
    private fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it + seed) % 251).toByte() }

    // ---------- 命中：本地已有同一张 ----------

    @Test
    fun findsLocalFileWithSameContent() {
        val local = imagesDir()
        // 历史遗留的 UUID 命名文件，应与新文件按内容判为同一张、被复用。
        val existing = File(local, "item_11111111-2222-3333-4444-555555555555.jpg")
            .apply { writeBytes(bytes(2048, seed = 1)) }
        val incoming = File(sourceDir(), "backup_img.jpg")
            .apply { writeBytes(bytes(2048, seed = 1)) }

        val index = LocalImageContentIndex(local)

        assertEquals(
            existing.absolutePath,
            index.findByHash(requireNotNull(ImageUtil.contentHash(incoming)), incoming.length())
        )
    }

    // ---------- 未命中：本地确实没有 ----------

    @Test
    fun returnsNullWhenLocalContentIsDifferent() {
        val local = imagesDir()
        File(local, "item_old.jpg").writeBytes(bytes(2048, seed = 1))
        val incoming = File(sourceDir(), "backup_img.jpg").apply { writeBytes(bytes(2048, seed = 2)) }

        val index = LocalImageContentIndex(local)

        assertNull(
            index.findByHash(requireNotNull(ImageUtil.contentHash(incoming)), incoming.length())
        )
    }

    /**
     * 关键回归：按大小预筛只能用来「缩小候选」，绝不能当成命中依据——
     * 大小相同而内容不同必须判为不同，否则会把两张不同的图混成一张。
     */
    @Test
    fun sameSizeButDifferentContentIsNotMatched() {
        val local = imagesDir()
        File(local, "item_same_size.jpg").writeBytes(bytes(1024, seed = 1))
        val incoming = File(sourceDir(), "backup_img.jpg").apply { writeBytes(bytes(1024, seed = 2)) }

        val index = LocalImageContentIndex(local)

        assertNull(
            index.findByHash(requireNotNull(ImageUtil.contentHash(incoming)), incoming.length())
        )
    }

    // ---------- register：本次导入刚落盘的图 ----------

    @Test
    fun registeredFileBecomesFindable() {
        val local = imagesDir()
        // 先建索引（此刻目录为空），再落盘新文件——模拟导入过程中的真实时序。
        val index = LocalImageContentIndex(local)
        val created = File(local, "item_new.jpg").apply { writeBytes(bytes(100, seed = 3)) }
        val hash = requireNotNull(ImageUtil.contentHash(created))

        // 索引建立后新增的文件它并不知道，此时不应命中。
        assertNull(index.findByHash(hash, created.length()))

        index.register(hash, created.absolutePath)

        // 登记后，同一个备份里再出现同一张图就能复用同一文件。
        assertEquals(created.absolutePath, index.findByHash(hash, created.length()))
    }

    @Test
    fun registerIsIdempotent() {
        val local = imagesDir()
        val index = LocalImageContentIndex(local)
        val created = File(local, "item_new.jpg").apply { writeBytes(bytes(64, seed = 4)) }
        val hash = requireNotNull(ImageUtil.contentHash(created))

        index.register(hash, created.absolutePath)
        index.register(hash, created.absolutePath)

        assertEquals(created.absolutePath, index.findByHash(hash, created.length()))
    }

    // ---------- 边界 ----------

    /** 只认文件：目录里混入子目录不应干扰索引。 */
    @Test
    fun ignoresSubdirectories() {
        val local = imagesDir()
        File(local, "sub").mkdirs()

        val index = LocalImageContentIndex(local)

        assertNull(index.findByHash("deadbeef", 0))
    }

    @Test
    fun emptyDirectoryYieldsNoMatch() {
        val index = LocalImageContentIndex(imagesDir())

        assertNull(index.findByHash("anything", 123))
    }

    /** 大小为 0 的空文件也不该被凭空命中。 */
    @Test
    fun zeroByteFileDoesNotMatchArbitraryHash() {
        val local = imagesDir()
        File(local, "empty.jpg").writeBytes(ByteArray(0))

        val index = LocalImageContentIndex(local)

        assertNull(index.findByHash("not-a-real-hash", 0))
    }
}
