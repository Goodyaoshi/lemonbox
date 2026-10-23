package com.goodyaoshi.lemonbox.ui.viewmodel

import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.testing.FakeItemDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 临期提醒 ViewModel 单测（F17）：
 * 只保留在库、且填了到期时间的家当，其余（无到期时间 / 已丢弃）不进入临期列表。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpiryViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun expirableItems_onlyIncludeInStockItemsWithExpireTime() = runTest(mainDispatcher) {
        val dao = FakeItemDao(
            mutableListOf(
                activeItem(id = 1L).copy(expireTime = 1_000L),
                // 没有到期时间：不进入临期列表。
                activeItem(id = 2L),
                // 已丢弃：不在库，即使有到期时间也排除。
                activeItem(id = 3L).copy(expireTime = 1_000L)
                    .copy(disposition = Item.DISPOSITION_DISCARDED)
            )
        )
        val viewModel = ExpiryViewModel(ItemRepository(dao))

        val collectJob = launch { viewModel.expirableItems.collect { } }

        assertEquals(listOf(1L), viewModel.expirableItems.value.map { it.item.id })
        collectJob.cancel()
    }

    @Test
    fun markAsUsed_setsUsedUpWithoutNeedRestock() = runTest(mainDispatcher) {
        val dao = FakeItemDao(mutableListOf(activeItem(id = 1L).copy(expireTime = 1_000L)))
        val viewModel = ExpiryViewModel(ItemRepository(dao))

        viewModel.markAsUsed(1L)

        val item = dao.snapshot().first { it.id == 1L }
        assertEquals(Item.USAGE_USED_UP, item.usageStatus)
        // 用完不再自动进待买：要买时由用户手动「加入待买」。
        assertEquals(false, item.needRestock)
    }

    @Test
    fun markAsDiscarded_setsDiscardedAndClearsNeedRestock() = runTest(mainDispatcher) {
        val dao = FakeItemDao(
            mutableListOf(
                activeItem(id = 1L).copy(expireTime = 1_000L, needRestock = true)
            )
        )
        val viewModel = ExpiryViewModel(ItemRepository(dao))

        viewModel.markAsDiscarded(1L)

        val item = dao.snapshot().first { it.id == 1L }
        assertEquals(Item.DISPOSITION_DISCARDED, item.disposition)
        assertEquals(false, item.needRestock)
    }

    /** 加入待买（F07）：只置「需要补货」位，使用状态保持不变。 */
    @Test
    fun addToBuy_setsNeedRestockWithoutChangingUsageStatus() = runTest(mainDispatcher) {
        val dao = FakeItemDao(
            mutableListOf(
                activeItem(id = 1L).copy(
                    expireTime = 1_000L,
                    usageStatus = Item.USAGE_IN_USE,
                    needRestock = false
                )
            )
        )
        val viewModel = ExpiryViewModel(ItemRepository(dao))

        viewModel.addToBuy(1L)

        val item = dao.snapshot().first { it.id == 1L }
        assertEquals(true, item.needRestock)
        assertEquals(Item.USAGE_IN_USE, item.usageStatus)
    }

    /** 在库、未删除的家当。 */
    private fun activeItem(id: Long) = Item(
        id = id,
        name = "Item $id",
        disposition = Item.DISPOSITION_IN_STOCK,
        deletedAt = null
    )
}
