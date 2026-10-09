package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.dao.ItemDao
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun permanentlyDeleteFromTrash_removesOnlyDeletedItems() = runTest {
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

        assertEquals(listOf(1L, 3L), deletedItems.map { it.id })
        assertEquals(listOf(2L), dao.snapshot().map { it.id })
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
        imagePath = if (deletedAt != null) "image-$id.jpg" else ""
    )
}

private class FakeItemDao(
    private val items: MutableList<Item>
) : ItemDao {

    fun snapshot(): List<Item> = items.toList()

    override fun getActiveItems(): Flow<List<ItemDetail>> = flowOf(emptyList())

    override fun getAllItems(): Flow<List<ItemDetail>> = flowOf(emptyList())

    override fun getItemsByUsageStatus(usageStatus: Int): Flow<List<ItemDetail>> = flowOf(
        items
            .filter { it.usageStatus == usageStatus && it.deletedAt == null }
            .map { ItemDetail(item = it) }
    )

    override fun getCountByUsageStatus(usageStatus: Int): Flow<Int> =
        flowOf(items.count { it.usageStatus == usageStatus && it.deletedAt == null })

    override fun getToBuyItems(): Flow<List<ItemDetail>> = flowOf(
        items
            .filter {
                it.needRestock &&
                    it.disposition != Item.DISPOSITION_DISCARDED &&
                    it.deletedAt == null
            }
            .map { ItemDetail(item = it) }
    )

    override fun getToBuyCount(): Flow<Int> = flowOf(
        items.count {
            it.needRestock &&
                it.disposition != Item.DISPOSITION_DISCARDED &&
                it.deletedAt == null
        }
    )

    override suspend fun getToBuyNames(): List<String> = items
        .filter {
            it.needRestock &&
                it.disposition != Item.DISPOSITION_DISCARDED &&
                it.deletedAt == null
        }
        .map { it.name }

    override fun getRecycleItems(cutoffTime: Long): Flow<List<ItemDetail>> = flowOf(
        items.filter { it.deletedAt != null && it.deletedAt >= cutoffTime }.map { ItemDetail(item = it) }
    )

    override fun getItemDetailById(id: Long): Flow<ItemDetail?> = flowOf(null)

    override fun getExpiringItems(thresholdTime: Long): Flow<List<ItemDetail>> = flowOf(emptyList())

    override fun searchItems(query: String): Flow<List<ItemDetail>> = flowOf(emptyList())

    override fun getItemsByCategory(categoryId: Long): Flow<List<ItemDetail>> = flowOf(emptyList())

    override fun getItemsByLocation(locationId: Long): Flow<List<ItemDetail>> = flowOf(emptyList())

    override fun getActiveItemsByBarcode(barcode: String): Flow<List<ItemDetail>> = flowOf(emptyList())

    override fun getItemsByBarcode(barcode: String): Flow<List<ItemDetail>> = flowOf(emptyList())

    override suspend fun getActiveItemsByBarcodeSync(barcode: String): List<Item> =
        items.filter {
            it.barcode == barcode &&
                it.disposition == Item.DISPOSITION_IN_STOCK &&
                it.deletedAt == null
        }

    override suspend fun getLatestItemByBarcode(barcode: String): Item? =
        items.firstOrNull { it.barcode == barcode && it.deletedAt == null }

    override suspend fun incrementQuantity(id: Long, delta: Int, updatedAt: Long) {
        items.replaceAll { item ->
            if (item.id == id) item.copy(quantity = item.quantity + delta, updatedAt = updatedAt) else item
        }
    }

    override suspend fun consumeOnePiece(id: Long, updatedAt: Long) {
        items.replaceAll { item ->
            if (item.id == id) {
                item.copy(
                    quantity = item.quantity - 1,
                    consumedQuantity = item.consumedQuantity + 1,
                    updatedAt = updatedAt
                )
            } else {
                item
            }
        }
    }

    override suspend fun getItemById(id: Long): Item? = items.firstOrNull { it.id == id && it.deletedAt == null }

    override suspend fun getAllItemsSnapshot(): List<Item> = snapshot()

    override suspend fun insert(item: Item): Long {
        items += item
        return item.id
    }

    override suspend fun update(item: Item) {
        replace(item)
    }

    override suspend fun delete(item: Item) {
        items.removeAll { it.id == item.id }
    }

    override suspend fun moveToTrash(id: Long, deletedAt: Long) {
        items.replaceAll { item -> if (item.id == id) item.copy(deletedAt = deletedAt) else item }
    }

    override suspend fun restoreFromTrash(id: Long, updatedAt: Long) {
        items.replaceAll { item ->
            if (item.id == id) item.copy(deletedAt = null, updatedAt = updatedAt) else item
        }
    }

    override suspend fun restoreItemsFromTrash(ids: List<Long>, updatedAt: Long) {
        val idSet = ids.toSet()
        items.replaceAll { item ->
            if (item.id in idSet) item.copy(deletedAt = null, updatedAt = updatedAt) else item
        }
    }

    override suspend fun updateUsageStatus(id: Long, usageStatus: Int, updatedAt: Long) {
        items.replaceAll { item ->
            if (item.id == id) item.copy(usageStatus = usageStatus, updatedAt = updatedAt) else item
        }
    }

    override suspend fun updateDisposition(id: Long, disposition: Int, updatedAt: Long) {
        items.replaceAll { item ->
            if (item.id == id) item.copy(disposition = disposition, updatedAt = updatedAt) else item
        }
    }

    override suspend fun updateNeedRestock(id: Long, needRestock: Boolean, updatedAt: Long) {
        items.replaceAll { item ->
            if (item.id == id) item.copy(needRestock = needRestock, updatedAt = updatedAt) else item
        }
    }

    override suspend fun updateUsageWindow(
        id: Long,
        startUseTime: Long?,
        usageEndedAt: Long?,
        updatedAt: Long
    ) {
        items.replaceAll { item ->
            if (item.id == id) {
                item.copy(
                    startUseTime = startUseTime,
                    usageEndedAt = usageEndedAt,
                    updatedAt = updatedAt
                )
            } else {
                item
            }
        }
    }

    override suspend fun updatePurchaseDate(id: Long, purchaseDate: Long?, updatedAt: Long) {
        items.replaceAll { item ->
            if (item.id == id) item.copy(purchaseDate = purchaseDate, updatedAt = updatedAt) else item
        }
    }

    override suspend fun getActiveItemsSync(): List<Item> =
        items.filter { it.disposition == Item.DISPOSITION_IN_STOCK && it.deletedAt == null }

    override fun getActiveCount(): Flow<Int> =
        flowOf(items.count { it.disposition == Item.DISPOSITION_IN_STOCK && it.deletedAt == null })

    override fun getAvailableCount(): Flow<Int> = flowOf(
        items.count {
            it.disposition == Item.DISPOSITION_IN_STOCK &&
                it.usageStatus != Item.USAGE_USED_UP &&
                it.deletedAt == null
        }
    )

    override fun getExpiringCount(thresholdTime: Long): Flow<Int> = flowOf(0)

    override fun getTotalCount(): Flow<Int> = flowOf(items.count { it.deletedAt == null })

    override fun getUsedUpCount(): Flow<Int> = flowOf(
        items.count {
            it.usageStatus == Item.USAGE_USED_UP &&
                it.disposition == Item.DISPOSITION_IN_STOCK &&
                it.deletedAt == null
        }
    )

    override fun getTotalValue(): Flow<Double> = flowOf(0.0)

    override fun getCountByCategory(categoryId: Long): Flow<Int> = flowOf(0)

    override fun getCountByLocation(locationId: Long): Flow<Int> = flowOf(0)

    override fun getRecycleCount(cutoffTime: Long): Flow<Int> =
        flowOf(items.count { it.deletedAt != null && it.deletedAt >= cutoffTime })

    override suspend fun getDeletedItemsBefore(cutoffTime: Long): List<Item> =
        items.filter { it.deletedAt != null && it.deletedAt < cutoffTime }

    override suspend fun getDeletedItemsByIds(ids: List<Long>): List<Item> {
        val idSet = ids.toSet()
        return items.filter { it.id in idSet && it.deletedAt != null }
    }

    override suspend fun purgeDeletedBefore(cutoffTime: Long) {
        items.removeAll { it.deletedAt != null && it.deletedAt < cutoffTime }
    }

    override suspend fun deleteDeletedItemsByIds(ids: List<Long>) {
        val idSet = ids.toSet()
        items.removeAll { it.id in idSet && it.deletedAt != null }
    }

    override suspend fun deleteAll() {
        items.clear()
    }

    override fun getExpiringItemsInRange(currentTime: Long, thresholdTime: Long): Flow<List<ItemDetail>> =
        flowOf(emptyList())

    private fun replace(item: Item) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            items[index] = item
        }
    }
}
