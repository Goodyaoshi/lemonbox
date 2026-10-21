package com.goodyaoshi.lemonbox.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拉取响应的兼容解析。
 *
 * `scannedSeq` 是修复「整页都是本设备记录、水位不前进」的关键：新服务端返回它，
 * 旧服务端没有这个字段时客户端必须回退读 `lastSeq`，否则升级/回滚期间会拉不动。
 */
class PullResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `新服务端同时返回 scannedSeq 与 lastSeq`() {
        val response = json.decodeFromString(
            PullResponse.serializer(),
            """{"changes":[],"lastSeq":420,"scannedSeq":420,"hasMore":true}"""
        )
        assertEquals(420L, response.scannedSeq)
        assertEquals(420L, response.lastSeq)
        assertTrue(response.hasMore)
        assertTrue(response.changes.isEmpty())
    }

    @Test
    fun `旧服务端没有 scannedSeq 时回退为 0，由调用方读 lastSeq`() {
        val response = json.decodeFromString(
            PullResponse.serializer(),
            """{"changes":[{"collection":"items","key":"s1","payload":"{}","seq":7}],"lastSeq":7,"hasMore":false}"""
        )
        assertEquals(0L, response.scannedSeq)
        assertEquals(7L, response.lastSeq)
        assertEquals("s1", response.changes.single().key)
    }

    @Test
    fun `整页被本设备过滤时 changes 为空但扫描位置仍前进`() {
        val response = json.decodeFromString(
            PullResponse.serializer(),
            """{"changes":[],"lastSeq":200,"scannedSeq":200,"hasMore":true}"""
        )
        // 客户端必须用 scannedSeq 推进水线；只看 changes 会误判成「没有新数据」。
        assertTrue(response.changes.isEmpty())
        assertTrue(response.scannedSeq > 0)
        assertTrue(response.hasMore)
    }

    @Test
    fun `推送载荷支持 force 标记`() {
        val change = json.decodeFromString(
            PushChange.serializer(),
            """{"collection":"items","key":"s1","payload":"{}","baseSeq":3,"force":true}"""
        )
        assertTrue(change.force)
        assertFalse(change.deleted)
    }
}
