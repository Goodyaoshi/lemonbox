package com.goodyaoshi.lemonbox.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回执文案是用户唯一能察觉「两边都改过同一条」的线索：
 * 0 条也报冲突、或冲突条数拼错，都会直接误导用户，而这类字符串问题编译器不管。
 */
class RemoteSyncMessageTest {

    @Test
    fun `什么都没发生就说已是最新`() {
        assertEquals("已是最新", remoteSyncMessage(pushed = 0, pulled = 0, conflicts = 0))
    }

    @Test
    fun `上传与收到分别有数字`() {
        val uploaded = remoteSyncMessage(pushed = 12, pulled = 0, conflicts = 0)
        assertTrue(uploaded.contains("上传 12 条"))
        assertFalse(uploaded.contains("收到"))

        val received = remoteSyncMessage(pushed = 0, pulled = 3, conflicts = 0)
        assertTrue(received.contains("收到 3 条"))
        assertFalse(received.contains("上传"))
    }

    @Test
    fun `零冲突时不出现冲突字样`() {
        assertFalse(remoteSyncMessage(pushed = 1, pulled = 1, conflicts = 0).contains("冲突"))
    }

    @Test
    fun `有冲突时说清按较新的改动为准`() {
        val message = remoteSyncMessage(pushed = 0, pulled = 2, conflicts = 5)
        assertTrue(message.contains("5 条"))
        assertTrue(message.contains("较新"))
        // 旧文案的「以服务端为准」不再成立：收场规则是较新者胜，可能落在任何一方。
        assertFalse(message.contains("以服务端为准"))
    }

    @Test
    fun `只有冲突、没有推拉时也要给出说明`() {
        val message = remoteSyncMessage(pushed = 0, pulled = 0, conflicts = 2)
        assertTrue(message.contains("2 条"))
        assertTrue(message.contains("较新"))
    }
}

/**
 * 冲突收场的时间戳提取：取错了就会把旧版当新版重推（或反过来），
 * 两种方向都破坏「较新者胜」，且静默无提示。
 */
class PayloadUpdatedAtTest {

    @Test
    fun `正常载荷取到 updatedAt`() {
        assertEquals(1700000000000L, payloadUpdatedAt("""{"id":"a","updatedAt":1700000000000}"""))
    }

    @Test
    fun `缺字段按 0`() {
        assertEquals(0L, payloadUpdatedAt("""{"id":"a"}"""))
        assertEquals(0L, payloadUpdatedAt("""{"updatedAt":null}"""))
    }

    @Test
    fun `空与坏载荷按 0`() {
        assertEquals(0L, payloadUpdatedAt(null))
        assertEquals(0L, payloadUpdatedAt(""))
        assertEquals(0L, payloadUpdatedAt("不是 JSON"))
        assertEquals(0L, payloadUpdatedAt("""["数组也算不出"]"""))
    }
}
