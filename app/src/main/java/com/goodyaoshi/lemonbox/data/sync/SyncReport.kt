package com.goodyaoshi.lemonbox.data.sync

import com.goodyaoshi.lemonbox.data.backup.SyncChangeKind
import com.goodyaoshi.lemonbox.data.backup.SyncEntityKind
import kotlinx.serialization.Serializable

/**
 * 一次同步里一条变更的回执（GAP-1）：勾选时的逐条粒度在这里留痕，
 * 事后能回看「那次到底动了哪几条、哪些被我跳过了」，而不是只塌缩成几个数字。
 * 只保留最小必要信息（实体、种类、标题、是否冲突、是否采用），不含字段级 diff，避免历史膨胀。
 */
@Serializable
data class SyncReportEntry(
    val entity: SyncEntityKind,
    val kind: SyncChangeKind,
    val title: String,
    /** 是否为双方都改过的真冲突条目。 */
    val conflict: Boolean = false,
    /** 本机最终是否落地了这条：true=已采用（本机落地），false=本次跳过（下次同步会再问）。 */
    val adopted: Boolean
)

/**
 * 一次局域网同步的回执（S5）：记下这次动了多少条、有没有冲突与跳过，
 * 持久化成「同步历史」，事后能回答「昨天同步动了什么」。
 * 统计口径是**本机落地对方数据**的结果；对方落地本机的情况不在此记录。
 *
 * [entries]（GAP-1）保留本次逐条变更的摘要，供历史行点开后回放；旧记录没有该字段时为空列表，
 * 反序列化按默认值兼容（向后兼容）。
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
    val peerLabel: String = "",
    /** 本次逐条变更摘要（GAP-1）：勾有粒度，回看也有粒度。 */
    val entries: List<SyncReportEntry> = emptyList()
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
