package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.dao.CategoryDao
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.util.MonotonicClock
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao
) {
    fun getAllCategories(): Flow<List<Category>> = categoryDao.getAllCategories()

    fun getRootCategories(): Flow<List<Category>> = categoryDao.getRootCategories()

    fun getSubCategories(parentId: Long): Flow<List<Category>> = categoryDao.getSubCategories(parentId)

    suspend fun getCategoryById(id: Long): Category? = categoryDao.getCategoryById(id)

    suspend fun getAllCategoriesSnapshot(): List<Category> = categoryDao.getAllCategoriesSnapshot()

    /** 取某个分类及其全部下级分类的 id（不含墓碑）。 */
    suspend fun getSubtreeIds(rootId: Long): List<Long> {
        val all = categoryDao.getAllCategoriesSnapshot().filter { it.deletedAt == null }
        return collectDescendantIds(rootId, all) + rootId
    }

    suspend fun insert(category: Category): Long = categoryDao.insert(category)

    suspend fun update(category: Category) {
        // 内置保护分类（食品及其食材子树）是菜谱匹配与默认规则的基础，不允许改写。
        if (category.isProtected) return
        categoryDao.update(category.copy(updatedAt = MonotonicClock.now()))
    }

    /**
     * 软删除（写墓碑），并连带其所有下级分类（子分类、孙分类…），
     * 记录会保留以便把删除同步给其他设备。受保护分类直接跳过。
     */
    suspend fun delete(category: Category) {
        if (category.isProtected) return
        val now = MonotonicClock.now()
        val all = categoryDao.getAllCategoriesSnapshot()
        val targets = collectDescendantIds(category.id, all) + category.id
        targets.forEach { id ->
            all.firstOrNull { it.id == id }?.let { entity ->
                categoryDao.update(entity.copy(deletedAt = now, updatedAt = now))
            }
        }
    }

    private fun collectDescendantIds(rootId: Long, all: List<Category>): List<Long> {
        val result = mutableListOf<Long>()
        val pending = ArrayDeque<Long>().apply { add(rootId) }
        while (pending.isNotEmpty()) {
            val parentId = pending.removeFirst()
            all.filter { it.parentId == parentId }.forEach { child ->
                if (result.add(child.id)) {
                    pending.add(child.id)
                }
            }
        }
        return result
    }

    /** 合并导入专用：保留备份中的 id 与 updatedAt。 */
    suspend fun insertSynced(category: Category): Long = categoryDao.insert(category)

    suspend fun updateSynced(category: Category) = categoryDao.update(category)
}
