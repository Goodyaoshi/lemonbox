package com.goodyaoshi.lemonbox.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `有冲突时说清按较新版本合并`() {
        val message = remoteSyncMessage(pushed = 0, pulled = 2, conflicts = 5)
        assertTrue(message.contains("5 条"))
        assertTrue(message.contains("较新版本"))
        // 旧文案的「以服务端为准」「本地优先」都不准确：取舍取决于两侧的修改时刻。
        assertFalse(message.contains("以服务端为准"))
        assertFalse(message.contains("本地优先"))
    }

    @Test
    fun `只有冲突、没有推拉时也要给出说明`() {
        val message = remoteSyncMessage(pushed = 0, pulled = 0, conflicts = 2)
        assertTrue(message.contains("2 条"))
        assertTrue(message.contains("较新版本"))
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

/**
 * 冲突取舍的有效时刻：墓碑只写 deletedAt / purgedAt 时，必须按删除时刻算，
 * 否则「对方已删除」会被判成更旧，本机版本重推后把记录复活。
 */
class PayloadStampTest {

    @Test
    fun `普通记录用 updatedAt`() {
        assertEquals(1700000000000L, payloadStamp("""{"updatedAt":1700000000000}"""))
    }

    @Test
    fun `缺 updatedAt 回退 createdAt`() {
        assertEquals(1600000000000L, payloadStamp("""{"createdAt":1600000000000}"""))
    }

    @Test
    fun `墓碑以删除时刻为准`() {
        assertEquals(
            1700000500000L,
            payloadStamp("""{"updatedAt":1700000000000,"deletedAt":1700000500000}""")
        )
        assertEquals(
            1700000900000L,
            payloadStamp("""{"updatedAt":1700000000000,"purgedAt":1700000900000}""")
        )
    }

    @Test
    fun `坏载荷按 0`() {
        assertEquals(0L, payloadStamp(null))
        assertEquals(0L, payloadStamp("不是 JSON"))
    }

    @Test
    fun `识别永久删除终态`() {
        assertEquals(1700000900000L, payloadPurgedAt("""{"purgedAt":1700000900000}"""))
        assertEquals(0L, payloadPurgedAt("""{"deletedAt":1700000500000}"""))
        assertEquals(0L, payloadPurgedAt(null))
    }
}

/**
 * 冲突回退要把旧载荷的时间推到当前才会被合并通道接受；
 * 只改这一位、不动其余字段，才能保证回退的是「本机当时那版」而不是残缺版本。
 */
class BumpPayloadStampTest {

    @Test
    fun `推新时间戳且保留其余字段`() {
        val bumped = bumpPayloadStamp(
            """{"id":"a","name":"旧名字","updatedAt":1700000000000}""",
            1800000000000L
        )
        assertEquals(1800000000000L, payloadStamp(bumped))
        assertTrue(bumped!!.contains("\"name\":\"旧名字\""))
    }

    @Test
    fun `缺 updatedAt 时补上`() {
        val bumped = bumpPayloadStamp("""{"createdAt":1600000000000}""", 1800000000000L)
        assertEquals(1800000000000L, payloadStamp(bumped))
    }

    @Test
    fun `空与坏载荷返回 null`() {
        assertNull(bumpPayloadStamp(null, 1800000000000L))
        assertNull(bumpPayloadStamp("", 1800000000000L))
        assertNull(bumpPayloadStamp("不是 JSON", 1800000000000L))
        assertNull(bumpPayloadStamp("{}", 1800000000000L))
    }
}
