package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.dao.LedgerAssetDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerBudgetDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerCategoryDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerRecordDao
import com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset
import com.goodyaoshi.lemonbox.data.local.entity.LedgerBudget
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.util.MonotonicClock
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 账户保存结果：成功，或与已有有效账户同名被守卫拦截。 */
enum class AssetSaveResult { SAVED, DUPLICATE_NAME }

/**
 * 记账仓库：账单、记账分类、账户、预算四张表的统一入口。
 * 备份与局域网同步只需注入这一个仓库。
 */
@Singleton
class LedgerRepository @Inject constructor(
    private val recordDao: LedgerRecordDao,
    private val categoryDao: LedgerCategoryDao,
    private val assetDao: LedgerAssetDao,
    private val budgetDao: LedgerBudgetDao
) {

    // ---------- 账单 ----------

    /** 某账期（[start, end)）的流水，时间倒序。 */
    fun observeRecordsBetween(start: Long, end: Long): Flow<List<LedgerRecord>> =
        recordDao.observeRecordsBetween(start, end)

    fun observeAssetsWithBalance() = assetDao.observeAssetsWithBalance()

    fun observeAssets(): Flow<List<LedgerAsset>> = assetDao.observeAssets()

    fun observeExpenseCategories(): Flow<List<LedgerCategory>> =
        categoryDao.observeByKind(LedgerCategory.KIND_EXPENSE)

    fun observeIncomeCategories(): Flow<List<LedgerCategory>> =
        categoryDao.observeByKind(LedgerCategory.KIND_INCOME)

    fun observeBudgets(): Flow<List<LedgerBudget>> = budgetDao.observeAll()

    suspend fun getRecord(id: Long): LedgerRecord? = recordDao.getById(id)

    /** 新增或更新账单；新增时补齐 syncId，更新时刷新 updatedAt。返回记录 id。 */
    suspend fun saveRecord(record: LedgerRecord): Long = if (record.id == 0L) {
        recordDao.insert(
            record.copy(
                syncId = record.syncId ?: UUID.randomUUID().toString(),
                updatedAt = MonotonicClock.now()
            )
        )
    } else {
        recordDao.update(record.copy(updatedAt = MonotonicClock.now()))
        record.id
    }

    suspend fun deleteRecord(id: Long) = recordDao.softDelete(id, MonotonicClock.now())

    /** 某账期支出/收入汇总（分）。 */
    suspend fun monthSums(start: Long, end: Long): Map<Int, Long> =
        recordDao.sumByTypeBetween(start, end)
            .associate { it.type to (it.total ?: 0L) }

    /** 某账期支出按分类聚合（分，金额降序），供统计页使用。 */
    suspend fun expenseByCategory(start: Long, end: Long) =
        recordDao.sumExpenseByCategoryBetween(start, end)

    // ---------- 记账分类 ----------

    suspend fun getCategory(id: Long): LedgerCategory? = categoryDao.getById(id)

    /** 新增或更新记账分类；返回分类 id。 */
    suspend fun saveCategory(category: LedgerCategory): Long = if (category.id == 0L) {
        categoryDao.insert(
            category.copy(
                syncId = category.syncId ?: UUID.randomUUID().toString(),
                updatedAt = MonotonicClock.now()
            )
        )
    } else {
        categoryDao.update(category.copy(updatedAt = MonotonicClock.now()))
        category.id
    }

    /**
     * 删除记账分类（软删）。内置保护分类（「其他」）不可删除，返回 false。
     * 历史账单的 categoryId 置空引用即可，不做级联改动。
     */
    suspend fun deleteCategory(id: Long): Boolean {
        val category = categoryDao.getById(id) ?: return false
        if (category.isProtected) return false
        categoryDao.softDelete(id, MonotonicClock.now())
        return true
    }

    // ---------- 账户 ----------

    suspend fun getAsset(id: Long): LedgerAsset? = assetDao.getById(id)

    /** 某账期某分类的流水（时间倒序）；categoryId 为 null 表示未分类。 */
    fun observeRecordsByCategoryBetween(
        kind: Int,
        categoryId: Long?,
        start: Long,
        end: Long
    ): Flow<List<LedgerRecord>> =
        recordDao.observeRecordsByCategoryBetween(kind, categoryId, start, end)

    /** 新增或更新账户；名称与其他有效账户同名（忽略大小写）时拒绝并返回 [AssetSaveResult.DUPLICATE_NAME]。 */
    suspend fun saveAsset(asset: LedgerAsset): AssetSaveResult {
        val normalizedName = asset.name.trim()
        // 同名守卫：与去重口径一致（忽略大小写、只看有效账户），拦截手动录入的重复账户。
        if (assetDao.countActiveByName(normalizedName, asset.id) > 0) {
            return AssetSaveResult.DUPLICATE_NAME
        }
        val target = asset.copy(name = normalizedName)
        if (target.id == 0L) {
            assetDao.insert(
                target.copy(
                    syncId = target.syncId ?: UUID.randomUUID().toString(),
                    updatedAt = MonotonicClock.now()
                )
            )
        } else {
            assetDao.update(target.copy(updatedAt = MonotonicClock.now()))
        }
        return AssetSaveResult.SAVED
    }

    /**
     * 删除账户（软删）。仍有未删除账单引用（支出/收入/转账任一端）时拒绝删除，
     * 返回 false，避免历史账单找不到账户。
     */
    suspend fun deleteAsset(id: Long): Boolean {
        val referenced = recordDao.countRecordsByAsset(id) > 0
        if (referenced) return false
        assetDao.softDelete(id, MonotonicClock.now())
        return true
    }

    // ---------- 预算 ----------

    /**
     * 设置月度预算：amountCents <= 0 表示取消该预算；categoryId 为 null 是总预算。
     * categoryId 唯一索引，覆盖式更新（含复活墓碑行，避免唯一冲突）。
     */
    suspend fun setBudget(categoryId: Long?, amountCents: Long) {
        val existing = budgetDao.getByCategoryId(categoryId)
        if (amountCents <= 0) {
            existing?.let { budgetDao.hardDelete(it.id) }
            return
        }
        if (existing == null) {
            budgetDao.insert(
                LedgerBudget(
                    categoryId = categoryId,
                    amount = amountCents,
                    syncId = UUID.randomUUID().toString(),
                    updatedAt = MonotonicClock.now()
                )
            )
        } else {
            budgetDao.update(
                existing.copy(
                    amount = amountCents,
                    deletedAt = null,
                    updatedAt = MonotonicClock.now()
                )
            )
        }
    }

    /** 各分类当月预算（categoryId 为 null 的键表示总预算）。 */
    fun budgetByCategory(budgets: List<LedgerBudget>): Map<Long?, Long> =
        budgets.associate { it.categoryId to it.amount }

    // ---------- 同名去重（合并导入/局域网同步后调用） ----------

    /**
     * 清理同名的重复记账分类（同一 kind 下按名称）。
     *
     * 根因：两台设备各自种子化了「餐饮/购物/其他」等默认分类，syncId 各不相同；
     * 按 syncId 对齐合并后识别不出它们本是同一条，于是各留一份，选择器里出现多个同名分类。
     *
     * 保留项规则必须与设备无关：优先受保护分类，其次 syncId 字典序最小 ——
     * 否则两台设备各自保留本地副本会互相把对方的副本删掉，最终两条都不剩。
     * 把账单与预算改挂到保留项后软删除多余项（写墓碑，同步给其他设备）。
     * 返回被清理的重复条数。
     */
    suspend fun deduplicateCategories(): Int {
        val active = categoryDao.getAllSnapshot().filter { it.deletedAt == null }
        val groups = active.groupBy { "${it.kind}|${it.name.trim().lowercase()}" }
            .values.filter { it.size > 1 }
        if (groups.isEmpty()) return 0
        val now = MonotonicClock.now()
        var removed = 0
        groups.forEach { group ->
            val keeper = group.firstOrNull { it.isProtected }
                ?: group.minByOrNull { it.syncId ?: "id:${it.id}" }!!
            group.forEach { dup ->
                if (dup.id == keeper.id) return@forEach
                recordDao.reassignCategory(dup.id, keeper.id)
                mergeBudgetIntoKeeper(dup.id, keeper.id, now)
                categoryDao.softDelete(dup.id, now)
                removed++
            }
        }
        return removed
    }

    /**
     * 清理同名的重复账户。
     *
     * 保留项规则与分类一致（syncId 字典序最小，与设备无关）；把账单的支出/收入端
     * 与转账转入端改挂到保留项后软删除多余项。返回被清理的重复条数。
     */
    suspend fun deduplicateAssets(): Int {
        val active = assetDao.getAllSnapshot().filter { it.deletedAt == null }
        val groups = active.groupBy { it.name.trim().lowercase() }.values.filter { it.size > 1 }
        if (groups.isEmpty()) return 0
        val now = MonotonicClock.now()
        var removed = 0
        groups.forEach { group ->
            val keeper = group.minByOrNull { it.syncId ?: "id:${it.id}" }!!
            group.forEach { dup ->
                if (dup.id == keeper.id) return@forEach
                recordDao.reassignAsset(dup.id, keeper.id)
                recordDao.reassignTargetAsset(dup.id, keeper.id)
                assetDao.softDelete(dup.id, now)
                removed++
            }
        }
        return removed
    }

    /** 预算的 categoryId 有唯一索引：保留项已有预算时只能丢弃重复项，否则把重复项的预算改挂过去。 */
    private suspend fun mergeBudgetIntoKeeper(fromCategoryId: Long, toCategoryId: Long, now: Long) {
        val dupBudget = budgetDao.getByCategoryId(fromCategoryId) ?: return
        if (budgetDao.getByCategoryId(toCategoryId) != null) {
            budgetDao.hardDelete(dupBudget.id)
        } else {
            budgetDao.update(dupBudget.copy(categoryId = toCategoryId, updatedAt = now))
        }
    }

    // ---------- 备份/同步 ----------

    suspend fun getAllRecordsSnapshot(): List<LedgerRecord> = recordDao.getAllSnapshot()

    suspend fun getAllCategoriesSnapshot(): List<LedgerCategory> = categoryDao.getAllSnapshot()

    suspend fun getAllAssetsSnapshot(): List<LedgerAsset> = assetDao.getAllSnapshot()

    suspend fun getAllBudgetsSnapshot(): List<LedgerBudget> = budgetDao.getAllSnapshot()

    /** 同步专用：原样插入（保留 syncId/updatedAt/墓碑），不刷新时间戳。 */
    suspend fun insertSyncedRecord(record: LedgerRecord): Long = recordDao.insert(record)

    suspend fun updateSyncedRecord(record: LedgerRecord) = recordDao.update(record)

    suspend fun insertSyncedCategory(category: LedgerCategory): Long = categoryDao.insert(category)

    suspend fun updateSyncedCategory(category: LedgerCategory) = categoryDao.update(category)

    suspend fun insertSyncedAsset(asset: LedgerAsset): Long = assetDao.insert(asset)

    suspend fun updateSyncedAsset(asset: LedgerAsset) = assetDao.update(asset)

    suspend fun insertSyncedBudget(budget: LedgerBudget): Long = budgetDao.insert(budget)

    suspend fun updateSyncedBudget(budget: LedgerBudget) = budgetDao.update(budget)
}
