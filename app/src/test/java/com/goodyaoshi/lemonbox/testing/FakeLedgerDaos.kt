package com.goodyaoshi.lemonbox.testing

import com.goodyaoshi.lemonbox.data.local.dao.LedgerAssetDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerAssetWithBalance
import com.goodyaoshi.lemonbox.data.local.dao.LedgerBudgetDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerCategoryDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerCategorySum
import com.goodyaoshi.lemonbox.data.local.dao.LedgerMonthTypeSum
import com.goodyaoshi.lemonbox.data.local.dao.LedgerRecordDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerTypeSum
import com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset
import com.goodyaoshi.lemonbox.data.local.entity.LedgerBudget
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 记账四张表的极简内存实现（F17 单测用）：让需要 [com.goodyaoshi.lemonbox.data.repository.LedgerRepository]
 * 的 ViewModel 测试（如 ToBuyViewModel 的批量结账）能在纯 JVM 下构造仓库，无需 Room。
 * 只实现被 ViewModel 触达的少量方法，其余返回空值即可，避免每个测试各抄一份桩。
 */
class FakeLedgerRecordDao(
    val records: MutableList<LedgerRecord> = mutableListOf()
) : LedgerRecordDao {

    override fun observeRecordsBetween(start: Long, end: Long): Flow<List<LedgerRecord>> = flowOf(
        records.filter { it.deletedAt == null && it.recordTime in start until end }
    )

    override suspend fun getById(id: Long): LedgerRecord? = records.firstOrNull { it.id == id }

    override suspend fun getBySyncId(syncId: String): LedgerRecord? =
        records.firstOrNull { it.syncId == syncId }

    override suspend fun sumByTypeBetween(start: Long, end: Long): List<LedgerTypeSum> =
        records.filter { it.deletedAt == null && it.recordTime in start until end }
            .groupBy { it.type }
            .map { (type, list) -> LedgerTypeSum(type, list.sumOf { it.amount }) }

    override fun observeRecordsByCategoryBetween(
        kind: Int,
        categoryId: Long?,
        start: Long,
        end: Long
    ): Flow<List<LedgerRecord>> = flowOf(
        records.filter {
            it.deletedAt == null && it.type == kind &&
                it.recordTime in start until end && it.categoryId == categoryId
        }
    )

    override suspend fun sumExpenseByCategoryBetween(
        start: Long,
        end: Long
    ): List<LedgerCategorySum> =
        records.filter {
            it.deletedAt == null && it.type == LedgerRecord.TYPE_EXPENSE &&
                it.recordTime in start until end
        }.groupBy { it.categoryId }
            .map { (categoryId, list) -> LedgerCategorySum(categoryId, list.sumOf { it.amount }) }

    override fun observeMonthTypeSumsBetween(
        start: Long,
        end: Long,
        monthStartDay: Int
    ): Flow<List<LedgerMonthTypeSum>> = flowOf(emptyList())

    override fun observeRecordCountBetween(start: Long, end: Long): Flow<Int> = flowOf(0)

    override fun observeExpenseByCategoryBetween(
        start: Long,
        end: Long
    ): Flow<List<LedgerCategorySum>> = flowOf(emptyList())

    override suspend fun insert(record: LedgerRecord): Long {
        val id = (records.maxOfOrNull { it.id } ?: 0L) + 1
        records += record.copy(id = id)
        return id
    }

    override suspend fun update(record: LedgerRecord) {
        val index = records.indexOfFirst { it.id == record.id }
        if (index >= 0) records[index] = record
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        val index = records.indexOfFirst { it.id == id }
        if (index >= 0) records[index] = records[index].copy(deletedAt = deletedAt, updatedAt = deletedAt)
    }

    override suspend fun restoreFromTrash(id: Long, updatedAt: Long) {
        val index = records.indexOfFirst { it.id == id }
        if (index >= 0) records[index] = records[index].copy(deletedAt = null, updatedAt = updatedAt)
    }

    override suspend fun getAllSnapshot(): List<LedgerRecord> = records.toList()

    override suspend fun getCount(): Int = records.size

    override suspend fun countRecordsByAsset(assetId: Long): Int =
        records.count { it.deletedAt == null && (it.assetId == assetId || it.targetAssetId == assetId) }

    override suspend fun reassignCategory(fromId: Long, toId: Long) {
        records.replaceAll { if (it.categoryId == fromId) it.copy(categoryId = toId) else it }
    }

    override suspend fun reassignAsset(fromId: Long, toId: Long) {
        records.replaceAll { if (it.assetId == fromId) it.copy(assetId = toId) else it }
    }

    override suspend fun reassignTargetAsset(fromId: Long, toId: Long) {
        records.replaceAll { if (it.targetAssetId == fromId) it.copy(targetAssetId = toId) else it }
    }
}

class FakeLedgerCategoryDao(
    val categories: MutableList<LedgerCategory> = mutableListOf()
) : LedgerCategoryDao {

    override fun observeAll(): Flow<List<LedgerCategory>> =
        flowOf(categories.filter { it.deletedAt == null })

    override fun observeByKind(kind: Int): Flow<List<LedgerCategory>> =
        flowOf(categories.filter { it.deletedAt == null && it.kind == kind })

    override suspend fun getById(id: Long): LedgerCategory? = categories.firstOrNull { it.id == id }

    override suspend fun getAllSnapshot(): List<LedgerCategory> = categories.toList()

    override suspend fun insert(category: LedgerCategory): Long {
        val id = (categories.maxOfOrNull { it.id } ?: 0L) + 1
        categories += category.copy(id = id)
        return id
    }

    override suspend fun update(category: LedgerCategory) {
        val index = categories.indexOfFirst { it.id == category.id }
        if (index >= 0) categories[index] = category
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        val index = categories.indexOfFirst { it.id == id }
        if (index >= 0) categories[index] = categories[index].copy(deletedAt = deletedAt, updatedAt = deletedAt)
    }

    override suspend fun getCount(): Int = categories.size
}

class FakeLedgerAssetDao(
    val assets: MutableList<LedgerAsset> = mutableListOf()
) : LedgerAssetDao {

    override fun observeAssetsWithBalance(): Flow<List<LedgerAssetWithBalance>> = flowOf(emptyList())

    override fun observeAssets(): Flow<List<LedgerAsset>> =
        flowOf(assets.filter { it.deletedAt == null })

    override suspend fun getById(id: Long): LedgerAsset? = assets.firstOrNull { it.id == id }

    override suspend fun countActiveByName(name: String, excludeId: Long): Int =
        assets.count { it.deletedAt == null && it.id != excludeId && it.name.equals(name, true) }

    override suspend fun getAllSnapshot(): List<LedgerAsset> = assets.toList()

    override suspend fun insert(asset: LedgerAsset): Long {
        val id = (assets.maxOfOrNull { it.id } ?: 0L) + 1
        assets += asset.copy(id = id)
        return id
    }

    override suspend fun update(asset: LedgerAsset) {
        val index = assets.indexOfFirst { it.id == asset.id }
        if (index >= 0) assets[index] = asset
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        val index = assets.indexOfFirst { it.id == id }
        if (index >= 0) assets[index] = assets[index].copy(deletedAt = deletedAt, updatedAt = deletedAt)
    }

    override suspend fun getCount(): Int = assets.size
}

class FakeLedgerBudgetDao(
    val budgets: MutableList<LedgerBudget> = mutableListOf()
) : LedgerBudgetDao {

    override fun observeAll(): Flow<List<LedgerBudget>> =
        flowOf(budgets.filter { it.deletedAt == null })

    override suspend fun getAllSnapshot(): List<LedgerBudget> = budgets.toList()

    override suspend fun getByCategoryId(categoryId: Long?): LedgerBudget? =
        budgets.firstOrNull { it.categoryId == categoryId }

    override suspend fun insert(budget: LedgerBudget): Long {
        val id = (budgets.maxOfOrNull { it.id } ?: 0L) + 1
        budgets += budget.copy(id = id)
        return id
    }

    override suspend fun update(budget: LedgerBudget) {
        val index = budgets.indexOfFirst { it.id == budget.id }
        if (index >= 0) budgets[index] = budget
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        val index = budgets.indexOfFirst { it.id == id }
        if (index >= 0) budgets[index] = budgets[index].copy(deletedAt = deletedAt, updatedAt = deletedAt)
    }

    override suspend fun getCount(): Int = budgets.size
}
