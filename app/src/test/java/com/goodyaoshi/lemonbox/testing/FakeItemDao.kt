package com.goodyaoshi.lemonbox.testing

import com.goodyaoshi.lemonbox.data.local.dao.ItemDao
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 单元测试用的 [ItemDao] 内存实现（F17）。
 *
 * Room 的 DAO 是接口，直接用一个内存 List 实现它，就能在纯 JVM 单测里跑通
 * 仓库层与 ViewModel 的逻辑，无需 Android 运行时或真机。
 * 供 [com.goodyaoshi.lemonbox.data.repository.ItemRepositoryTest] 与
 * `ui.viewmodel` 下的 ViewModel 测试共用，避免每个测试各抄一份桩实现。
 */
class FakeItemDao(
    private val items: MutableList<Item> = mutableListOf()
) : ItemDao {

    fun snapshot(): List<Item> = items.toList()

    override fun getActiveItems(): Flow<List<ItemDetail>> = flowOf(
        items
            .filter { it.disposition == Item.DISPOSITION_IN_STOCK && it.deletedAt == null }
            .map { ItemDetail(item = it) }
    )

    override fun getAllItems(): Flow<List<ItemDetail>> = flowOf(
        items.filter { it.deletedAt == null }.map { ItemDetail(item = it) }
    )

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

    override fun getItemDetailById(id: Long): Flow<ItemDetail?> = flowOf(
        items.firstOrNull { it.id == id && it.deletedAt == null }?.let { ItemDetail(item = it) }
    )

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
