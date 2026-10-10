package com.goodyaoshi.lemonbox.testing

import com.goodyaoshi.lemonbox.data.local.dao.CategoryDao
import com.goodyaoshi.lemonbox.data.local.entity.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 单元测试用的 [CategoryDao] 内存实现（F17）。
 *
 * 与 [FakeItemDao] 同样的思路：用内存 List 实现 DAO 接口，
 * 让依赖分类树的 ViewModel（如待买清单的父分类筛选）可在纯 JVM 单测中断言。
 */
class FakeCategoryDao(
    private val categories: MutableList<Category> = mutableListOf()
) : CategoryDao {

    fun snapshot(): List<Category> = categories.toList()

    override fun getAllCategories(): Flow<List<Category>> = flowOf(active())

    override fun getRootCategories(): Flow<List<Category>> =
        flowOf(active().filter { it.parentId == null })

    override fun getSubCategories(parentId: Long): Flow<List<Category>> =
        flowOf(active().filter { it.parentId == parentId })

    override suspend fun getCategoryById(id: Long): Category? =
        categories.firstOrNull { it.id == id }

    override suspend fun getAllCategoriesSnapshot(): List<Category> = snapshot()

    override suspend fun insert(category: Category): Long {
        categories += category
        return category.id
    }

    override suspend fun update(category: Category) {
        val index = categories.indexOfFirst { it.id == category.id }
        if (index >= 0) categories[index] = category
    }

    override suspend fun delete(category: Category) {
        categories.removeAll { it.id == category.id }
    }

    override suspend fun getCount(): Int = categories.size

    override suspend fun deleteAll() {
        categories.clear()
    }

    override suspend fun reassignChildren(fromId: Long, toId: Long) {
        categories.replaceAll { category ->
            if (category.parentId == fromId && category.id != toId) {
                category.copy(parentId = toId)
            } else {
                category
            }
        }
    }

    override suspend fun reassignItemsToCategory(fromId: Long, toId: Long) {
        // 物品改挂由 FakeItemDao 负责，这里无需处理。
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        categories.replaceAll { category ->
            if (category.id == id) category.copy(deletedAt = deletedAt, updatedAt = deletedAt) else category
        }
    }

    private fun active(): List<Category> = categories.filter { it.deletedAt == null }
}
