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

        val collectJob = launch { viewModel.expirableItems.collect() }

        assertEquals(listOf(1L), viewModel.expirableItems.value.map { it.item.id })
        collectJob.cancel()
    }

    /** 在库、未删除的家当。 */
    private fun activeItem(id: Long) = Item(
        id = id,
        name = "Item $id",
        disposition = Item.DISPOSITION_IN_STOCK,
        deletedAt = null
    )
}
