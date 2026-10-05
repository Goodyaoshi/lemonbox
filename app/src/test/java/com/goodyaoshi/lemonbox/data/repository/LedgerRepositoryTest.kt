package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.dao.LedgerAssetDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerBudgetDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerCategoryDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerRecordDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerAssetWithBalance
import com.goodyaoshi.lemonbox.data.local.dao.LedgerCategorySum
import com.goodyaoshi.lemonbox.data.local.dao.LedgerTypeSum
import com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset
import com.goodyaoshi.lemonbox.data.local.entity.LedgerBudget
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerRepositoryTest {

    // ---------- 账单 ----------

    @Test
    fun saveRecord_newRecordFillsSyncIdAndUpdateAt() = runTest {
        val recordDao = FakeLedgerRecordDao()
        val repository = repository(recordDao)

        val id = repository.saveRecord(
            LedgerRecord(amount = 2500, type = LedgerRecord.TYPE_EXPENSE, syncId = null)
        )

        val saved = recordDao.records.single()
        assertEquals(id, saved.id)
        assertEquals(2500L, saved.amount)
        assertNotNull(saved.syncId)
        assertNotNull(saved.updatedAt)
    }

    @Test
    fun updateRecord_refreshesUpdatedAt() = runTest {
        val recordDao = FakeLedgerRecordDao()
        val repository = repository(recordDao)
        val id = repository.saveRecord(LedgerRecord(amount = 100, remark = "旧"))
        val before = recordDao.records.single()

        repository.saveRecord(before.copy(remark = "新"))

        val after = recordDao.records.single { it.id == id }
        assertEquals("新", after.remark)
        assertTrue(after.updatedAt!! >= before.updatedAt!!)
    }

    @Test
    fun deleteRecord_tombstonesAndExcludesFromMonthFlow() = runTest {
        val recordDao = FakeLedgerRecordDao()
        val repository = repository(recordDao)
        val id = repository.saveRecord(LedgerRecord(amount = 100, recordTime = 5_000L))

        repository.deleteRecord(id)

        val tombstoned = recordDao.records.single { it.id == id }
        assertNotNull(tombstoned.deletedAt)
        assertTrue(
            repository.observeRecordsBetween(0L, 10_000L).first().isEmpty()
        )
    }

    @Test
    fun monthSums_aggregatesExpenseAndIncome() = runTest {
        val recordDao = FakeLedgerRecordDao()
        val repository = repository(recordDao)
        listOf(
            LedgerRecord(amount = 1000, type = LedgerRecord.TYPE_EXPENSE, recordTime = 1_000L),
            LedgerRecord(amount = 500, type = LedgerRecord.TYPE_EXPENSE, recordTime = 2_000L),
            LedgerRecord(amount = 3000, type = LedgerRecord.TYPE_INCOME, recordTime = 3_000L)
        ).forEach { recordDao.insert(it) }

        val sums = repository.monthSums(0L, 10_000L)

        assertEquals(1500L, sums[LedgerRecord.TYPE_EXPENSE])
        assertEquals(3000L, sums[LedgerRecord.TYPE_INCOME])
    }

    // ---------- 记账分类 ----------

    @Test
    fun deleteCategory_protectedBlocked() = runTest {
        val categoryDao = FakeLedgerCategoryDao()
        val repository = repository(categoryDao = categoryDao)
        val id = categoryDao.insert(
            LedgerCategory(name = "其他", kind = LedgerCategory.KIND_EXPENSE, isProtected = true)
        )

        assertFalse(repository.deleteCategory(id))
        assertNull(categoryDao.categories.single().deletedAt)
    }

    @Test
    fun deleteCategory_unprotectedSoftDeleted() = runTest {
        val categoryDao = FakeLedgerCategoryDao()
        val repository = repository(categoryDao = categoryDao)
        val id = categoryDao.insert(LedgerCategory(name = "购物"))

        assertTrue(repository.deleteCategory(id))
        assertNotNull(categoryDao.categories.single().deletedAt)
    }

    // ---------- 账户 ----------

    @Test
    fun deleteAsset_blockedWhenReferencedByRecords() = runTest {
        val recordDao = FakeLedgerRecordDao()
        val assetDao = FakeLedgerAssetDao()
        val repository = repository(recordDao, assetDao = assetDao)
        val assetId = assetDao.insert(LedgerAsset(name = "微信"))
        recordDao.insert(
            LedgerRecord(
                amount = 100,
                type = LedgerRecord.TYPE_EXPENSE,
                assetId = assetId,
                recordTime = 1_000L
            )
        )

        assertFalse(repository.deleteAsset(assetId))
        assertNull(assetDao.assets.single().deletedAt)
    }

    @Test
    fun deleteAsset_allowedWhenUnused() = runTest {
        val recordDao = FakeLedgerRecordDao()
        val assetDao = FakeLedgerAssetDao()
        val repository = repository(recordDao, assetDao = assetDao)
        val assetId = assetDao.insert(LedgerAsset(name = "现金"))

        assertTrue(repository.deleteAsset(assetId))
        assertNotNull(assetDao.assets.single().deletedAt)
    }

    // ---------- 预算 ----------

    @Test
    fun setBudget_upsertsByCategoryWithoutDuplicates() = runTest {
        val budgetDao = FakeLedgerBudgetDao()
        val repository = repository(budgetDao = budgetDao)

        repository.setBudget(categoryId = null, amountCents = 300_000)
        repository.setBudget(categoryId = 7L, amountCents = 50_000)
        repository.setBudget(categoryId = 7L, amountCents = 60_000)

        val budgets = budgetDao.budgets
        assertEquals(2, budgets.size)
        assertEquals(300_000L, budgets.first { it.categoryId == null }.amount)
        assertEquals(60_000L, budgets.first { it.categoryId == 7L }.amount)
    }

    @Test
    fun setBudget_nonPositiveAmountRemovesBudget() = runTest {
        val budgetDao = FakeLedgerBudgetDao()
        val repository = repository(budgetDao = budgetDao)
        repository.setBudget(categoryId = null, amountCents = 300_000)

        repository.setBudget(categoryId = null, amountCents = 0)

        assertTrue(budgetDao.budgets.isEmpty())
    }

    // ---------- Fake DAO ----------

    private fun repository(
        recordDao: LedgerRecordDao = FakeLedgerRecordDao(),
        categoryDao: LedgerCategoryDao = FakeLedgerCategoryDao(),
        assetDao: LedgerAssetDao = FakeLedgerAssetDao(),
        budgetDao: LedgerBudgetDao = FakeLedgerBudgetDao()
    ): LedgerRepository = LedgerRepository(recordDao, categoryDao, assetDao, budgetDao)

    private class FakeLedgerRecordDao : LedgerRecordDao {
        val records = mutableListOf<LedgerRecord>()

        override fun observeRecordsBetween(start: Long, end: Long): Flow<List<LedgerRecord>> = flow {
            emit(
                records.filter {
                    it.deletedAt == null && it.recordTime >= start && it.recordTime < end
                }.sortedByDescending { it.recordTime }
            )
        }

        override suspend fun getById(id: Long): LedgerRecord? =
            records.firstOrNull { it.id == id }

        override suspend fun sumByTypeBetween(start: Long, end: Long): List<LedgerTypeSum> =
            records.filter { it.deletedAt == null && it.recordTime >= start && it.recordTime < end }
                .groupBy { it.type }
                .map { (type, list) -> LedgerTypeSum(type, list.sumOf { it.amount }) }

        override suspend fun sumExpenseByCategoryBetween(
            start: Long,
            end: Long
        ): List<LedgerCategorySum> =
            records.filter {
                it.deletedAt == null && it.type == LedgerRecord.TYPE_EXPENSE &&
                    it.recordTime >= start && it.recordTime < end
            }.groupBy { it.categoryId }
                .map { (categoryId, list) -> LedgerCategorySum(categoryId, list.sumOf { it.amount }) }
                .sortedByDescending { it.total ?: 0L }

        override suspend fun insert(record: LedgerRecord): Long {
            val id = (records.maxOfOrNull { it.id } ?: 0L) + 1
            records += record.copy(id = id)
            return id
        }

        override suspend fun update(record: LedgerRecord) {
            val index = records.indexOfFirst { it.id == record.id }
            require(index >= 0) { "更新不存在的账单" }
            records[index] = record
        }

        override suspend fun softDelete(id: Long, deletedAt: Long) {
            update(records.single { it.id == id }.copy(deletedAt = deletedAt, updatedAt = deletedAt))
        }

        override suspend fun getAllSnapshot(): List<LedgerRecord> = records.toList()

        override suspend fun getCount(): Int = records.size

        override suspend fun countRecordsByAsset(assetId: Long): Int =
            records.count {
                it.deletedAt == null && (it.assetId == assetId || it.targetAssetId == assetId)
            }
    }

    private class FakeLedgerCategoryDao : LedgerCategoryDao {
        val categories = mutableListOf<LedgerCategory>()

        override fun observeAll(): Flow<List<LedgerCategory>> = flow {
            emit(categories.filter { it.deletedAt == null })
        }

        override fun observeByKind(kind: Int): Flow<List<LedgerCategory>> = flow {
            emit(categories.filter { it.deletedAt == null && it.kind == kind })
        }

        override suspend fun getById(id: Long): LedgerCategory? =
            categories.firstOrNull { it.id == id }

        override suspend fun getAllSnapshot(): List<LedgerCategory> = categories.toList()

        override suspend fun insert(category: LedgerCategory): Long {
            val id = (categories.maxOfOrNull { it.id } ?: 0L) + 1
            categories += category.copy(id = id)
            return id
        }

        override suspend fun update(category: LedgerCategory) {
            val index = categories.indexOfFirst { it.id == category.id }
            require(index >= 0) { "更新不存在的分类" }
            categories[index] = category
        }

        override suspend fun softDelete(id: Long, deletedAt: Long) {
            update(categories.single { it.id == id }.copy(deletedAt = deletedAt, updatedAt = deletedAt))
        }

        override suspend fun getCount(): Int = categories.size
    }

    private class FakeLedgerAssetDao : LedgerAssetDao {
        val assets = mutableListOf<LedgerAsset>()

        // 余额 = 初始余额（真实聚合逻辑在 Room SQL 层，由装机验收覆盖）。
        override fun observeAssetsWithBalance(): Flow<List<LedgerAssetWithBalance>> = flow {
            emit(assets.map { it.toWithBalance(it.initialBalance) }.sortedBy { it.sort })
        }

        override fun observeAssets(): Flow<List<LedgerAsset>> = flow {
            emit(assets.filter { it.deletedAt == null })
        }

        override suspend fun getById(id: Long): LedgerAsset? = assets.firstOrNull { it.id == id }

        override suspend fun getAllSnapshot(): List<LedgerAsset> = assets.toList()

        override suspend fun insert(asset: LedgerAsset): Long {
            val id = (assets.maxOfOrNull { it.id } ?: 0L) + 1
            assets += asset.copy(id = id)
            return id
        }

        override suspend fun update(asset: LedgerAsset) {
            val index = assets.indexOfFirst { it.id == asset.id }
            require(index >= 0) { "更新不存在的账户" }
            assets[index] = asset
        }

        override suspend fun softDelete(id: Long, deletedAt: Long) {
            update(assets.single { it.id == id }.copy(deletedAt = deletedAt, updatedAt = deletedAt))
        }

        override suspend fun getCount(): Int = assets.size
    }

    private class FakeLedgerBudgetDao : LedgerBudgetDao {
        val budgets = mutableListOf<LedgerBudget>()

        override fun observeAll(): Flow<List<LedgerBudget>> = flow {
            emit(budgets.filter { it.deletedAt == null })
        }

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
            require(index >= 0) { "更新不存在的预算" }
            budgets[index] = budget
        }

        override suspend fun hardDelete(id: Long) {
            budgets.removeAll { it.id == id }
        }

        override suspend fun getCount(): Int = budgets.size
    }
}

private fun LedgerAsset.toWithBalance(balance: Long): LedgerAssetWithBalance =
    LedgerAssetWithBalance(
        id = id,
        name = name,
        icon = icon,
        sort = sort,
        initialBalance = initialBalance,
        type = type,
        syncId = syncId,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        balance = balance
    )
