package com.goodyaoshi.lemonbox.data.sync

import com.goodyaoshi.lemonbox.data.imagecloud.S3ObjectEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本轮该拉哪些快照」的筛选规则。
 *
 * 这一段决定哪些数据会被看到：漏掉一份不报错，只是数据静默缺席，
 * 只有把规则写成用例才容易发现回归。
 */
class SelectFreshRevisionsTest {

    private fun entry(key: String, at: Long): S3ObjectEntry =
        S3ObjectEntry(key = key, lastModified = at, size = 100)

    @Test
    fun keepsOnlyRevisionKeys() {
        val objects = listOf(
            entry("sync/rev-devB-1.zip", 200),
            entry("sync/base-1.zip", 250),   // 非 rev，应被滤掉
            entry("images/abc.jpg", 300)     // 非快照，应被滤掉
        )

        val result = selectFreshRevisions(objects, deviceId = "devA", watermark = 0)

        assertEquals(1, result.size)
        assertEquals("sync/rev-devB-1.zip", result[0].key)
    }

    @Test
    fun skipsOwnSnapshots() {
        val objects = listOf(
            entry("sync/rev-devA-1.zip", 200),
            entry("sync/rev-devB-2.zip", 300)
        )

        val result = selectFreshRevisions(objects, deviceId = "devA", watermark = 0)

        assertEquals(1, result.size)
        assertEquals("sync/rev-devB-2.zip", result[0].key)
    }

    @Test
    fun filtersByWatermarkExclusively() {
        val objects = listOf(
            entry("sync/rev-devB-1.zip", 100),
            entry("sync/rev-devB-2.zip", 200),
            entry("sync/rev-devB-3.zip", 300)
        )

        val result = selectFreshRevisions(objects, deviceId = "devA", watermark = 200)

        // 严格大于水位线：200 那份已经合并过了，不该重复处理。
        assertEquals(1, result.size)
        assertEquals("sync/rev-devB-3.zip", result[0].key)
    }

    @Test
    fun sortsAscendingByServerTime() {
        val objects = listOf(
            entry("sync/rev-devB-3.zip", 300),
            entry("sync/rev-devB-1.zip", 100),
            entry("sync/rev-devB-2.zip", 200)
        )

        val result = selectFreshRevisions(objects, deviceId = "devA", watermark = 0)

        assertEquals(
            listOf("sync/rev-devB-1.zip", "sync/rev-devB-2.zip", "sync/rev-devB-3.zip"),
            result.map { it.key }
        )
    }

    @Test
    fun emptyInputYieldsEmpty() {
        val result = selectFreshRevisions(emptyList(), deviceId = "devA", watermark = 0)

        assertTrue(result.isEmpty())
    }

    @Test
    fun watermarkZeroIncludesEverythingFromOthers() {
        val objects = listOf(
            entry("sync/rev-devA-1.zip", 100),
            entry("sync/rev-devB-1.zip", 200)
        )

        val result = selectFreshRevisions(objects, deviceId = "devA", watermark = 0)

        assertEquals(1, result.size)
        assertEquals("sync/rev-devB-1.zip", result[0].key)
    }
}
