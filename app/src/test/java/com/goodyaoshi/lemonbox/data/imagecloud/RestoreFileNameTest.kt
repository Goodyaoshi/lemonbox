package com.goodyaoshi.lemonbox.data.imagecloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 回填文件名推导（F20）。
 *
 * 这条推导决定「云端取回的图落到本地哪个文件」，写错的两种表现都很难从界面看出：
 * 取整串当文件名会带上斜杠、直接失败；取了会变的部分则同一张图每次落到不同文件，
 * 回填等于白做（每处显示仍要联网）。
 *
 * 因此把「末段即稳定标识」这条不变量单独锁住。纯函数，可直接在 JVM 跑。
 */
class RestoreFileNameTest {

    @Test
    fun takesLastSegmentSoPrefixDoesNotMatter() {
        // 同一个内容哈希，换前缀（甚至不配前缀）必须得到同一个文件名——
        // 否则同一张图会被重复回填成多份。
        assertEquals(
            restoreFileNameFor("lemonbox/images/abc123.jpg"),
            restoreFileNameFor("images/abc123.jpg")
        )
        assertEquals("abc123.jpg", restoreFileNameFor("images/abc123.jpg"))
    }

    @Test
    fun stripsNestedPrefixCompletely() {
        assertEquals("abc.jpg", restoreFileNameFor("a/b/c/images/abc.jpg"))
    }

    @Test
    fun toleratesSurroundingWhitespace() {
        assertEquals("abc.jpg", restoreFileNameFor("  images/abc.jpg  "))
    }

    @Test
    fun keyWithoutSlashIsUsedAsIs() {
        assertEquals("abc.jpg", restoreFileNameFor("abc.jpg"))
    }

    @Test
    fun blankOrEmptyKeysYieldNull() {
        // 返回 null 让调用方跳过：拿空名去当文件名会落到目录本身，属于误操作。
        assertNull(restoreFileNameFor(null))
        assertNull(restoreFileNameFor(""))
        assertNull(restoreFileNameFor("   "))
    }

    @Test
    fun trailingSlashYieldsNullRatherThanEmptyName() {
        assertNull(restoreFileNameFor("images/"))
    }

    @Test
    fun sameKeyAlwaysYieldsSameName() {
        // 纯函数应当确定性：同一输入重复调用结果一致。
        val key = "lemonbox/images/deadbeef.jpg"
        assertEquals(restoreFileNameFor(key), restoreFileNameFor(key))
    }
}
