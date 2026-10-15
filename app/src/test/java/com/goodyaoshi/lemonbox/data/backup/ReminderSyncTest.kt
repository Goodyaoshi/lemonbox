package com.goodyaoshi.lemonbox.data.backup

import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.ReminderRepeatType
import com.goodyaoshi.lemonbox.data.repository.selectReminderDuplicates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒跨设备同步的两条关键规则：
 *
 * 1. [recomputeFireAt]：`nextFireAt` 是设备本地调度状态，合并时必须本地重算。
 * 2. [selectReminderDuplicates]：来源型提醒在多设备各自生成，收敛规则必须与设备无关。
 */
class ReminderSyncTest {

    private fun once(targetDate: String, nextFireAt: Long = 0L) = Reminder(
        id = 1,
        title = "解冻鸡肉",
        repeatType = ReminderRepeatType.ONCE.name,
        targetDate = targetDate,
        fireTime = "19:00",
        nextFireAt = nextFireAt
    )

    // —— recomputeFireAt：派生状态本地重算 ——

    @Test
    fun `合并时丢弃对方快照里的 nextFireAt 并本地重算`() {
        // 对方快照带一个陈旧的触发时间；本机应忽略它，按规则算出未来的时刻。
        val incoming = once(targetDate = "2099-01-01", nextFireAt = 123L)

        val merged = incoming.recomputeFireAt()

        assertNotEquals("不能沿用对方快照里的 nextFireAt", 123L, merged.nextFireAt)
        assertTrue("应重算为未来的触发时刻", merged.nextFireAt > System.currentTimeMillis())
    }

    @Test
    fun `重算不出未来时刻时退回本地既有值作为兜底`() {
        // 一次性提醒目标日期已过 → firstFireAt 返回 null → 用本地既有的 nextFireAt 兜底，
        // 避免把调度时间清零。
        val expired = once(targetDate = "2000-01-01", nextFireAt = 4_242L)

        assertEquals(4_242L, expired.recomputeFireAt(fallback = 4_242L).nextFireAt)
    }

    @Test
    fun `墓碑不参与调度重算`() {
        // 已删除的提醒无需触发时间：原样返回，避免无谓计算。
        val tombstone = once(targetDate = "2099-01-01", nextFireAt = 7L).copy(deletedAt = 5L)

        assertEquals(7L, tombstone.recomputeFireAt().nextFireAt)
    }

    // —— selectReminderDuplicates：来源型提醒收敛 ——

    @Test
    fun `同来源键保留 syncId 较小者其余待清理`() {
        val all = listOf(
            Reminder(id = 1, title = "准备明天的菜", sourceKey = "meal_prep:2026-10-05", syncId = "zzz"),
            Reminder(id = 2, title = "准备明天的菜", sourceKey = "meal_prep:2026-10-05", syncId = "aaa")
        )

        // 存活的是 syncId="aaa"（id=2），待清理的是 id=1。
        assertEquals(listOf(1L), selectReminderDuplicates(all))
    }

    @Test
    fun `手动提醒不参与去重即使标题相同`() {
        val all = listOf(
            Reminder(id = 1, title = "买菜", sourceKey = null, syncId = "a"),
            Reminder(id = 2, title = "买菜", sourceKey = null, syncId = "b"),
            Reminder(id = 3, title = "买菜", sourceKey = "", syncId = "c")
        )

        assertTrue("sourceKey 为空的手动提醒本就可重复，不收敛", selectReminderDuplicates(all).isEmpty())
    }

    @Test
    fun `已删除的墓碑不参与去重`() {
        val all = listOf(
            Reminder(id = 1, title = "解锁信", sourceKey = "capsule:x", syncId = "a"),
            Reminder(id = 2, title = "解锁信", sourceKey = "capsule:x", syncId = "b", deletedAt = 9L)
        )

        // 只剩一条活跃记录，无重复可清理。
        assertTrue(selectReminderDuplicates(all).isEmpty())
    }

    @Test
    fun `不同来源键各自独立不误伤`() {
        val all = listOf(
            Reminder(id = 1, title = "A", sourceKey = "meal_prep:2026-10-05", syncId = "a"),
            Reminder(id = 2, title = "B", sourceKey = "meal_prep:2026-10-06", syncId = "b"),
            Reminder(id = 3, title = "C", sourceKey = "capsule:y", syncId = "c")
        )

        assertTrue(selectReminderDuplicates(all).isEmpty())
    }
}
