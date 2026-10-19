package com.goodyaoshi.lemonbox.data.imagecloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对象键推导的不变量（F20）。
 *
 * 这是「避免重复迁移」赖以成立的根据，因此值得单独锁住：
 * **同一个内容哈希必须永远推导出同一个键**，无论来自哪台设备、哪一次同步。
 * 只要这条不变量成立，多设备各传各的也只会落到同一个对象上。
 */
class ImageObjectKeysTest {

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    @Test
    fun key_isDeterministicForSameContentHash() {
        // 同一台设备连算两次
        assertEquals(
            ImageObjectKeys.forContentHash("lemonbox", hashA),
            ImageObjectKeys.forContentHash("lemonbox", hashA)
        )
        // 模拟「两台设备」：调用方不同、但内容相同，键必须一致
        val deviceOne = ImageObjectKeys.forContentHash("lemonbox", hashA)
        val deviceTwo = ImageObjectKeys.forContentHash("lemonbox", hashA)
        assertEquals(deviceOne, deviceTwo)
    }

    @Test
    fun key_differsForDifferentContent() {
        assertNotEquals(
            ImageObjectKeys.forContentHash("lemonbox", hashA),
            ImageObjectKeys.forContentHash("lemonbox", hashB)
        )
    }

    @Test
    fun key_placesImageUnderFolder() {
        assertEquals("images/$hashA.jpg", ImageObjectKeys.forContentHash("", hashA))
    }

    @Test
    fun key_normalizesPrefixSlashes() {
        // 前缀前后带斜杠、或全是斜杠，都不该产生空路径段或双斜杠。
        val expected = "lemonbox/images/$hashA.jpg"
        assertEquals(expected, ImageObjectKeys.forContentHash("lemonbox", hashA))
        assertEquals(expected, ImageObjectKeys.forContentHash("lemonbox/", hashA))
        assertEquals(expected, ImageObjectKeys.forContentHash("/lemonbox", hashA))
        assertEquals(expected, ImageObjectKeys.forContentHash(" /lemonbox/ ", hashA))
        assertEquals("images/$hashA.jpg", ImageObjectKeys.forContentHash("/", hashA))
        assertEquals("images/$hashA.jpg", ImageObjectKeys.forContentHash("   ", hashA))
    }

    /** 全小写十六进制的哈希不会引入需要百分号编码的字符，键自然是「干净」的。 */
    @Test
    fun key_needsNoPercentEncodingForHexHash() {
        val key = ImageObjectKeys.forContentHash("lemonbox", hashA)
        assertTrue(key.all { it in "0123456789abcdefghijklmnopqrstuvwxyz/._" })
    }

    @Test
    fun key_isStableAcrossNestedPrefixSegments() {
        assertEquals(
            "team/lemonbox/images/$hashA.jpg",
            ImageObjectKeys.forContentHash("team/lemonbox", hashA)
        )
    }

    @Test
    fun folderConstantIsUsedByKeys() {
        assertTrue(ImageObjectKeys.forContentHash("", hashA).startsWith("${ImageObjectKeys.FOLDER}/"))
    }
}
