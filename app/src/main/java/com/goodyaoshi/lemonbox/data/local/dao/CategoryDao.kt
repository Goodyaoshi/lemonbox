package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.Category
import kotlinx.coroutines.flow.Flow

/**
 * 写操作一律带 `dirty = 1`（P6）：本机改动要被增量同步捞出来，漏标就会静默不上传。
 * [reassignItemsToCategory] 改的是 `items` 表，一并给那张表标脏——脏标记跟着被改的表走。
 */
@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories WHERE deletedAt IS NULL ORDER BY name ASC")
    fun getAllCategories(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE deletedAt IS NULL AND parentId IS NULL ORDER BY name ASC")
    fun getRootCategories(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE deletedAt IS NULL AND parentId = :parentId ORDER BY name ASC")
    fun getSubCategories(parentId: Long): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getCategoryById(id: Long): Category?

    /** 全量快照（含墓碑），供备份/同步合并使用。 */
    @Query("SELECT * FROM categories ORDER BY id ASC")
    suspend fun getAllCategoriesSnapshot(): List<Category>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: Category): Long

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun getCount(): Int

    @Query("DELETE FROM categories")
    suspend fun deleteAll()

    /** 把重复分类的下级分类改挂到保留项，供去重清理使用。 */
    @Query("UPDATE categories SET parentId = :toId, dirty = 1 WHERE parentId = :fromId AND id != :toId")
    suspend fun reassignChildren(fromId: Long, toId: Long)

    /** 把挂在重复分类上的物品改挂到保留项，供去重清理使用。 */
    @Query("UPDATE items SET categoryId = :toId, dirty = 1 WHERE categoryId = :fromId")
    suspend fun reassignItemsToCategory(fromId: Long, toId: Long)

    /** 软删除（写墓碑），去重清理用；保留记录以便把删除同步给其他设备。 */
    @Query("UPDATE categories SET deletedAt = :deletedAt, updatedAt = :deletedAt, dirty = 1 WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long)
}
