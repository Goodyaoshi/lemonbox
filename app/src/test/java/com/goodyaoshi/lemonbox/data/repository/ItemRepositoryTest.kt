package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.testing.FakeItemDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ItemRepositoryTest {

    @Test
    fun restoreFromTrash_restoresOnlySelectedItems() = runTest {
        val deletedAt = 1_000L
        val dao = FakeItemDao(
            mutableListOf(
                testItem(id = 1L, deletedAt = deletedAt),
                testItem(id = 2L, deletedAt = deletedAt),
                testItem(id = 3L, deletedAt = null)
            )
        )
        val repository = ItemRepository(dao)

        repository.restoreFromTrash(listOf(1L, 2L))

        val items = dao.snapshot()
        assertNull(items.first { it.id == 1L }.deletedAt)
        assertNull(items.first { it.id == 2L }.deletedAt)
        assertNull(items.first { it.id == 3L }.deletedAt)
    }

    @Test
    fun permanentlyDeleteFromTrash_marksTombstoneInsteadOfDeleting() = runTest {
        val deletedAt = 2_000L
        val dao = FakeItemDao(
            mutableListOf(
                testItem(id = 1L, deletedAt = deletedAt),
                testItem(id = 2L, deletedAt = null),
                testItem(id = 3L, deletedAt = deletedAt)
            )
        )
        val repository = ItemRepository(dao)

        val deletedItems = repository.permanentlyDeleteFromTrash(listOf(1L, 2L, 3L))

        // 只处理已在回收站里的条目（2L 还在正常列表，不该被动）。
        assertEquals(listOf(1L, 3L), deletedItems.map { it.id })
        // 不再物理删行：三条都还在，否则删除无法随快照同步到其他设备。
        val byId = dao.snapshot().associateBy { it.id }
        assertEquals(setOf(1L, 2L, 3L), byId.keys)
        // 被永久删除的两条带上 purgedAt 墓碑，回收站与业务查询都不会再显示它们。
        assertNotNull(byId.getValue(1L).purgedAt)
        assertNotNull(byId.getValue(3L).purgedAt)
        // 未选中删除的那条不受影响。
        assertNull(byId.getValue(2L).purgedAt)
        assertNull(byId.getValue(2L).deletedAt)
    }

    @Test
    fun purgeDeletedItemsOlderThan_returnsPurgedItems() = runTest {
        val dao = FakeItemDao(
            mutableListOf(
                testItem(id = 1L, deletedAt = 100L),
                testItem(id = 2L, deletedAt = 300L),
                testItem(id = 3L, deletedAt = null)
            )
        )
        val repository = ItemRepository(dao)

        val purgedItems = repository.purgeDeletedItemsOlderThan(cutoffTime = 200L)

        assertEquals(listOf(1L), purgedItems.map { it.id })
        assertEquals(listOf(2L, 3L), dao.snapshot().map { it.id })
    }

    @Test
    fun addToBuyItemIfAbsent_skipsExistingNameAndWritesCategory() = runTest {
        val dao = FakeItemDao(mutableListOf())
        val repository = ItemRepository(dao)

        val firstId = repository.addToBuyItemIfAbsent("洗衣液", 2, "瓶", categoryId = 7L)
        // 同名（忽略大小写与首尾空格）已存在时不再重复添加。
        val duplicateId = repository.addToBuyItemIfAbsent(" 洗衣液 ", 1, "袋", categoryId = null)

        assertEquals(null, duplicateId)
        val created = dao.snapshot().single()
        assertEquals(firstId, created.id)
        assertEquals("洗衣液", created.name)
        assertEquals(2, created.quantity)
        assertEquals("瓶", created.unit)
        assertEquals(7L, created.categoryId)
        assertEquals(true, created.needRestock)
    }

    @Test
    fun consumeOne_skipsDurableItems() = runTest {
        val dao = FakeItemDao(
            mutableListOf(
                testItem(id = 1L, deletedAt = null).copy(
                    trackMode = Item.TRACK_DURABLE,
                    quantity = 3
                )
            )
        )
        val repository = ItemRepository(dao)

        repository.consumeOne(1L)

        val item = dao.snapshot().single()
        // 持续使用型物品不扣数量、不改变状态。
        assertEquals(3, item.quantity)
        assertEquals(Item.USAGE_IN_USE, item.usageStatus)
    }

    @Test
    fun consumeOne_closesUsageWindowWhenUsedUp() = runTest {
        val dao = FakeItemDao(
            mutableListOf(testItem(id = 1L, deletedAt = null).copy(startUseTime = 100L))
        )
        val repository = ItemRepository(dao)

        repository.consumeOne(1L)

        val item = dao.snapshot().single()
        assertEquals(0, item.quantity)
        assertEquals(Item.USAGE_USED_UP, item.usageStatus)
        assertEquals(100L, item.startUseTime)
        assertEquals(item.updatedAt, item.usageEndedAt)
    }

    @Test
    fun markAsUsed_closesUsageWindow() = runTest {
        val dao = FakeItemDao(
            mutableListOf(
                testItem(id = 1L, deletedAt = null).copy(
                    startUseTime = 100L,
                    usageStatus = Item.USAGE_IN_USE
                )
            )
        )
        val repository = ItemRepository(dao)

        repository.markAsUsed(1L)

        val item = dao.snapshot().single()
        assertEquals(Item.USAGE_USED_UP, item.usageStatus)
        assertEquals(true, item.needRestock)
        assertEquals(100L, item.startUseTime)
        assertEquals(item.updatedAt, item.usageEndedAt)
    }

    @Test
    fun restoreToInUse_recordsPurchaseAndRestartsWindow() = runTest {
        val dao = FakeItemDao(
            mutableListOf(
                testItem(id = 1L, deletedAt = null).copy(
                    usageStatus = Item.USAGE_USED_UP,
                    needRestock = true,
                    startUseTime = 100L,
                    usageEndedAt = 200L
                )
            )
        )
        val repository = ItemRepository(dao)

        repository.restoreToInUse(1L)

        val item = dao.snapshot().single()
        assertEquals(Item.USAGE_IN_USE, item.usageStatus)
        assertEquals(false, item.needRestock)
        // 「已买到」= 刚买回来：购买日期记当下，并开启新一轮使用周期。
        assertEquals(item.updatedAt, item.purchaseDate)
        assertEquals(item.updatedAt, item.startUseTime)
        assertNull(item.usageEndedAt)
    }

    @Test
    fun setDisposition_backToInStockRestartsWindow() = runTest {
        val dao = FakeItemDao(
            mutableListOf(
                testItem(id = 1L, deletedAt = null).copy(
                    disposition = Item.DISPOSITION_GIVEN_AWAY,
                    startUseTime = 100L,
                    usageEndedAt = 200L
                )
            )
        )
        val repository = ItemRepository(dao)

        repository.setDisposition(1L, Item.DISPOSITION_IN_STOCK)

        val item = dao.snapshot().single()
        assertEquals(Item.DISPOSITION_IN_STOCK, item.disposition)
        assertEquals(item.updatedAt, item.startUseTime)
        assertNull(item.usageEndedAt)
    }

    private fun testItem(id: Long, deletedAt: Long?) = Item(
        id = id,
        name = "Item $id",
        deletedAt = deletedAt,
        imageHashes = if (deletedAt != null) "hash-$id" else null
    )
}
