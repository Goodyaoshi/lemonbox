package com.goodyaoshi.lemonbox.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片槽位（内容哈希列）的编解码不变量（F22）。
 *
 * 这是本模块最需要守住的一条：图片是**有序多张**（第一张是主图、用户可拖动排序）。
 * 顺序一旦丢失或错位，界面就会把 A 图当作主图——「看着是我的东西、显示的却是另一张」。
 *
 * 因此把三条防线测住：
 *  1. 编码保留顺序（首项即主图）；
 *  2. 往返编解码不改变任何一项；
 *  3. [Item.withImageHashes] 是唯一的批量修改入口。
 */
class ItemImageHashesTest {

    private fun item(raw: String?): Item = Item(id = 1, name = "测试物品", imageHashes = raw)

    // ---------- 编码 / 解码 ----------

    @Test
    fun encode_preservesOrder() {
        assertEquals("a\nb\nc", Item.encodeImageHashes(listOf("a", "b", "c")))
    }

    @Test
    fun encode_trimsEachEntry() {
        assertEquals("a\nb", Item.encodeImageHashes(listOf(" a ", "b ")))
    }

    /**
     * 空项不占位（与早先的「空行占位」相反）。
     *
     * 因为哈希就是槽位本身：没有哈希就没有这张图，留空行只会造出一个取不到图的空槽位。
     */
    @Test
    fun encode_dropsBlankEntriesInsteadOfKeepingPlaceholders() {
        assertEquals("a\nb", Item.encodeImageHashes(listOf("a", "", "b")))
    }

    @Test
    fun encode_emptyListYieldsNull() {
        // 没有图就不该在库里留一个空字符串，null 才是「没有槽位」。
        assertNull(Item.encodeImageHashes(emptyList()))
        assertNull(Item.encodeImageHashes(listOf("", "  ")))
    }

    @Test
    fun decode_nullYieldsEmptyList() {
        assertTrue(Item.decodeImageHashes(null).isEmpty())
    }

    @Test
    fun decode_ignoresBlankLines() {
        assertEquals(listOf("a", "b"), Item.decodeImageHashes("a\n\nb"))
    }

    // ---------- 下标访问 ----------

    @Test
    fun hashAt_returnsNullForOutOfRange() {
        val subject = item("h1\nh2")

        assertEquals("h1", subject.imageHashAt(0))
        assertEquals("h2", subject.imageHashAt(1))
        assertNull(subject.imageHashAt(2))
        assertNull(subject.imageHashAt(9))
    }

    @Test
    fun primaryImageHash_isTheFirstEntry() {
        assertEquals("h1", item("h1\nh2").primaryImageHash())
    }

    /** 历史数据（本方案落地前保存的物品）没有哈希列，必须按「没有图」处理而不是崩掉。 */
    @Test
    fun legacyItemWithoutHashes_isTreatedAsNoImages() {
        val subject = item(null)

        assertTrue(subject.imageHashList().isEmpty())
        assertNull(subject.primaryImageHash())
    }

    @Test
    fun noImages_yieldsEmptyList() {
        assertTrue(item("").imageHashList().isEmpty())
    }

    // ---------- withImageHashes：唯一的批量修改入口 ----------

    @Test
    fun withImageHashes_reordersAndKeepsPrimary() {
        val updated = item("h1\nh2").withImageHashes(listOf("h2", "h1"))

        assertEquals(listOf("h2", "h1"), updated.imageHashList())
        assertEquals("h2", updated.primaryImageHash())
    }

    @Test
    fun withImageHashes_replacesWholeList() {
        val updated = item("h1\nh2\nh3").withImageHashes(listOf("h1", "h3"))

        assertEquals(listOf("h1", "h3"), updated.imageHashList())
    }

    @Test
    fun withImageHashes_clearingAlsoClearsPrimary() {
        val updated = item("h1").withImageHashes(emptyList())

        assertTrue(updated.imageHashList().isEmpty())
        assertNull(updated.primaryImageHash())
    }

    /** 往返一遍不应改变任何一项，包括顺序——否则「只是读一遍」也会让主图漂移。 */
    @Test
    fun roundTripThroughEncodingPreservesEverything() {
        val hashes = listOf("aaa", "bbb", "ccc")
        val decoded = Item.decodeImageHashes(Item.encodeImageHashes(hashes))

        assertEquals(hashes, decoded)
    }
}
