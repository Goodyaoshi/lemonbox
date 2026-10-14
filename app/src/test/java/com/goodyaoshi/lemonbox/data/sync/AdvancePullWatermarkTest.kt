package com.goodyaoshi.lemonbox.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拉取水位线的推进规则（多设备同步里「不丢数据」的唯一决策点）。
 *
 * 水位线的语义是「早于它的都已合并」。因此一次失败之后的时间点**一律不能采纳**：
 * 一旦采纳，下次 `lastModified > watermark` 会把那份失败的数据永久排除，
 * 且没有任何报错、用户也察觉不到。下面的用例正是在锁这一点。
 */
class AdvancePullWatermarkTest {

    @Test
    fun advancesToLastSuccessfulWhenAllSucceed() {
        val result = advancePullWatermark(
            current = 100L,
            processed = listOf(
                200L to PullItemOutcome.Merged,
                300L to PullItemOutcome.Merged
            )
        )

        assertEquals(300L, result)
    }

    @Test
    fun stopsAtFirstFailureAndIgnoresLaterSuccesses() {
        // 关键用例：300 失败，400 成功——绝不能把水位线推到 400，
        // 否则 300 那份会被永远跳过。
        val result = advancePullWatermark(
            current = 100L,
            processed = listOf(
                200L to PullItemOutcome.Merged,
                300L to PullItemOutcome.Failed,
                400L to PullItemOutcome.Merged
            )
        )

        assertEquals(200L, result)
    }

    @Test
    fun failureOnFirstItemLeavesWatermarkUnchanged() {
        val result = advancePullWatermark(
            current = 100L,
            processed = listOf(
                200L to PullItemOutcome.Failed,
                300L to PullItemOutcome.Merged
            )
        )

        assertEquals(100L, result)
    }

    @Test
    fun goneObjectsAreCrossedSinceTheyNeverComeBack() {
        // 对象被别的设备清理了：它不会再回来，跨过去是安全的。
        val result = advancePullWatermark(
            current = 100L,
            processed = listOf(
                200L to PullItemOutcome.Gone,
                300L to PullItemOutcome.Merged
            )
        )

        assertEquals(300L, result)
    }

    @Test
    fun goneThenFailureStopsAtLastCrossedPoint() {
        val result = advancePullWatermark(
            current = 100L,
            processed = listOf(
                200L to PullItemOutcome.Gone,
                300L to PullItemOutcome.Failed,
                400L to PullItemOutcome.Merged
            )
        )

        assertEquals(200L, result)
    }

    @Test
    fun emptyProcessedListKeepsCurrentWatermark() {
        val result = advancePullWatermark(current = 100L, processed = emptyList())

        assertEquals(100L, result)
    }

    @Test
    fun neverMovesBackwards() {
        // 服务端时间理论上单调，但实现上仍要保证不会倒退——
        // 水位线倒退会导致同一批数据被反复合并。
        val result = advancePullWatermark(
            current = 500L,
            processed = listOf(
                200L to PullItemOutcome.Merged,
                300L to PullItemOutcome.Merged
            )
        )

        assertEquals(500L, result)
    }

    @Test
    fun firstSyncFromZeroAdvances() {
        val result = advancePullWatermark(
            current = 0L,
            processed = listOf(1_791_979_200_000L to PullItemOutcome.Merged)
        )

        assertEquals(1_791_979_200_000L, result)
    }
}
