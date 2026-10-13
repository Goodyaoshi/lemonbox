package com.goodyaoshi.lemonbox.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片路径与云端对象键的**下标对齐**不变量（F20）。
 *
 * 这是本模块最需要守住的一条：图片是有序多张（第一张是主图、用户可拖动排序），
 * 而对象键必须跟着图片一起移动。一旦错位，界面上就会把 A 图的云端对象显示成 B 图——
 * 也就是说「看着是我的东西、显示的却是别人的图片」，属于最不能出的错。
 *
 * 因此这里把三条防线都测住：
 *  1. 编码保留空项（不做去空过滤），否则后面所有键都会前移；
 *  2. 解码一律补齐/截断到与图片等长；
 *  3. [Item.withImages] 是唯一的批量修改入口，两边永远同步。
 */
class ItemImageObjectKeysTest {

    private fun item(paths: String, keys: String?): Item =
        Item(id = 1, name = "测试物品", imagePaths = paths, imageObjectKeys = keys)

    // ---------- 编码 / 解码 ----------

    @Test
    fun encode_keepsBlankPlaceholders() {
        // 中间那张「还没上传」必须留下空位，否则第三张的键会前移到第二位。
        assertEquals("a\n\nb", Item.encodeImageObjectKeys(listOf("a", "", "b")))
    }

    @Test
    fun encode_trimsEachEntry() {
        assertEquals("a\nb", Item.encodeImageObjectKeys(listOf(" a ", "b ")))
    }

    @Test
    fun encode_allBlankKeepsLineCount() {
        // 三张都没上传 → 三行空串，而不是空字符串（长度信息必须留住）。
        assertEquals("\n\n", Item.encodeImageObjectKeys(listOf("", "", "")))
    }

    @Test
    fun decode_padsMissingEntriesToCount() {
        assertEquals(listOf("a", "", ""), Item.decodeImageObjectKeys("a", 3))
    }

    @Test
    fun decode_truncatesExtraEntriesToCount() {
        assertEquals(listOf("a", "b"), Item.decodeImageObjectKeys("a\nb\nc", 2))
    }

    @Test
    fun decode_nullEncoded_yieldsAllBlank() {
        assertEquals(listOf("", ""), Item.decodeImageObjectKeys(null, 2))
    }

    @Test
    fun decode_nonPositiveCount_yieldsEmpty() {
        assertTrue(Item.decodeImageObjectKeys("a\nb", 0).isEmpty())
        assertTrue(Item.decodeImageObjectKeys("a\nb", -1).isEmpty())
    }

    // ---------- 下标访问 ----------

    @Test
    fun objectKeyList_isAlwaysSameLengthAsImagePaths() {
        val subject = item(paths = "p1\np2\np3", keys = "k1\nk2")

        assertEquals(subject.imagePathList().size, subject.imageObjectKeyList().size)
        assertEquals(listOf("k1", "k2", ""), subject.imageObjectKeyList())
    }

    @Test
    fun objectKeyAt_returnsNullForBlankEntryOrOutOfRange() {
        val subject = item(paths = "p1\np2", keys = "k1\n")

        assertEquals("k1", subject.imageObjectKeyAt(0))
        assertNull(subject.imageObjectKeyAt(1))
        assertNull(subject.imageObjectKeyAt(9))
    }

    /** 历史数据（升级前保存的物品）没有任何键，必须按「全部未上传」处理而不是崩掉。 */
    @Test
    fun legacyItemWithoutKeys_isTreatedAsFullyUploadedPending() {
        val subject = item(paths = "p1\np2", keys = null)

        assertEquals(2, subject.imageObjectKeyList().size)
        assertTrue(subject.imageObjectKeyList().all { it.isEmpty() })
        assertNull(subject.imageObjectKeyAt(0))
    }

    @Test
    fun noImages_yieldsEmptyKeyList() {
        val subject = item(paths = "", keys = null)

        assertTrue(subject.imageObjectKeyList().isEmpty())
        assertNull(subject.imageObjectKeyAt(0))
    }

    // ---------- withImages：唯一的批量修改入口 ----------

    @Test
    fun withImages_reordersBothListsTogether() {
        val subject = item(paths = "p1\np2", keys = "k1\nk2")

        val updated = subject.withImages(paths = listOf("p2", "p1"), objectKeys = listOf("k2", "k1"))

        assertEquals(listOf("p2", "p1"), updated.imagePathList())
        assertEquals(listOf("k2", "k1"), updated.imageObjectKeyList())
        // 第一张是主图，legacy 字段要跟着走。
        assertEquals("p2", updated.imagePath)
    }

    @Test
    fun withImages_padsKeysWhenCallerSuppliesFewer() {
        val subject = item(paths = "p1", keys = "k1")

        val updated = subject.withImages(paths = listOf("p1", "p2"), objectKeys = listOf("k1"))

        assertEquals(listOf("k1", ""), updated.imageObjectKeyList())
    }

    @Test
    fun withImages_dropsKeysWhenImagesRemoved() {
        val subject = item(paths = "p1\np2\np3", keys = "k1\nk2\nk3")

        val updated = subject.withImages(paths = listOf("p1", "p3"), objectKeys = listOf("k1", "k3"))

        assertEquals(listOf("p1", "p3"), updated.imagePathList())
        assertEquals(listOf("k1", "k3"), updated.imageObjectKeyList())
        assertEquals("p1", updated.imagePath)
    }

    @Test
    fun withImages_ignoresSurplusKeysBeyondImageCount() {
        val subject = item(paths = "p1", keys = "k1")

        val updated = subject.withImages(
            paths = listOf("p1"),
            objectKeys = listOf("k1", "k2", "k3")
        )

        // 键比图多时以图为准，绝不能让多余的键挤进列表。
        assertEquals(listOf("k1"), updated.imageObjectKeyList())
    }

    @Test
    fun withImages_clearingImagesAlsoClearsKeysAndPrimary() {
        val subject = item(paths = "p1", keys = "k1")

        val updated = subject.withImages(paths = emptyList(), objectKeys = emptyList())

        assertTrue(updated.imagePathList().isEmpty())
        assertTrue(updated.imageObjectKeyList().isEmpty())
        assertEquals("", updated.imagePath)
    }

    /** 往返编码不应改变任何键，包括空位——否则「只是读一遍」也会让下标错位。 */
    @Test
    fun roundTripThroughEncodingPreservesAllEntries() {
        val keys = listOf("k1", "", "k3", "")
        val encoded = Item.encodeImageObjectKeys(keys)
        val decoded = Item.decodeImageObjectKeys(encoded, keys.size)

        assertEquals(keys, decoded)
    }
}
