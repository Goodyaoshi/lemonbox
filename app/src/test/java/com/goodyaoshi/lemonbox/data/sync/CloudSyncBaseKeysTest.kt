package com.goodyaoshi.lemonbox.data.sync

import com.goodyaoshi.lemonbox.data.imagecloud.S3ObjectEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全量 checkpoint（base）的键推导与挑选（多设备同步的「新设备快速加入」）。
 *
 * 为什么值得单测：
 * - `selectLatestBase` 选错的表现是「新设备从很旧的状态起步」——界面上看不出来，
 *   只会觉得「怎么少了一些东西」，而这正是最难排查的一类问题；
 * - `isBaseKey` 与 `isRevisionKey` 若混淆，清理会把 base 当旧 rev 删掉，
 *   又或者把 rev 当 base 拉，让增量被误当成全量。
 */
class CloudSyncBaseKeysTest {

    private fun entry(key: String, at: Long): S3ObjectEntry =
        S3ObjectEntry(key = key, lastModified = at, size = 100)

    // ---------- 键推导 ----------

    @Test
    fun `newBaseKey 落在 sync 目录且带 base 前缀`() {
        val key = CloudSyncKeys.newBaseKey("", "devA")

        assertTrue(key.startsWith("sync/base-"))
        assertTrue(key.endsWith(".zip"))
        assertTrue(key.contains("devA"))
    }

    @Test
    fun `同设备连续两次调用产生不同的 base 键`() {
        // 与 rev 同理：覆盖即丢失。base 也必须每次都是新对象。
        val first = CloudSyncKeys.newBaseKey("", "devA")
        val second = CloudSyncKeys.newBaseKey("", "devA")

        assertFalse(first == second)
    }

    @Test
    fun `newBaseKey 支持自定义 prefix 且归一化斜杠`() {
        val key = CloudSyncKeys.newBaseKey("  /lemon/  ", "devA")

        assertTrue(key.startsWith("lemon/sync/base-"))
    }

    @Test
    fun `isBaseKey 只认 base 不认 rev`() {
        assertTrue(CloudSyncKeys.isBaseKey("sync/base-devA-1.zip"))
        assertTrue(CloudSyncKeys.isBaseKey("lemon/sync/base-devA-1.zip"))

        assertFalse(CloudSyncKeys.isBaseKey("sync/rev-devA-1.zip"))
        assertFalse(CloudSyncKeys.isBaseKey("sync/base-devA-1.json"))
        assertFalse(CloudSyncKeys.isBaseKey("images/abc.jpg"))
    }

    @Test
    fun `isSnapshotKey 同时接受 base 与 rev`() {
        assertTrue(CloudSyncKeys.isSnapshotKey("sync/base-devA-1.zip"))
        assertTrue(CloudSyncKeys.isSnapshotKey("sync/rev-devA-1.zip"))
        assertFalse(CloudSyncKeys.isSnapshotKey("images/abc.jpg"))
    }

    @Test
    fun `isOwnBaseKey 区分设备且不匹配子串`() {
        val mine = CloudSyncKeys.newBaseKey("", "devA")

        assertTrue(CloudSyncKeys.isOwnBaseKey(mine, "devA"))
        assertFalse(CloudSyncKeys.isOwnBaseKey(mine, "devB"))
        // "dev" 不应被认成 "devA" 的 base，否则会跳过别人的起点。
        assertFalse(CloudSyncKeys.isOwnBaseKey(mine, "dev"))
    }

    // ---------- 挑选最近 base ----------

    @Test
    fun `selectLatestBase 取服务端时间最近的一份`() {
        val objects = listOf(
            entry("sync/base-devB-old.zip", 100),
            entry("sync/base-devB-new.zip", 300),
            entry("sync/base-devB-mid.zip", 200)
        )

        val result = selectLatestBase(objects, deviceId = "devA")

        assertEquals("sync/base-devB-new.zip", result?.key)
    }

    @Test
    fun `selectLatestBase 排除自己的 base`() {
        val objects = listOf(
            entry("sync/base-devA-mine.zip", 500),
            entry("sync/base-devB-other.zip", 300)
        )

        val result = selectLatestBase(objects, deviceId = "devA")

        assertEquals("sync/base-devB-other.zip", result?.key)
    }

    @Test
    fun `selectLatestBase 忽略 rev 与无关对象`() {
        val objects = listOf(
            entry("sync/rev-devB-1.zip", 900),
            entry("images/abc.jpg", 800)
        )

        assertNull(selectLatestBase(objects, deviceId = "devA"))
    }

    @Test
    fun `selectLatestBase 只有自己的 base 时返回 null`() {
        val objects = listOf(entry("sync/base-devA-mine.zip", 500))

        assertNull(selectLatestBase(objects, deviceId = "devA"))
    }

    @Test
    fun `selectLatestBase 空输入返回 null`() {
        assertNull(selectLatestBase(emptyList(), deviceId = "devA"))
    }
}
