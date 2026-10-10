package com.goodyaoshi.lemonbox.ui.viewmodel

import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 回收站 ViewModel 单测（F17）。
 *
 * `viewModelScope` 默认跑在 `Dispatchers.Main` 上，而纯 JVM 单测没有 Main 线程，
 * 因此用 `Dispatchers.setMain` 把它替换成测试调度器；`UnconfinedTestDispatcher`
 * 让协程即时推进，断言无需等待。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {

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
    fun deletedItems_includeOnlyItemsWithinRetentionWindow() = runTest(mainDispatcher) {
        val now = System.currentTimeMillis()
        val withinWindow = now - 1_000L
        // 超过 30 天保留期的记录不再出现在回收站。
        val beyondWindow = now - 40L * 24 * 60 * 60 * 1000
        val dao = FakeItemDao(
            mutableListOf(
                trashItem(id = 1L, deletedAt = withinWindow),
                trashItem(id = 2L, deletedAt = beyondWindow),
                trashItem(id = 3L, deletedAt = null)
            )
        )
        val viewModel = TrashViewModel(ItemRepository(dao))

        val collectJob = launch { viewModel.deletedItems.collect() }

        assertEquals(listOf(1L), viewModel.deletedItems.value.map { it.item.id })
        collectJob.cancel()
    }

    @Test
    fun toggleSelection_addsThenRemovesSameId() = runTest(mainDispatcher) {
        val viewModel = TrashViewModel(ItemRepository(FakeItemDao()))

        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(2L)
        assertEquals(setOf(1L, 2L), viewModel.selectedIds.value)

        // 再次点击同一项即取消勾选。
        viewModel.toggleSelection(1L)
        assertEquals(setOf(2L), viewModel.selectedIds.value)
    }

    @Test
    fun clearSelection_emptiesSelection() = runTest(mainDispatcher) {
        val viewModel = TrashViewModel(ItemRepository(FakeItemDao()))

        viewModel.selectAll(setOf(1L, 2L, 3L))
        viewModel.clearSelection()

        assertTrue(viewModel.selectedIds.value.isEmpty())
    }

    @Test
    fun pruneSelection_dropsIdsThatNoLongerExist() = runTest(mainDispatcher) {
        val viewModel = TrashViewModel(ItemRepository(FakeItemDao()))

        viewModel.selectAll(setOf(1L, 2L, 3L))
        // 列表刷新后只剩 2、3，勾选集里的 1 应被剔除。
        viewModel.pruneSelection(validIds = setOf(2L, 3L, 4L))

        assertEquals(setOf(2L, 3L), viewModel.selectedIds.value)
    }

    @Test
    fun restoreSelected_restoresItemsAndClearsSelection() = runTest(mainDispatcher) {
        val dao = FakeItemDao(
            mutableListOf(
                trashItem(id = 1L, deletedAt = 1_000L),
                trashItem(id = 2L, deletedAt = 1_000L)
            )
        )
        val viewModel = TrashViewModel(ItemRepository(dao))
        viewModel.selectAll(setOf(1L, 2L))

        viewModel.restoreSelected()
        advanceUntilIdle()

        assertNull(dao.snapshot().first { it.id == 1L }.deletedAt)
        assertNull(dao.snapshot().first { it.id == 2L }.deletedAt)
        assertTrue(viewModel.selectedIds.value.isEmpty())
    }

    @Test
    fun permanentlyDelete_removesItemsAndDropsThemFromSelection() = runTest(mainDispatcher) {
        val dao = FakeItemDao(
            mutableListOf(
                trashItem(id = 1L, deletedAt = 1_000L),
                trashItem(id = 2L, deletedAt = 1_000L)
            )
        )
        val viewModel = TrashViewModel(ItemRepository(dao))
        viewModel.selectAll(setOf(1L, 2L))

        viewModel.permanentlyDelete(listOf(1L))
        advanceUntilIdle()

        assertEquals(listOf(2L), dao.snapshot().map { it.id })
        assertEquals(setOf(2L), viewModel.selectedIds.value)
    }

    /** 回收站条目：已写删除时间戳，且不带图片以免单测触达 Android 的文件 API。 */
    private fun trashItem(id: Long, deletedAt: Long?) = Item(
        id = id,
        name = "Item $id",
        deletedAt = deletedAt,
        imagePath = ""
    )
}
