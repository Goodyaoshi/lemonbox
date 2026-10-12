package com.goodyaoshi.lemonbox.data.sync

import kotlinx.serialization.Serializable

/**
 * 一次局域网同步的回执（S5）：记下这次动了多少条、有没有冲突与跳过，
 * 持久化成「同步历史」，事后能回答「昨天同步动了什么」。
 * 统计口径是**本机落地对方数据**的结果；对方落地本机的情况不在此记录。
 */
@Serializable
data class SyncReport(
    /** 发生时间（毫秒）。 */
    val time: Long,
    /** 本机在这次同步里的角色。 */
    val role: Role,
    /** 结果。 */
    val outcome: Outcome,
    /** 本机新增条数。 */
    val added: Int = 0,
    /** 本机更新条数。 */
    val updated: Int = 0,
    /** 本机执行的删除条数（来自勾选的删除墓碑）。 */
    val deleted: Int = 0,
    /** 勾选接受的真冲突条数（勾=采用对方）。 */
    val conflicts: Int = 0,
    /** 本机跳过（未勾选）的条数；下次同步会再出现。 */
    val skipped: Int = 0,
    /** 对方设备的名字。 */
    val peerLabel: String = ""
) {
    /** 本机在同步中的角色：发起连接的一方 / 展示配对码等待连接的一方。 */
    enum class Role(val label: String) {
        CLIENT("发起方"),
        HOST("被连接方")
    }

    enum class Outcome(val label: String) {
        SUCCESS("成功"),
        FAILED("失败"),
        CANCELLED("已取消")
    }
}
