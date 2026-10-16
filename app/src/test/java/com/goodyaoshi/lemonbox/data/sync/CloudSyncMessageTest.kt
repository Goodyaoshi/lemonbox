package com.goodyaoshi.lemonbox.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云同步回执文案（含冲突可见的措辞）。
 *
 * 为什么值得单测：云端同步在后台自动进行，用户能看到的**只有这一句**。
 * 冲突条数是用户唯一能察觉「两边都改过、数据被自动取较新一方」的线索——
 * 漏拼接会让人以为没发生冲突（静默丢改动），而 0 条也拼上冲突字样则是虚报噪声。
 * 这类字符串问题编译器不管，只有用例能锁住。
 */
class CloudSyncMessageTest {

    @Test
    fun `什么都没有时报告已是最新`() {
        assertEquals("已是最新", cloudSyncMessage(pushed = 0, pulled = 0, conflicts = 0))
    }

    @Test
    fun `上传变更时带上说明`() {
        val message = cloudSyncMessage(pushed = 1, pulled = 0, conflicts = 0)

        assertTrue(message.startsWith("同步完成"))
        assertTrue(message.contains("上传"))
    }

    @Test
    fun `合并多份时带上份数`() {
        val message = cloudSyncMessage(pushed = 0, pulled = 3, conflicts = 0)

        assertTrue(message.contains("合并 3 份"))
    }

    /** 关键：有冲突必须说出来，否则用户以为没发生冲突。 */
    @Test
    fun `有冲突时明确报出条数`() {
        val message = cloudSyncMessage(pushed = 0, pulled = 2, conflicts = 5)

        assertTrue(message.contains("5 条冲突"))
    }

    /** 关键：0 条冲突不该出现冲突字样，避免每次同步都报噪声。 */
    @Test
    fun `零冲突时不出现冲突字样`() {
        val message = cloudSyncMessage(pushed = 1, pulled = 1, conflicts = 0)

        assertFalse(message.contains("冲突"))
    }

    @Test
    fun `只有冲突也应该有回执而非已是最新`() {
        // pushed/pulled 都为 0 但有冲突（例如本地已有该数据、只是标记了冲突）
        // 不能退化成「已是最新」——那会让用户以为什么都没发生。
        val message = cloudSyncMessage(pushed = 0, pulled = 0, conflicts = 2)

        assertNotEquals("已是最新", message)
        assertTrue(message.contains("2 条冲突"))
    }
}
