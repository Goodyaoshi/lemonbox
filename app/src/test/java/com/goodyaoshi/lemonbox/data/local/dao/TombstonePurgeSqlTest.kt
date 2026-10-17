package com.goodyaoshi.lemonbox.data.local.dao

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 墓碑清除的谓词是「删除动作能不能同步出去」的最后一道闸。
 *
 * 少一个条件就等于放行「本机还没传上去的墓碑」被物理清掉，对端那条会一直留着；
 * 而这段 SQL 只是拼在每条 purge 末尾的一个字符串常量，谁都能顺手「简化」掉一句，
 * 编译器与界面都不会有任何提示，故用单测把它钉住。
 */
class TombstonePurgeSqlTest {

    @Test
    fun `清除前提同时要求服务端已确认与未同步兜底`() {
        // 已被服务端确认：本机推成功或拉回来过这一条（序号才会大于 0）。
        assertTrue(TOMBSTONE_CONFIRMED_SQL.contains("dirty = 0"))
        assertTrue(TOMBSTONE_CONFIRMED_SQL.contains("serverSeq > 0"))
        // 从没成功同步过的安装（纯本地 / 还没登录多设备）没有对端要通知，
        // 用 server_ack 标记行兜底，维持旧的「满 30 天就清」行为。
        assertTrue(TOMBSTONE_CONFIRMED_SQL.contains("sync_state"))
        assertTrue(TOMBSTONE_CONFIRMED_SQL.contains("server_ack"))
        assertTrue(TOMBSTONE_CONFIRMED_SQL.contains("NOT EXISTS"))
    }
}
