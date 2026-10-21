package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 同步的本地状态位（P6）：一个键值表，存「不适合塞进任何一张实体表」的全局进度标记。
 *
 * 用一张表而不是 [com.goodyaoshi.lemonbox.data.settings.AppPreferences] 的偏好项，
 * 是为了让这些标记能和「拉取落地」写在一个事务里——水位若存偏好、数据存 Room，
 * 中途被杀进程会出现「数据已落地、水位没推进」（下次重复拉）或反过来的错位。
 */
@Entity(tableName = "sync_state")
data class SyncStateRow(
    @PrimaryKey
    val key: String,
    val value: Long
)

/** [SyncStateRow] 用到的键。 */
object SyncStateKeys {
    /** 已拉取到的服务端序号水位：`GET /v1/pull?since=` 的 since 值。 */
    const val PULL_WATERMARK = "pull_watermark"

    /**
     * 「本机至少成功同步过一次」的标记（值恒为 1，仅以行是否存在作判据）。
     *
     * 墓碑物理清除（P6-d）以它作前提：从没同步过时维持旧的 TTL 行为，
     * 一旦同步过只清「已被服务端确认」的墓碑，避免把别的设备还没拉到的删除记录清掉。
     */
    const val SERVER_ACK = "server_ack"

    /**
     * `preferences` 这条整块设置记录的服务端序号。
     *
     * 偏好没有 dirty/serverSeq 列（它是整块设置而非逐条实体），却又必须同步；
     * 单独记在这里，推送时才能给出正确的 `baseSeq`——否则恒用 0 会被服务端每次都判成
     * 「有人在我之后改过」，「同步完成，1 条两边都改过」这条假冲突会永远挂在回执上。
     */
    const val PREFERENCES_SEQ = "preferences_seq"

    /**
     * 上次成功推送到服务端的 `preferences` 载荷内容签名（SHA-256 前 8 字节）。
     *
     * 偏好是整块设置、没有逐条 dirty 位。若每次同步都无条件推它，即使内容一字未改，
     * 服务端也会给它发一个新序号——所有设备的增量水线于是永远多扫这一行，
     * 「没有数据变化但同步序号一直涨」就是这么来的。先比签名，没变就不推。
     */
    const val PREFERENCES_HASH = "preferences_hash"

    /**
     * 服务端实例标识的哈希（SHA-256 前 8 字节）。
     *
     * `sync_state.value` 是 Long，存不下服务端返回的字符串 instanceId，故存哈希。
     * 它变化只有一种含义：服务端库被删掉/重建过，本地水位和快照都失效，
     * 必须先以本机为准回灌，再重新拉快照。
     */
    const val INSTANCE_HASH = "instance_hash"

    /**
     * 是否已经完成过一次「按集合快照」。
     *
     * 首次安装 / 重建 / 服务端换库后为 0，同步走 /v1/snapshot；
     * 完成后置 1，日常只走轻量 meta + 增量。
     */
    const val SNAPSHOT_DONE = "snapshot_done"
}
