package com.goodyaoshi.lemonbox.data.imagecloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置就绪度与同步结果判定的纯逻辑（F20）。
 *
 * 这两处虽小，却是「用户看到什么」与「后台要不要重试」的直接来源：
 * - [ImageCloudReadiness.firstBlocker] 决定设置页那句提示语，四个条件必须按固定优先级报，
 *   否则会出现「明明没开开关，却提示缺凭据」这种误导；
 * - [SyncRunResult.Done.allSucceeded] 决定后台任务是否重试，判错会导致无谓的空转重试，
 *   或者该重试时不重试（那就会真的漏传）。
 */
class ImageCloudReadinessTest {

    private fun readiness(
        supported: Boolean = true,
        hasConnectionConfig: Boolean = true,
        hasCredentials: Boolean = true,
        enabled: Boolean = true
    ) = ImageCloudReadiness(supported, hasConnectionConfig, hasCredentials, enabled)

    // ---------- ready ----------

    @Test
    fun ready_requiresAllFourConditions() {
        assertTrue(readiness().ready)

        assertFalse(readiness(supported = false).ready)
        assertFalse(readiness(hasConnectionConfig = false).ready)
        assertFalse(readiness(hasCredentials = false).ready)
        assertFalse(readiness(enabled = false).ready)
    }

    // ---------- firstBlocker：优先级必须稳定 ----------

    @Test
    fun firstBlocker_isNullWhenEverythingReady() {
        assertNull(readiness().firstBlocker)
    }

    @Test
    fun firstBlocker_reportsUnsupportedFirst() {
        // debug 包即使其他条件都满足，也必须先说「版本不支持」——
        // 否则用户会去填一堆永远用不上的配置。
        val state = readiness(
            supported = false,
            hasConnectionConfig = false,
            hasCredentials = false,
            enabled = false
        )
        assertEquals("当前版本不支持云存储，请使用正式版", state.firstBlocker)
    }

    @Test
    fun firstBlocker_reportsMissingConnectionBeforeMissingCredentials() {
        assertEquals(
            "缺少存储地址配置",
            readiness(hasConnectionConfig = false, hasCredentials = false, enabled = false).firstBlocker
        )
    }

    @Test
    fun firstBlocker_reportsMissingCredentialsBeforeDisabled() {
        assertEquals(
            "还未填写访问凭据",
            readiness(hasCredentials = false, enabled = false).firstBlocker
        )
    }

    @Test
    fun firstBlocker_reportsDisabledLast() {
        assertEquals("图片上传未开启", readiness(enabled = false).firstBlocker)
    }

    // ---------- 后台重试判定 ----------

    @Test
    fun done_allSucceededOnlyWhenNoFailures() {
        assertTrue(SyncRunResult.Done(uploaded = 3, skipped = 0, failed = 0).allSucceeded)
        // 全部跳过（都传过了）同样是成功——不该因此重试。
        assertTrue(SyncRunResult.Done(uploaded = 0, skipped = 5, failed = 0).allSucceeded)
        assertTrue(SyncRunResult.Done(uploaded = 0, skipped = 0, failed = 0).allSucceeded)

        assertFalse(SyncRunResult.Done(uploaded = 3, skipped = 1, failed = 1).allSucceeded)
        assertFalse(SyncRunResult.Done(uploaded = 0, skipped = 0, failed = 2).allSucceeded)
    }
}
