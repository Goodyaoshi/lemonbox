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

    /** 按使用进度取列表（F20 详情页 scope 直连 DAO 用）。 */
    fun getItemsByUsageStatus(usageStatus: Int): Flow<List<ItemDetail>> =
        itemDao.getItemsByUsageStatus(usageStatus)

    fun getTotalCount(): Flow<Int> = itemDao.getTotalCount()

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

    /**
     * 只登记图片槽位哈希（F22），刻意不走 [update]。
     *
     * 两点讲究：
     * - **不刷新 updatedAt**：图片刚传上去是同步的副产品，不是用户改了物品内容。
     *   若刷新时间戳，跨设备合并按「较新者胜」时本机会盖掉对方真实的编辑。
     * - **整列覆盖**：哈希列表达完整的槽位与顺序，只能整体替换。
     */
    suspend fun updateImageHashes(item: Item) =
        itemDao.updateImageHashes(item.id, item.imageHashes)

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

    /**
     * 永久删除：改写「永久删除墓碑」而不是物理删行（F21）。
     *
     * 物理删行会让这条记录从云端快照里彻底消失，其他设备因此认为「它没被改过」而永远保留，
     * 删除就同步不出去。改成写墓碑后，它随快照传到各设备，对方据此把本地那份也清掉。
     * 墓碑带 purgedAt 标记，因此不会出现在回收站，也只是暂存、到期由维护任务物理清除。
     */
    suspend fun permanentlyDeleteFromTrash(ids: List<Long>): List<Item> {
        if (ids.isEmpty()) return emptyList()
        val deletedItems = itemDao.getDeletedItemsByIds(ids)
        if (deletedItems.isNotEmpty()) {
            itemDao.markPurged(deletedItems.map { it.id }, MonotonicClock.now())
        }
        return deletedItems
    }

    /**
     * 合并时收到对方的永久删除墓碑：把这批 id 也标记为永久删除（F21）。
     *
     * 不直接物理删行，有两个理由：一是本地那份可能还活着（deletedAt 为 NULL），
     * 物理删除的 SQL 带 `deletedAt IS NOT NULL` 条件会漏掉它；二是本地留一份同样的
     * 墓碑可以继续向第三方设备传播这次删除，且重复收到同一墓碑时天然幂等。
     * 墓碑到期后由维护任务统一物理清除。
     */
    suspend fun purgeDeletedByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        itemDao.markPurged(ids, MonotonicClock.now())
    }

    /**
     * 「用1件」：同一种物品多件时只消耗其中一件，数量减 1。
     * 减到 0 时自动记为已用完并默认勾选需要补货（与「用完」一致）。
     * 持续使用型物品不扣数量（电器、调料等按使用天数统计，不走逐件消耗）。
     */
    suspend fun consumeOne(id: Long) {
        val item = itemDao.getItemById(id) ?: return
        if (item.trackMode == Item.TRACK_DURABLE) return
        val now = MonotonicClock.now()
        if (item.quantity > 1) {
            itemDao.consumeOnePiece(id, now)
            return
        }
        if (item.quantity == 1) {
            itemDao.consumeOnePiece(id, now)
        }
        itemDao.updateUsageStatus(id, Item.USAGE_USED_UP, now)
        itemDao.updateNeedRestock(id, true, now)
        writeUsageWindow(item, Item.USAGE_USED_UP, item.disposition, now)
    }

    /** "用完"= 该买了：记为已用完并默认勾选需要补货，进入待买清单；同时封口使用周期。 */
    suspend fun markAsUsed(id: Long) {
        val item = itemDao.getItemById(id) ?: return
        val now = MonotonicClock.now()
        itemDao.updateUsageStatus(id, Item.USAGE_USED_UP, now)
        itemDao.updateNeedRestock(id, true, now)
        writeUsageWindow(item, Item.USAGE_USED_UP, item.disposition, now)
    }

    /** 丢弃：去向改为已丢弃，取消补货标记，并封口使用周期。 */
    suspend fun markAsDiscarded(id: Long) {
        val item = itemDao.getItemById(id) ?: return
        val now = MonotonicClock.now()
        itemDao.updateDisposition(id, Item.DISPOSITION_DISCARDED, now)
        itemDao.updateNeedRestock(id, false, now)
        writeUsageWindow(item, item.usageStatus, Item.DISPOSITION_DISCARDED, now)
    }

    /**
     * 待买清单里勾选"已买到"：回到使用中并取消补货标记，
     * 同时记下购买日期、开启新一轮使用周期。
     */
    suspend fun restoreToInUse(id: Long) {
        val now = MonotonicClock.now()
        itemDao.updateUsageStatus(id, Item.USAGE_IN_USE, now)
        itemDao.updateNeedRestock(id, false, now)
        itemDao.updatePurchaseDate(id, now, now)
        itemDao.updateUsageWindow(id, now, null, now)
    }

    /** 撤销「已买到」：按勾选前的快照写回使用状态与周期，回到待买队列。 */
    suspend fun undoRestoreToInUse(previous: Item) {
        val now = MonotonicClock.now()
        itemDao.updateUsageStatus(previous.id, previous.usageStatus, now)
        itemDao.updateNeedRestock(previous.id, previous.needRestock, now)
        itemDao.updatePurchaseDate(previous.id, previous.purchaseDate, now)
        itemDao.updateUsageWindow(previous.id, previous.startUseTime, previous.usageEndedAt, now)
    }

    /** 详情页手动切换「使用进度」，同步维护使用周期窗口。 */
    suspend fun setUsageStatus(id: Long, usageStatus: Int) {
        val item = itemDao.getItemById(id) ?: return
        val now = MonotonicClock.now()
        itemDao.updateUsageStatus(id, usageStatus, now)
        writeUsageWindow(item, usageStatus, item.disposition, now)
    }

    /** 详情页手动切换「物品去向」，同步维护使用周期窗口。 */
    suspend fun setDisposition(id: Long, disposition: Int) {
        val item = itemDao.getItemById(id) ?: return
        val now = MonotonicClock.now()
        itemDao.updateDisposition(id, disposition, now)
        writeUsageWindow(item, item.usageStatus, disposition, now)
    }

    /** 开关「需要补货」（不涉及使用周期）。 */
    suspend fun setNeedRestock(id: Long, needRestock: Boolean) =
        itemDao.updateNeedRestock(id, needRestock, MonotonicClock.now())

    /**
     * 依最终状态计算使用周期窗口（开始使用, 使用结束）：
     * - 未使用：清空窗口；
     * - 已进入非可用状态（用完/送人/丢弃）：保留原开始时间（缺了用购买日期、创建时间兜底），
     *   结束时间缺失时补当前时刻；
     * - 从非可用状态回到可用：开启新一轮使用（开始=当前时刻，结束清空）；
     * - 一直在用（含借出）：保留原开始时间，缺了补当前时刻。
     */
    private fun usageWindowFor(
        item: Item,
        newUsageStatus: Int,
        newDisposition: Int,
        now: Long
    ): Pair<Long?, Long?> {
        if (newUsageStatus == Item.USAGE_UNUSED) return null to null
        val fallbackStart = Item.effectiveStartUseTime(
            item.startUseTime, item.purchaseDate, item.createdAt
        )
        return when {
            Item.isUsageEnded(newUsageStatus, newDisposition) ->
                fallbackStart to (item.usageEndedAt ?: now)

            Item.isUsageEnded(item.usageStatus, item.disposition) -> now to null

            else -> (item.startUseTime ?: now) to null
        }
    }

    private suspend fun writeUsageWindow(
        item: Item,
        newUsageStatus: Int,
        newDisposition: Int,
        now: Long
    ) {
        val (start, end) = usageWindowFor(item, newUsageStatus, newDisposition, now)
        itemDao.updateUsageWindow(item.id, start, end, now)
    }

    /** 手动添加一个待买项：名称 + 数量 + 单位，可选关联分类与归属。 */
    suspend fun addToBuyItem(
        name: String,
        quantity: Int,
        unit: String,
        categoryId: Long? = null,
        forWhom: String? = null
    ): Long =
        insert(
            Item(
                name = name.trim(),
                quantity = quantity.coerceAtLeast(1),
                unit = unit.trim().ifBlank { "件" },
                categoryId = categoryId,
                forWhom = forWhom,
                usageStatus = Item.USAGE_USED_UP,
                needRestock = true
            )
        )

    /** 编辑待买项：只改名称 / 数量 / 单位 / 分类 / 归属，其余字段（状态等）保持不变。 */
    suspend fun updateToBuyItem(
        item: Item,
        name: String,
        quantity: Int,
        unit: String,
        categoryId: Long?,
        forWhom: String? = null
    ) {
        val normalized = name.trim()
        if (normalized.isBlank()) return
        update(
            item.copy(
                name = normalized,
                quantity = quantity.coerceAtLeast(1),
                unit = unit.trim().ifBlank { "件" },
                categoryId = categoryId,
                forWhom = forWhom
            )
        )
    }

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

    /** 清到期的永久删除墓碑，保留期更长：墓碑要活到其他设备都拉到过它为止。 */
    suspend fun purgePurgedItemsOlderThan(cutoff: Long) = itemDao.purgePurgedBefore(cutoff)

    suspend fun getAllItemsSnapshot(): List<Item> = itemDao.getAllItemsSnapshot()
}
