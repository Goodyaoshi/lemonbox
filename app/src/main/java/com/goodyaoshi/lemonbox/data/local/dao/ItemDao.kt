package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {

    @Query(
        """
        SELECT items.*,
               categories.name AS categoryName,
               categories.icon AS categoryIcon,
               locations.name AS locationName
        FROM items
        LEFT JOIN categories ON items.categoryId = categories.id
        LEFT JOIN locations ON items.locationId = locations.id
        WHERE items.disposition = 0
          AND items.deletedAt IS NULL
        ORDER BY items.createdAt DESC
        """
    )
    fun getActiveItems(): Flow<List<ItemDetail>>

    @Query(
        """
        SELECT items.*,
               categories.name AS categoryName,
               categories.icon AS categoryIcon,
               locations.name AS locationName
        FROM items
        LEFT JOIN categories ON items.categoryId = categories.id
        LEFT JOIN locations ON items.locationId = locations.id
        WHERE items.deletedAt IS NULL
        ORDER BY items.createdAt DESC
        """
    )
    fun getAllItems(): Flow<List<ItemDetail>>

    @Query(
        """
        SELECT items.*,
               categories.name AS categoryName,
               categories.icon AS categoryIcon,
               locations.name AS locationName
        FROM items
        LEFT JOIN categories ON items.categoryId = categories.id
        LEFT JOIN locations ON items.locationId = locations.id
        WHERE items.usageStatus = :usageStatus
          AND items.deletedAt IS NULL
        ORDER BY items.createdAt DESC
        """
    )
    fun getItemsByUsageStatus(usageStatus: Int): Flow<List<ItemDetail>>

    @Query("SELECT COUNT(*) FROM items WHERE usageStatus = :usageStatus AND deletedAt IS NULL")
    fun getCountByUsageStatus(usageStatus: Int): Flow<Int>

    /** 待买清单：勾选了「需要补货」且尚未丢弃的物品。 */
    @Query(
        """
        SELECT items.*,
               categories.name AS categoryName,
               categories.icon AS categoryIcon,
               locations.name AS locationName
        FROM items
        LEFT JOIN categories ON items.categoryId = categories.id
        LEFT JOIN locations ON items.locationId = locations.id
        WHERE items.needRestock = 1
          AND items.disposition <> 3
          AND items.deletedAt IS NULL
        ORDER BY items.createdAt DESC
        """
    )
    fun getToBuyItems(): Flow<List<ItemDetail>>

    @Query(
        """
        SELECT COUNT(*) FROM items
        WHERE needRestock = 1
          AND disposition <> 3
          AND deletedAt IS NULL
        """
    )
    fun getToBuyCount(): Flow<Int>

    /** 待买清单里已有的物品名，供自动补买去重。 */
    @Query(
        """
        SELECT name FROM items
        WHERE needRestock = 1
          AND disposition <> 3
          AND deletedAt IS NULL
        """
    )
    suspend fun getToBuyNames(): List<String>

    @Query(
        """
        SELECT items.*,
               categories.name AS categoryName,
               categories.icon AS categoryIcon,
               locations.name AS locationName
        FROM items
        LEFT JOIN categories ON items.categoryId = categories.id
        LEFT JOIN locations ON items.locationId = locations.id
        WHERE items.id = :id
          AND items.deletedAt IS NULL
        """
    )
    fun getItemDetailById(id: Long): Flow<ItemDetail?>

    @Query(
        """
        SELECT items.*,
               categories.name AS categoryName,
               categories.icon AS categoryIcon,
               locations.name AS locationName
        FROM items
        LEFT JOIN categories ON items.categoryId = categories.id
        LEFT JOIN locations ON items.locationId = locations.id
        WHERE items.disposition = 0
          AND items.deletedAt IS NULL
          AND items.expireTime IS NOT NULL
          AND items.expireTime <= :thresholdTime
          AND items.expireTime > 0
        ORDER BY items.expireTime ASC
        """
    )
    fun getExpiringItems(thresholdTime: Long): Flow<List<ItemDetail>>

    @Query(
        """
        SELECT items.*,
               categories.name AS categoryName,
               categories.icon AS categoryIcon,
               locations.name AS locationName
        FROM items
        LEFT JOIN categories ON items.categoryId = categories.id
        LEFT JOIN locations ON items.locationId = locations.id
        WHERE items.disposition = 0
          AND items.deletedAt IS NULL
          AND (
              items.name LIKE '%' || :query || '%'
              OR categories.name LIKE '%' || :query || '%'
              OR locations.name LIKE '%' || :query || '%'
              OR items.note LIKE '%' || :query || '%'
          )
        ORDER BY items.createdAt DESC
        """
    )
    fun searchItems(query: String): Flow<List<ItemDetail>>

    @Query(
        """
        SELECT items.*,
               categories.name AS categoryName,
               categories.icon AS categoryIcon,
               locations.name AS locationName
        FROM items
        LEFT JOIN categories ON items.categoryId = categories.id
        LEFT JOIN locations ON items.locationId = locations.id
        WHERE items.disposition = 0
          AND items.deletedAt IS NULL
          AND items.categoryId = :categoryId
        ORDER BY items.createdAt DESC
        """
    )
    fun getItemsByCategory(categoryId: Long): Flow<List<ItemDetail>>

    @Query(
        """
        SELECT items.*,
               categories.name AS categoryName,
               categories.icon AS categoryIcon,
               locations.name AS locationName
        FROM items
        LEFT JOIN categories ON items.categoryId = categories.id
        LEFT JOIN locations ON items.locationId = locations.id
        WHERE items.disposition = 0
          AND items.deletedAt IS NULL
          AND items.locationId = :locationId
        ORDER BY items.createdAt DESC
        """
    )
    fun getItemsByLocation(locationId: Long): Flow<List<ItemDetail>>

    @Query(
        """
        SELECT items.*,
               categories.name AS categoryName,
               categories.icon AS categoryIcon,
               locations.name AS locationName
        FROM items
        LEFT JOIN categories ON items.categoryId = categories.id
        LEFT JOIN locations ON items.locationId = locations.id
        WHERE items.disposition = 0
          AND items.deletedAt IS NULL
          AND items.barcode = :barcode
        ORDER BY (items.expireTime IS NULL), items.expireTime ASC, items.createdAt DESC
        """
    )
    fun getActiveItemsByBarcode(barcode: String): Flow<List<ItemDetail>>

    @Query(
        """
        SELECT items.*,
               categories.name AS categoryName,
               categories.icon AS categoryIcon,
               locations.name AS locationName
        FROM items
        LEFT JOIN categories ON items.categoryId = categories.id
        LEFT JOIN locations ON items.locationId = locations.id
        WHERE items.deletedAt IS NULL
          AND items.barcode = :barcode
        ORDER BY items.disposition ASC, items.createdAt DESC
        """
    )
    fun getItemsByBarcode(barcode: String): Flow<List<ItemDetail>>

    @Query(
        """
        SELECT * FROM items
        WHERE deletedAt IS NULL
          AND disposition = 0
          AND barcode = :barcode
        ORDER BY createdAt DESC
        """
    )
    suspend fun getActiveItemsByBarcodeSync(barcode: String): List<Item>

    @Query(
        """
        SELECT * FROM items
        WHERE deletedAt IS NULL
          AND barcode = :barcode
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun getLatestItemByBarcode(barcode: String): Item?

    @Query("UPDATE items SET quantity = quantity + :delta, updatedAt = :updatedAt WHERE id = :id")
    suspend fun incrementQuantity(id: Long, delta: Int, updatedAt: Long)

    @Query("SELECT * FROM items WHERE id = :id AND deletedAt IS NULL")
    suspend fun getItemById(id: Long): Item?

    @Query("SELECT * FROM items ORDER BY id ASC")
    suspend fun getAllItemsSnapshot(): List<Item>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: Item): Long

    @Update
    suspend fun update(item: Item)

    @Delete
    suspend fun delete(item: Item)

    @Query("UPDATE items SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun moveToTrash(id: Long, deletedAt: Long)

    @Query("UPDATE items SET deletedAt = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun restoreFromTrash(id: Long, updatedAt: Long)

    @Query("UPDATE items SET deletedAt = NULL, updatedAt = :updatedAt WHERE id IN (:ids)")
    suspend fun restoreItemsFromTrash(ids: List<Long>, updatedAt: Long)

    @Query("UPDATE items SET usageStatus = :usageStatus, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateUsageStatus(id: Long, usageStatus: Int, updatedAt: Long)

    @Query("UPDATE items SET disposition = :disposition, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDisposition(id: Long, disposition: Int, updatedAt: Long)

    @Query("UPDATE items SET needRestock = :needRestock, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateNeedRestock(id: Long, needRestock: Boolean, updatedAt: Long)

    /** 使用周期窗口：开始使用 / 使用结束两个时间点，随状态流转由仓库层写入。 */
    @Query(
        "UPDATE items SET startUseTime = :startUseTime, usageEndedAt = :usageEndedAt, " +
            "updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateUsageWindow(
        id: Long,
        startUseTime: Long?,
        usageEndedAt: Long?,
        updatedAt: Long
    )

    /** 购买日期单独写入（如待买清单点「已买到」时自动记今天）。 */
    @Query("UPDATE items SET purchaseDate = :purchaseDate, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePurchaseDate(id: Long, purchaseDate: Long?, updatedAt: Long)

    @Query(
        """
        SELECT * FROM items
        WHERE disposition = 0
          AND deletedAt IS NULL
          AND expireTime IS NOT NULL
          AND expireTime > 0
        """
    )
    suspend fun getActiveItemsSync(): List<Item>

    @Query("SELECT COUNT(*) FROM items WHERE disposition = 0 AND deletedAt IS NULL")
    fun getActiveCount(): Flow<Int>

    /** 在库且尚未用完：家当「在库」的口径，也是「我的」页重点关注的库存量。 */
    @Query(
        "SELECT COUNT(*) FROM items WHERE disposition = 0 AND usageStatus <> 2 AND deletedAt IS NULL"
    )
    fun getAvailableCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM items
        WHERE disposition = 0
          AND deletedAt IS NULL
          AND expireTime IS NOT NULL
          AND expireTime <= :thresholdTime
          AND expireTime > 0
        """
    )
    fun getExpiringCount(thresholdTime: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM items WHERE deletedAt IS NULL")
    fun getTotalCount(): Flow<Int>

    /** 已用完且仍在库：用于「我的」页面的已用完指标。 */
    @Query("SELECT COUNT(*) FROM items WHERE usageStatus = 2 AND disposition = 0 AND deletedAt IS NULL")
    fun getUsedUpCount(): Flow<Int>

    @Query(
        "SELECT COALESCE(SUM(price * quantity), 0.0) FROM items " +
            "WHERE disposition = 0 AND deletedAt IS NULL"
    )
    fun getTotalValue(): Flow<Double>

    @Query(
        "SELECT COUNT(*) FROM items WHERE disposition = 0 AND deletedAt IS NULL " +
            "AND categoryId = :categoryId"
    )
    fun getCountByCategory(categoryId: Long): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM items WHERE disposition = 0 AND deletedAt IS NULL " +
            "AND locationId = :locationId"
    )
    fun getCountByLocation(locationId: Long): Flow<Int>

    @Query(
        """
        SELECT items.*,
               categories.name AS categoryName,
               categories.icon AS categoryIcon,
               locations.name AS locationName
        FROM items
        LEFT JOIN categories ON items.categoryId = categories.id
        LEFT JOIN locations ON items.locationId = locations.id
        WHERE items.deletedAt IS NOT NULL
          AND items.deletedAt >= :cutoffTime
        ORDER BY items.deletedAt DESC
        """
    )
    fun getRecycleItems(cutoffTime: Long): Flow<List<ItemDetail>>

    @Query("SELECT COUNT(*) FROM items WHERE deletedAt IS NOT NULL AND deletedAt >= :cutoffTime")
    fun getRecycleCount(cutoffTime: Long): Flow<Int>

    @Query("SELECT * FROM items WHERE deletedAt IS NOT NULL AND deletedAt < :cutoffTime")
    suspend fun getDeletedItemsBefore(cutoffTime: Long): List<Item>

    @Query("SELECT * FROM items WHERE id IN (:ids) AND deletedAt IS NOT NULL")
    suspend fun getDeletedItemsByIds(ids: List<Long>): List<Item>

    @Query("DELETE FROM items WHERE deletedAt IS NOT NULL AND deletedAt < :cutoffTime")
    suspend fun purgeDeletedBefore(cutoffTime: Long)

    @Query("DELETE FROM items WHERE id IN (:ids) AND deletedAt IS NOT NULL")
    suspend fun deleteDeletedItemsByIds(ids: List<Long>)

    @Query("DELETE FROM items")
    suspend fun deleteAll()

    @Query(
        """
        SELECT items.*,
               categories.name AS categoryName,
               categories.icon AS categoryIcon,
               locations.name AS locationName
        FROM items
        LEFT JOIN categories ON items.categoryId = categories.id
        LEFT JOIN locations ON items.locationId = locations.id
        WHERE items.disposition = 0
          AND items.deletedAt IS NULL
          AND items.expireTime IS NOT NULL
          AND items.expireTime <= :thresholdTime
          AND items.expireTime > 0
          AND items.expireTime > :currentTime
        ORDER BY items.expireTime ASC
        """
    )
    fun getExpiringItemsInRange(currentTime: Long, thresholdTime: Long): Flow<List<ItemDetail>>
}