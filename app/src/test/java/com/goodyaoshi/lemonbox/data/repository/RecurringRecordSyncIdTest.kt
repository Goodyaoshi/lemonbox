package com.goodyaoshi.lemonbox.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周期生成账单的确定性 syncId（F05）。
 *
 * 为什么值得单独测：周期规则现在会同步到多台设备，每台都会各自跑
 * fireDueRules()。若两边给同一期账单算出**不同**的 syncId，合并后就是两份
 * 重复账单——金额翻倍、统计失真，而从界面上完全看不出是同步出的问题。
 * 反过来，若算得太"宽松"把不同期的账单算成同一个，则会丢账单。
 *
 * 纯字符串推导，不碰 Android 框架。
 */
class RecurringRecordSyncIdTest {

    // ---------- 确定性：同规则同期 → 同 id（跨设备去重的根据） ----------

    @Test
    fun `同一规则同一期得到相同 syncId`() {
        val first = recurringRecordSyncId("rule-abc", ruleId = 7, fireAt = 1_700_000_000_000L)
        val second = recurringRecordSyncId("rule-abc", ruleId = 7, fireAt = 1_700_000_000_000L)

        assertEquals(first, second)
    }

    /**
     * 关键：两台设备的本地 ruleId 不同（各自自增主键），但 syncId 相同。
     * 去重必须只依赖 syncId + 触发时刻，绝不能掺进本地 id。
     */
    @Test
    fun `本地 ruleId 不同不影响结果`() {
        val onDeviceA = recurringRecordSyncId("rule-abc", ruleId = 3, fireAt = 1_700_000_000_000L)
        val onDeviceB = recurringRecordSyncId("rule-abc", ruleId = 91, fireAt = 1_700_000_000_000L)

        assertEquals(onDeviceA, onDeviceB)
    }

    // ---------- 区分性：不同期 / 不同规则 → 不同 id（不丢账单的根据） ----------

    @Test
    fun `同一规则不同期得到不同 syncId`() {
        val january = recurringRecordSyncId("rule-abc", ruleId = 7, fireAt = 1_700_000_000_000L)
        val february = recurringRecordSyncId("rule-abc", ruleId = 7, fireAt = 1_702_678_400_000L)

        assertNotEquals(january, february)
    }

    @Test
    fun `不同规则同一时刻得到不同 syncId`() {
        val rent = recurringRecordSyncId("rule-rent", ruleId = 1, fireAt = 1_700_000_000_000L)
        val salary = recurringRecordSyncId("rule-salary", ruleId = 2, fireAt = 1_700_000_000_000L)

        assertNotEquals(rent, salary)
    }

    // ---------- 边界：旧数据缺 syncId 时的回退 ----------

    @Test
    fun `ruleSyncId 为空时回退到本地 id 并保持稳定`() {
        val first = recurringRecordSyncId(null, ruleId = 12, fireAt = 1_700_000_000_000L)
        val second = recurringRecordSyncId(null, ruleId = 12, fireAt = 1_700_000_000_000L)

        // 本机重复运行不产生重复账单。
        assertEquals(first, second)
        assertTrue(first.contains("12"))
    }

    @Test
    fun `ruleSyncId 为空白串时同样回退`() {
        val blank = recurringRecordSyncId("   ", ruleId = 12, fireAt = 1_700_000_000_000L)
        val nullValue = recurringRecordSyncId(null, ruleId = 12, fireAt = 1_700_000_000_000L)

        assertEquals(nullValue, blank)
    }

    /** 前缀区分不同来源，避免与手动记账的随机 UUID 语义混淆。 */
    @Test
    fun `syncId 带 recurring 前缀且含触发时刻`() {
        val id = recurringRecordSyncId("rule-abc", ruleId = 7, fireAt = 1_700_000_000_000L)

        assertTrue(id.startsWith("recurring:"))
        assertTrue(id.contains("rule-abc"))
        assertTrue(id.contains("1700000000000"))
    }
}
