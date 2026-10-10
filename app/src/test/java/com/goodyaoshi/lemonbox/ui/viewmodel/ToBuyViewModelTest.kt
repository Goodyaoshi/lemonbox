package com.goodyaoshi.lemonbox.ui.viewmodel

import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.testing.FakeCategoryDao
import com.goodyaoshi.lemonbox.testing.FakeItemDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 待买清单 ViewModel 单测（F17）：
 * 覆盖「按分类筛选（父分类连带子分类）」与「手动添加的入参收敛」两条核心逻辑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToBuyViewModelTest {

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
    fun filteredItems_matchDescendantsOfSelectedParentCategory() = runTest(mainDispatcher) {
        val itemDao = FakeItemDao(
            mutableListOf(
                // 蔬菜是「食品」的子分类。
                toBuyItem(id = 100L, categoryId = 2L),
                toBuyItem(id = 101L, categoryId = 10L)
            )
        )
        val categoryDao = FakeCategoryDao(
            mutableListOf(
                Category(id = 1L, name = "食品"),
                Category(id = 2L, name = "蔬菜", parentId = 1L),
                Category(id = 10L, name = "日用品")
            )
        )
        val viewModel = ToBuyViewModel(ItemRepository(itemDao), CategoryRepository(categoryDao))

        val collectJob = launch { viewModel.filteredItems.collect() }

        // 未选分类时展示全部待买项。
        assertEquals(listOf(100L, 101L), viewModel.filteredItems.value.map { it.item.id })

        // 选中父分类「食品」，应连带命中子分类「蔬菜」下的物品。
        viewModel.setFilterCategory(1L)
        assertEquals(listOf(100L), viewModel.filteredItems.value.map { it.item.id })

        viewModel.setFilterCategory(10L)
        assertEquals(listOf(101L), viewModel.filteredItems.value.map { it.item.id })

        collectJob.cancel()
    }

    @Test
    fun addToBuy_ignoresBlankNameAndMarksNeedRestock() = runTest(mainDispatcher) {
        val itemDao = FakeItemDao()
        val viewModel = ToBuyViewModel(ItemRepository(itemDao), CategoryRepository(FakeCategoryDao()))

        viewModel.addToBuy(name = "   ", quantity = 1, unit = "件", categoryId = null)
        advanceUntilIdle()
        assertTrue(itemDao.snapshot().isEmpty())

        viewModel.addToBuy(name = " 洗洁精 ", quantity = 2, unit = "瓶", categoryId = 3L)
        advanceUntilIdle()

        val created = itemDao.snapshot().single()
        assertEquals("洗洁精", created.name)
        assertEquals(2, created.quantity)
        assertEquals("瓶", created.unit)
        assertEquals(3L, created.categoryId)
        assertEquals(true, created.needRestock)
    }

    /** 待买项：需要补货且尚未丢弃。 */
    private fun toBuyItem(id: Long, categoryId: Long?) = Item(
        id = id,
        name = "Item $id",
        categoryId = categoryId,
        usageStatus = Item.USAGE_USED_UP,
        needRestock = true
    )
}
