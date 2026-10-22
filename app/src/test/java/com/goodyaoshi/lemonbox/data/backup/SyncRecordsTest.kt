package com.goodyaoshi.lemonbox.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记录化与重组是「本机 ↔ 服务端」的载荷契约入口：
 * 键选错会把 A 的记录写到 B 的另一条上，重组漏一类会让某类数据永远同步不到，
 * 两者都不会在编译期体现，只有单测能提前发现。
 */
class SyncRecordsTest {

    private fun itemSnapshot(
        id: Long = 3,
        syncId: String? = "item-1",
        purgedAt: Long? = null
    ) = ItemSnapshot(
        id = id,
        name = "牛奶",
        quantity = 2.0,
        unit = "盒",
        price = null,
        expireTime = null,
        rating = null,
        ratedAt = null,
        deletedAt = null,
        purgedAt = purgedAt,
        note = "",
        createdAt = 5L,
        syncId = syncId,
        categorySyncId = "cat-1",
        updatedAt = 7L
    )

    private fun payload() = AppBackupPayload(
        exportedAt = 1_000L,
        syncVersion = AppBackupManager.SYNC_VERSION,
        deviceId = "devA",
        categories = listOf(
            CategorySnapshot(id = 1, name = "食品", icon = "", syncId = "cat-1", updatedAt = 10L)
        ),
        locations = listOf(
            LocationSnapshot(id = 2, name = "冰箱", parentId = null, syncId = "loc-1")
        ),
        items = listOf(itemSnapshot()),
        reminders = listOf(ReminderSnapshot(id = 4, title = "洗衣服", createdAt = 6L, syncId = "rem-1")),
        preferences = PreferencesSnapshot(nickname = "柠檬")
    )

    @Test
    fun `记录键用 syncId 而非本机自增 id`() {
        val records = payload().toSyncRecords()
        val category = records.single { it.collection == SyncCollections.CATEGORIES }
        assertEquals("cat-1", category.key)
        assertFalse(category.deleted)
    }

    @Test
    fun `没有 syncId 的旧行不进记录`() {
        val legacy = payload().copy(items = listOf(itemSnapshot(id = 9, syncId = null)))
        assertTrue(legacy.toSyncRecords().none { it.collection == SyncCollections.ITEMS })
    }

    @Test
    fun `永久删除的墓碑按已删除标记`() {
        val tombstone = payload().copy(items = listOf(itemSnapshot(purgedAt = 100L)))
        val item = tombstone.toSyncRecords().single { it.collection == SyncCollections.ITEMS }
        assertTrue(item.deleted)
    }

    @Test
    fun `偏好整块作为单条记录`() {
        val records = payload().toSyncRecords()
        val preferences = records.single { it.collection == SyncCollections.PREFERENCES }
        assertEquals(SyncCollections.PREFERENCES_KEY, preferences.key)
    }

    @Test
    fun `重组能把记录还原成清单`() {
        val original = payload()
        val restored = syncRecordsToPayload(original.toSyncRecords(), deviceId = "devB")

        // 重组后的清单要能直接喂给合并通道：版本号必须够新，否则会被当成旧备份按数字 id 对齐。
        assertEquals(AppBackupManager.SYNC_VERSION, restored.syncVersion)
        assertEquals(original.categories, restored.categories)
        assertEquals(original.locations, restored.locations)
        assertEquals(original.items, restored.items)
        assertEquals(original.reminders, restored.reminders)
        assertEquals(original.preferences, restored.preferences)
    }

    @Test
    fun `不认识的集合与解不开的载荷都跳过`() {
        val records = listOf(
            SyncRecord(collection = "unknown_collection", key = "x", payload = "{}"),
            SyncRecord(collection = SyncCollections.ITEMS, key = "item-9", payload = "不是 JSON")
        )
        val restored = syncRecordsToPayload(records, deviceId = "devA")
        assertTrue(restored.items.isEmpty())
        assertNull(restored.preferences)
    }

    @Test
    fun `解不开的载荷必须通过回调暴露，不能静默跳过`() {
        // 水位在落地后会越过坏记录；如果这里不回调，调用方就永远不知道有数据没落地。
        val goodItem = payload().toSyncRecords().single { it.collection == SyncCollections.ITEMS }
        val badItem = SyncRecord(SyncCollections.ITEMS, "item-bad", "{坏数据")
        val badPreferences = SyncRecord(SyncCollections.PREFERENCES, "preferences", "null")
        val skipped = mutableListOf<SyncRecord>()

        val restored = syncRecordsToPayload(
            listOf(goodItem, badItem, badPreferences),
            deviceId = "devA",
            onSkipped = { skipped += it }
        )

        assertEquals(listOf("item-bad", "preferences"), skipped.map { it.key })
        assertEquals(1, restored.items.size)
        assertNull(restored.preferences)
    }

    @Test
    fun `同步表映射覆盖全部 14 个集合`() {
        // 集合名是跨版本契约，表名是迁移与待推派发的落点：这份映射漏一个，表现就是
        // 「新增了一张同步表、却漏改迁移或漏改派发」，那类数据永远不上传，而编译期
        // 与界面上都看不出来。故这里把 14 个集合逐个写死比对，新增集合必须同步改到这里。
        val expected = setOf(
            SyncCollections.CATEGORIES,
            SyncCollections.LOCATIONS,
            SyncCollections.ITEMS,
            SyncCollections.LEDGER_ASSETS,
            SyncCollections.LEDGER_CATEGORIES,
            SyncCollections.LEDGER_RECORDS,
            SyncCollections.LEDGER_BUDGETS,
            SyncCollections.ANNIVERSARIES,
            SyncCollections.REMINDERS,
            SyncCollections.TIME_CAPSULES,
            SyncCollections.PACKING_TRIPS,
            SyncCollections.PACKING_ITEMS,
            SyncCollections.LEDGER_TEMPLATES,
            SyncCollections.LEDGER_RECURRING_RULES
        )
        assertEquals(14, expected.size)
        assertEquals(expected, SyncTables.BY_COLLECTION.keys)
        // TABLES 供迁移逐表加列：长度必须对得上，且不能重名（重名会让同一张表加两次列）。
        assertEquals(SyncTables.BY_COLLECTION.size, SyncTables.TABLES.size)
        assertEquals(SyncTables.TABLES.size, SyncTables.TABLES.toSet().size)
        // 偏好是整块设置、没有 dirty/serverSeq 列：不进这份映射，由调用方单独恒推。
        assertFalse(SyncTables.BY_COLLECTION.containsKey(SyncCollections.PREFERENCES))
    }
}
