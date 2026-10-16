package com.goodyaoshi.lemonbox.data.sync

import com.goodyaoshi.lemonbox.data.imagecloud.S3ObjectEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧 rev 的清理选择规则。
 *
 * 这是**不可逆**操作里最危险的一段：删掉一份别的设备还没拉走的 rev，那些变更就
 * 永远看不到了——不会报错、用户也不会知道，只会在某台设备上「少了一些东西」。
 * 因此规则刻意做成「最近 N 条」与「最近 M 天」的**并集**：任一条件成立就保留。
 * 下面每个用例都在锁这条保守性。
 */
class SelectCleanupCandidatesTest {

    private fun entry(key: String, at: Long): S3ObjectEntry =
        S3ObjectEntry(key = key, lastModified = at, size = 100)

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `近期窗口内的一律保留即使超过条数下限`() {
        // 30 份都在 7 天内：即便条数下限是 20，也不能删这 10 份——
        // 别的设备可能正要去拉它们。
        val objects = (1..30).map { entry("sync/rev-devB-$it.zip", 1000L - it) }

        val doomed = selectCleanupCandidates(
            objects, nowMillis = 1000L, keepRecentCount = 20, keepWindowMillis = 7 * day
        )

        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `超出条数下限且在窗口外才删`() {
        val now = 100 * day
        val objects = listOf(
            // 最近 2 份（条数下限内）——即便很旧也保留
            entry("sync/rev-devB-new.zip", now - 1),
            entry("sync/rev-devB-new2.zip", now - 2),
            // 窗口外且超出条数下限——可删
            entry("sync/rev-devB-old.zip", now - 30 * day)
        )

        val doomed = selectCleanupCandidates(
            objects, nowMillis = now, keepRecentCount = 2, keepWindowMillis = 7 * day
        )

        assertEquals(listOf("sync/rev-devB-old.zip"), doomed)
    }

    @Test
    fun `条数下限内保留即使已远超时间窗口`() {
        // 长期不同步：所有对象都在窗口外，但条数下限内的仍要保留，
        // 否则「攒了一段时间没同步」的设备一上线就发现无处可拉。
        val now = 100 * day
        val objects = (1..5).map { entry("sync/rev-devB-$it.zip", now - (30 + it) * day) }

        val doomed = selectCleanupCandidates(
            objects, nowMillis = now, keepRecentCount = 3, keepWindowMillis = 7 * day
        )

        // 只删掉条数下限之外的 2 份（最旧的两份）。
        assertEquals(2, doomed.size)
        assertTrue("sync/rev-devB-4.zip" in doomed)
        assertTrue("sync/rev-devB-5.zip" in doomed)
    }

    @Test
    fun `base 永不参与清理`() {
        // base 是新设备加入的起点，且数量极少，不该为了省空间而删。
        val now = 100 * day
        val objects = listOf(
            entry("sync/base-devB-very-old.zip", now - 90 * day),
            entry("sync/rev-devB-old.zip", now - 30 * day)
        )

        val doomed = selectCleanupCandidates(
            objects, nowMillis = now, keepRecentCount = 0, keepWindowMillis = 7 * day
        )

        assertEquals(listOf("sync/rev-devB-old.zip"), doomed)
        assertTrue(doomed.none { it.contains("base-") })
    }

    @Test
    fun `无关对象不参与`() {
        val now = 100 * day
        val objects = listOf(
            entry("images/abc.jpg", now - 90 * day),
            entry("sync/rev-devB-old.zip", now - 30 * day)
        )

        val doomed = selectCleanupCandidates(
            objects, nowMillis = now, keepRecentCount = 0, keepWindowMillis = 7 * day
        )

        assertEquals(listOf("sync/rev-devB-old.zip"), doomed)
    }

    @Test
    fun `空输入返回空`() {
        val doomed = selectCleanupCandidates(
            emptyList(), nowMillis = 1000L, keepRecentCount = 20, keepWindowMillis = 7 * day
        )

        assertTrue(doomed.isEmpty())
    }

    /**
     * 排序必须按服务端时间降序后 drop：若误按升序，drop 掉的会是**最新**的 N 条，
     * 即把最该保留的删掉、把最旧的留下——正好反了。
     */
    @Test
    fun `排序按服务端时间降序 drop 的是最旧的`() {
        val now = 100 * day
        val objects = listOf(
            entry("sync/rev-devB-a.zip", now - 30 * day),
            entry("sync/rev-devB-b.zip", now - 50 * day),
            entry("sync/rev-devB-c.zip", now - 10 * day)
        )

        val doomed = selectCleanupCandidates(
            objects, nowMillis = now, keepRecentCount = 1, keepWindowMillis = 0
        )

        // 最近的是 c（now-10d），保留；a、b 可删。
        assertEquals(setOf("sync/rev-devB-a.zip", "sync/rev-devB-b.zip"), doomed.toSet())
    }
}
