package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.dao.AnniversaryDao
import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AnniversaryRepositoryTest {

    // ---------- 同名去重 ----------

    @Test
    fun deduplicateAnniversaries_keepsSmallestSyncIdAndSoftDeletesRest() = runTest {
        val dao = FakeAnniversaryDao()
        val keeper = dao.seed(
            Anniversary(name = "在一起", date = "2020-05-20", type = Anniversary.TYPE_COUNTUP, syncId = "aaa")
        )
        dao.seed(
            Anniversary(name = "在一起", date = "2020-05-20", type = Anniversary.TYPE_COUNTUP, syncId = "zzz")
        )
        val repository = AnniversaryRepository(dao)

        assertEquals(1, repository.deduplicateAnniversaries())

        val active = dao.getAllSnapshot().filter { it.deletedAt == null }
        assertEquals(1, active.size)
        assertEquals(keeper.id, active.single().id)
        // 落败项写墓碑，随备份/同步传给对端，避免对端再次生成副本。
        assertNotNull(dao.getAllSnapshot().first { it.syncId == "zzz" }.deletedAt)
    }

    @Test
    fun deduplicateAnniversaries_groupsIgnoringNameCaseAndPadding() = runTest {
        val dao = FakeAnniversaryDao()
        dao.seed(Anniversary(name = "旅游", date = "2026-10-01", syncId = "b"))
        dao.seed(Anniversary(name = " 旅游 ", date = "2026-10-01", syncId = "a"))
        val repository = AnniversaryRepository(dao)

        assertEquals(1, repository.deduplicateAnniversaries())

        val active = dao.getAllSnapshot().filter { it.deletedAt == null }
        assertEquals(1, active.size)
        assertEquals("a", active.single().syncId)
    }

    @Test
    fun deduplicateAnniversaries_keepsDifferentDateOrType() = runTest {
        val dao = FakeAnniversaryDao()
        dao.seed(Anniversary(name = "考试", date = "2026-10-01", type = Anniversary.TYPE_COUNTDOWN))
        dao.seed(Anniversary(name = "考试", date = "2026-11-01", type = Anniversary.TYPE_COUNTDOWN))
        dao.seed(Anniversary(name = "考试", date = "2026-10-01", type = Anniversary.TYPE_COUNTUP))
        val repository = AnniversaryRepository(dao)

        assertEquals(0, repository.deduplicateAnniversaries())
        assertEquals(3, dao.getAllSnapshot().count { it.deletedAt == null })
    }

    // ---------- 录入同名守卫 ----------

    @Test
    fun save_blocksDuplicateNameDateTypeIgnoringCaseAndPadding() = runTest {
        val dao = FakeAnniversaryDao()
        val repository = AnniversaryRepository(dao)

        assertEquals(
            AnniversarySaveResult.SAVED,
            repository.save(Anniversary(name = "结婚纪念日", date = "2019-09-09"))
        )
        assertEquals(
            AnniversarySaveResult.DUPLICATE_NAME,
            repository.save(Anniversary(name = " 结婚纪念日 ", date = "2019-09-09"))
        )
        assertEquals(1, dao.getAllSnapshot().count { it.deletedAt == null })
    }

    @Test
    fun save_duplicateGuardIgnoresTombstonesAndSelf() = runTest {
        val dao = FakeAnniversaryDao()
        val repository = AnniversaryRepository(dao)
        repository.save(Anniversary(name = "生日", date = "2000-01-01", type = Anniversary.TYPE_BIRTHDAY))
        val firstId = dao.getAllSnapshot().single().id

        // 软删后同名同日期可以重新建立。
        repository.softDelete(dao.getById(firstId)!!)
        assertEquals(
            AnniversarySaveResult.SAVED,
            repository.save(Anniversary(name = "生日", date = "2000-01-01", type = Anniversary.TYPE_BIRTHDAY))
        )

        // 编辑自己：名称不变（仅多空格）应当放行。
        val activeId = dao.getAllSnapshot().single { it.deletedAt == null }.id
        val current = dao.getById(activeId)!!
        assertEquals(
            AnniversarySaveResult.SAVED,
            repository.save(current.copy(name = " 生日 "))
        )
        assertNull(dao.getById(activeId)!!.deletedAt)
    }

    /** 内存版 DAO：只实现仓库测试用得到的行为。 */
    private class FakeAnniversaryDao : AnniversaryDao {
        private val items = mutableListOf<Anniversary>()
        private var nextId = 1L

        fun seed(anniversary: Anniversary): Anniversary {
            val stored = anniversary.copy(id = nextId++)
            items += stored
            return stored
        }

        override fun getActiveAnniversaries(): Flow<List<Anniversary>> =
            flow { emit(items.filter { it.enabled && it.deletedAt == null }) }

        override fun getAllAnniversaries(): Flow<List<Anniversary>> =
            flow { emit(items.filter { it.deletedAt == null }) }

        override suspend fun getAllSnapshot(): List<Anniversary> = items.toList()

        override suspend fun getActiveSnapshot(): List<Anniversary> =
            items.filter { it.enabled && it.deletedAt == null }

        override suspend fun getById(id: Long): Anniversary? = items.firstOrNull { it.id == id }

        override fun getByIdFlow(id: Long): Flow<Anniversary?> =
            flow { emit(items.firstOrNull { it.id == id && it.deletedAt == null }) }

        override fun observeCount(): Flow<Int> = flow { emit(items.count { it.deletedAt == null }) }

        override suspend fun countActiveByNameDateType(
            name: String,
            date: String,
            type: Int,
            excludeId: Long
        ): Int = items.count {
            it.deletedAt == null && it.type == type && it.date == date &&
                it.name.trim().lowercase() == name.trim().lowercase() && it.id != excludeId
        }

        override suspend fun insert(anniversary: Anniversary): Long {
            val stored = anniversary.copy(id = nextId++)
            items += stored
            return stored.id
        }

        override suspend fun update(anniversary: Anniversary) {
            val index = items.indexOfFirst { it.id == anniversary.id }
            if (index >= 0) items[index] = anniversary
        }

        override suspend fun softDelete(id: Long, deletedAt: Long, updatedAt: Long) {
            val index = items.indexOfFirst { it.id == id }
            if (index >= 0) {
                items[index] = items[index].copy(deletedAt = deletedAt, updatedAt = updatedAt)
            }
        }

        override suspend fun purgeDeletedOlderThan(cutoff: Long) {
            items.removeAll { it.deletedAt != null && it.deletedAt < cutoff }
        }
    }
}
