package com.goodyaoshi.lemonbox.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云同步对象键推导。
 *
 * 为什么要单测：这套同步**不用服务器裁决**，正确性完全建立在「每个快照都是新对象、
 * 谁都不覆盖谁」之上。若两次调用产生同一个键，第二次上传就会覆盖第一次，
 * 而对象存储没有原子的读-改-写——覆盖即数据丢失，且没有任何报错。
 */
class CloudSyncKeysTest {

    @Test
    fun newRevisionKey_isUniqueAcrossCalls() {
        // 同设备连续两次推送必须是两个不同的键，否则后一次会覆盖前一次。
        val first = CloudSyncKeys.newRevisionKey("", "devA")
        val second = CloudSyncKeys.newRevisionKey("", "devA")

        assertNotEquals(first, second)
    }

    @Test
    fun newRevisionKey_containsDeviceId() {
        val key = CloudSyncKeys.newRevisionKey("", "devA")

        assertTrue(key.contains("devA"))
    }

    @Test
    fun newRevisionKey_landsUnderSyncFolder() {
        val key = CloudSyncKeys.newRevisionKey("", "devA")

        assertTrue(key.startsWith("sync/rev-"))
        assertTrue(key.endsWith(".zip"))
    }

    @Test
    fun listPrefix_includesTrailingSlashToAvoidPrefixCollisions() {
        // 尾部斜杠很重要：没有它 "sync" 会匹配到 "syncold/..." 这类兄弟目录。
        assertEquals("sync/", CloudSyncKeys.listPrefix(""))
        assertEquals("lemon/sync/", CloudSyncKeys.listPrefix("lemon"))
    }

    @Test
    fun listPrefix_normalizesSlashesAndSpaces() {
        assertEquals("lemon/sync/", CloudSyncKeys.listPrefix("  /lemon/  "))
    }

    @Test
    fun newRevisionKey_toleratesSlashInDeviceId() {
        // 设备 ID 里若混进斜杠，对象键会被切出意外的层级；应被替换掉。
        val key = CloudSyncKeys.newRevisionKey("", "dev/A")

        assertFalse(key.substringAfterLast("sync/").startsWith("dev/"))
        assertTrue(key.startsWith("sync/rev-"))
    }

    @Test
    fun isRevisionKey_acceptsOwnFormatRejectsOthers() {
        assertTrue(CloudSyncKeys.isRevisionKey("sync/rev-devA-123.zip"))
        assertTrue(CloudSyncKeys.isRevisionKey("lemon/sync/rev-devA-123.zip"))

        assertFalse(CloudSyncKeys.isRevisionKey("sync/base-1.zip"))
        assertFalse(CloudSyncKeys.isRevisionKey("images/abc.jpg"))
        assertFalse(CloudSyncKeys.isRevisionKey("sync/rev-devA-123.json"))
    }

    @Test
    fun isOwnRevisionKey_distinguishesDevices() {
        val mine = CloudSyncKeys.newRevisionKey("", "devA")

        assertTrue(CloudSyncKeys.isOwnRevisionKey(mine, "devA"))
        assertFalse(CloudSyncKeys.isOwnRevisionKey(mine, "devB"))
    }

    @Test
    fun isOwnRevisionKey_doesNotMatchDeviceIdSubstring() {
        // "dev" 不应被认成 "devA" 的快照——否则会跳过别人的数据。
        val other = CloudSyncKeys.newRevisionKey("", "devA")

        assertFalse(CloudSyncKeys.isOwnRevisionKey(other, "dev"))
    }
}
