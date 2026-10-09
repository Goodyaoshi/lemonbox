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

    /**
     * 清理同名同父级路径的重复分类。
     *
     * 根因：两台设备各自种子化了「食品/日用品/其他」等默认分类，syncId 各不相同；
     * 备份导入/局域网同步按 syncId 对齐，识别不出它们本是同一条，于是各留一份。
     *
     * 处理方式：按「完整路径名称」分组（祖先同时重复时路径名仍能对齐），
     * 每组保留一条，把下级分类与物品改挂到保留项后软删除多余项（写墓碑，同步给其他设备）。
     * 保留项的选择必须与设备无关：优先受保护，其次 syncId 字典序最小 —— 否则两台设备
     * 各自保留本地副本（数字 id 最小者）会互相把对方的副本删掉，最终两条都不剩。
     * 返回被清理的重复条数。
     */
    suspend fun deduplicateCategories(): Int {
        var removed = 0
        val now = MonotonicClock.now()
        // 祖先可能同时重复，清理后子孙才归并到一起，故循环到无重复为止（有界防意外）。
        repeat(4) {
            val active = categoryDao.getAllCategoriesSnapshot().filter { it.deletedAt == null }
            val byId = active.associateBy { it.id }
            val groups = active.groupBy { categoryPathKey(it, byId) }
            val targets = groups.values.filter { it.size > 1 }
            if (targets.isEmpty()) return removed
            targets.forEach { group ->
                val keeper = group.firstOrNull { it.isProtected }
                    ?: group.minByOrNull { it.syncId ?: "id:${it.id}" }!!
                group.forEach { dup ->
                    if (dup.id == keeper.id) return@forEach
                    // 必须先改挂子级与物品，再软删除：parentId 外键为 CASCADE，硬删会连带删掉子级。
                    categoryDao.reassignChildren(fromId = dup.id, toId = keeper.id)
                    categoryDao.reassignItemsToCategory(fromId = dup.id, toId = keeper.id)
                    categoryDao.softDelete(dup.id, now)
                    removed++
                }
            }
        }
        return removed
    }

    /** 沿 parentId 上溯拼接「食品/蔬菜/番茄」路径（按名称、忽略大小写），用于识别同一条分类。 */
    private fun categoryPathKey(category: Category, byId: Map<Long, Category>): String {
        val parts = mutableListOf<String>()
        var cursor: Category? = category
        var guard = 0
        while (cursor != null && guard++ < 64) {
            parts += cursor.name.trim().lowercase()
            cursor = cursor.parentId?.let { byId[it] }
        }
        return parts.reversed().joinToString("/")
    }

    /** 合并导入专用：保留备份中的 id 与 updatedAt。 */
    suspend fun insertSynced(category: Category): Long = categoryDao.insert(category)

    suspend fun updateSynced(category: Category) = categoryDao.update(category)
}
