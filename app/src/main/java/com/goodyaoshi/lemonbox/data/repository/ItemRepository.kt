package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.dao.ItemDao
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.util.MonotonicClock
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ItemRepository @Inject constructor(
    private val itemDao: ItemDao
) {
    fun getActiveItems(): Flow<List<ItemDetail>> = itemDao.getActiveItems()

    fun getAllItems(): Flow<List<ItemDetail>> = itemDao.getAllItems()

    fun getRecycleItems(cutoffTime: Long): Flow<List<ItemDetail>> = itemDao.getRecycleItems(cutoffTime)

    /** 待买清单：勾选了「需要补货」且尚未丢弃的物品。 */
    fun getToBuyItems(): Flow<List<ItemDetail>> = itemDao.getToBuyItems()

    fun getToBuyCount(): Flow<Int> = itemDao.getToBuyCount()

    fun getItemDetailById(id: Long): Flow<ItemDetail?> = itemDao.getItemDetailById(id)

    fun getExpiringItems(thresholdTime: Long): Flow<List<ItemDetail>> =
        itemDao.getExpiringItems(thresholdTime)

    fun searchItems(query: String): Flow<List<ItemDetail>> = itemDao.searchItems(query)

    fun getItemsByCategory(categoryId: Long): Flow<List<ItemDetail>> =
        itemDao.getItemsByCategory(categoryId)

    fun getItemsByLocation(locationId: Long): Flow<List<ItemDetail>> =
        itemDao.getItemsByLocation(locationId)

    fun getActiveItemsByBarcode(barcode: String): Flow<List<ItemDetail>> =
        itemDao.getActiveItemsByBarcode(barcode)

    fun getItemsByBarcode(barcode: String): Flow<List<ItemDetail>> =
        itemDao.getItemsByBarcode(barcode)

    suspend fun getActiveItemsByBarcodeSync(barcode: String): List<Item> =
        itemDao.getActiveItemsByBarcodeSync(barcode)

    suspend fun getLatestItemByBarcode(barcode: String): Item? =
        itemDao.getLatestItemByBarcode(barcode)

    suspend fun incrementQuantity(id: Long, delta: Int) =
        itemDao.incrementQuantity(id, delta, MonotonicClock.now())

    suspend fun getItemById(id: Long): Item? = itemDao.getItemById(id)

    suspend fun insert(item: Item): Long = itemDao.insert(
        item.copy(
            syncId = item.syncId ?: UUID.randomUUID().toString(),
            updatedAt = MonotonicClock.now()
        )
    )

    suspend fun update(item: Item) = itemDao.update(
        item.copy(updatedAt = MonotonicClock.now())
    )

    suspend fun delete(item: Item) = itemDao.delete(item)

    /** 合并导入专用：保留备份中的 id 与 updatedAt，按原样落库。 */
    suspend fun insertSynced(item: Item): Long = itemDao.insert(item)

    /** 合并导入专用：原样覆盖本地记录，不刷新 updatedAt。 */
    suspend fun updateSynced(item: Item) = itemDao.update(item)

    suspend fun moveToTrash(item: Item) = itemDao.moveToTrash(
        id = item.id,
        deletedAt = MonotonicClock.now()
    )

    suspend fun restoreFromTrash(id: Long) =
        itemDao.restoreFromTrash(id, MonotonicClock.now())

    suspend fun restoreFromTrash(ids: List<Long>) {
        if (ids.isNotEmpty()) {
            itemDao.restoreItemsFromTrash(ids, MonotonicClock.now())
        }
    }

    suspend fun permanentlyDeleteFromTrash(ids: List<Long>): List<Item> {
        if (ids.isEmpty()) return emptyList()
        val deletedItems = itemDao.getDeletedItemsByIds(ids)
        if (deletedItems.isNotEmpty()) {
            itemDao.deleteDeletedItemsByIds(deletedItems.map { it.id })
        }
        return deletedItems
    }

    /**
     * 「用1件」：同一种物品多件时只消耗其中一件，数量减 1。
     * 减到 0 时自动记为已用完并默认勾选需要补货（与「用完」一致）。
     */
    suspend fun consumeOne(id: Long) {
        val item = itemDao.getItemById(id) ?: return
        val now = MonotonicClock.now()
        if (item.quantity > 1) {
            itemDao.incrementQuantity(id, -1, now)
            return
        }
        if (item.quantity == 1) {
            itemDao.incrementQuantity(id, -1, now)
        }
        itemDao.updateUsageStatus(id, Item.USAGE_USED_UP, now)
        itemDao.updateNeedRestock(id, true, now)
    }

    /** "用完"= 该买了：记为已用完并默认勾选需要补货，进入待买清单。 */
    suspend fun markAsUsed(id: Long) {
        val now = MonotonicClock.now()
        itemDao.updateUsageStatus(id, Item.USAGE_USED_UP, now)
        itemDao.updateNeedRestock(id, true, now)
    }

    /** 丢弃：去向改为已丢弃，并取消补货标记（不会再买同款）。 */
    suspend fun markAsDiscarded(id: Long) {
        val now = MonotonicClock.now()
        itemDao.updateDisposition(id, Item.DISPOSITION_DISCARDED, now)
        itemDao.updateNeedRestock(id, false, now)
    }

    /** 待买清单里勾选"已买到"：回到使用中并取消补货标记。 */
    suspend fun restoreToInUse(id: Long) {
        val now = MonotonicClock.now()
        itemDao.updateUsageStatus(id, Item.USAGE_IN_USE, now)
        itemDao.updateNeedRestock(id, false, now)
    }

    /** 详情页手动切换「使用进度」。 */
    suspend fun setUsageStatus(id: Long, usageStatus: Int) =
        itemDao.updateUsageStatus(id, usageStatus, MonotonicClock.now())

    /** 详情页手动切换「物品去向」。 */
    suspend fun setDisposition(id: Long, disposition: Int) =
        itemDao.updateDisposition(id, disposition, MonotonicClock.now())

    /** 详情页开关「需要补货」。 */
    suspend fun setNeedRestock(id: Long, needRestock: Boolean) =
        itemDao.updateNeedRestock(id, needRestock, MonotonicClock.now())

    /** 手动添加一个待买项：名称 + 数量 + 单位，可选关联分类。 */
    suspend fun addToBuyItem(
        name: String,
        quantity: Int,
        unit: String,
        categoryId: Long? = null
    ): Long =
        insert(
            Item(
                name = name.trim(),
                quantity = quantity.coerceAtLeast(1),
                unit = unit.trim().ifBlank { "件" },
                categoryId = categoryId,
                usageStatus = Item.USAGE_USED_UP,
                needRestock = true
            )
        )

    /** 自动补买用：待买清单里已有同名物品时跳过，避免重复添加。 */
    suspend fun addToBuyItemIfAbsent(
        name: String,
        quantity: Int,
        unit: String,
        categoryId: Long? = null
    ): Long? {
        val normalized = name.trim()
        if (normalized.isBlank()) return null
        val exists = itemDao.getToBuyNames().any { it.equals(normalized, ignoreCase = true) }
        if (exists) return null
        return addToBuyItem(normalized, quantity, unit, categoryId)
    }

    fun getActiveCount(): Flow<Int> = itemDao.getActiveCount()

    fun getExpiringCount(thresholdTime: Long): Flow<Int> = itemDao.getExpiringCount(thresholdTime)

    fun getTotalValue(): Flow<Double> = itemDao.getTotalValue()

    fun getRecycleCount(cutoffTime: Long): Flow<Int> = itemDao.getRecycleCount(cutoffTime)

    fun getCountByCategory(categoryId: Long): Flow<Int> = itemDao.getCountByCategory(categoryId)

    fun getCountByLocation(locationId: Long): Flow<Int> = itemDao.getCountByLocation(locationId)

    fun getExpiringItemsInRange(currentTime: Long, thresholdTime: Long): Flow<List<ItemDetail>> =
        itemDao.getExpiringItemsInRange(currentTime, thresholdTime)

    suspend fun purgeDeletedItemsOlderThan(cutoffTime: Long): List<Item> {
        val expiredItems = itemDao.getDeletedItemsBefore(cutoffTime)
        if (expiredItems.isNotEmpty()) {
            itemDao.purgeDeletedBefore(cutoffTime)
        }
        return expiredItems
    }

    suspend fun getAllItemsSnapshot(): List<Item> = itemDao.getAllItemsSnapshot()
}
