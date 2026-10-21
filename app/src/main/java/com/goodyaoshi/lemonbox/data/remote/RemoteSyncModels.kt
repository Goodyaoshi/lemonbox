package com.goodyaoshi.lemonbox.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * 与同步服务端（Cloudflare Worker）交互的线上报文。
 *
 * 刻意与本地实体的 Snapshot DTO 分开：这些类型只描述**网络形态**，
 * 落库一律走 `BackupSnapshots`，避免把本机自增 id 之类的东西带到服务端。
 */

/** 登录请求（`POST /v1/login`）。 */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    /** 本机设备标识（[com.goodyaoshi.lemonbox.data.settings.AppPreferences.deviceId]）。 */
    val deviceId: String,
    val deviceName: String
)

/** 登录成功的响应：会话令牌 + 有效期 + 账号信息。 */
@Serializable
data class LoginResponse(
    val token: String,
    /** 令牌到期时刻（服务端时钟，毫秒）。 */
    val expiresAt: Long,
    val user: RemoteUser
)

/** 账号信息；`name` 即用户名，也用于界面上「这条最后是谁改的」。 */
@Serializable
data class RemoteUser(val id: String, val name: String)

/** 服务端的统一错误体（`{"error": "..."}`），只为把它的文案透给用户。 */
@Serializable
internal data class RemoteErrorBody(val error: String = "")

/** 推送的一条记录（`POST /v1/push`）。 */
@Serializable
data class PushChange(
    val collection: String,
    /** Snapshot 的 syncId；服务端按 (collection, key) 定位记录。 */
    val key: String,
    val payload: String,
    /**
     * 本机上次见到的该记录服务端序号，用于乐观并发：
     * 服务端发现实际序号更大（有人在我之后改过）就**不覆盖**，只回报冲突。
     */
    val baseSeq: Long,
    val deleted: Boolean = false,
    /**
     * 强制写入，跳过 `baseSeq` 冲突检查。
     *
     * 只用于用户在诊断页主动执行的「以本机为准合并上传」：这是明确的本地优先动作，
     * 把本机全量数据覆盖到服务端同一条记录上。日常同步不置位，仍走冲突→本地重推。
     */
    val force: Boolean = false
)

/** 一次推送的请求体；设备身份由会话令牌携带，不在报文里重复。 */
@Serializable
data class PushRequest(val changes: List<PushChange>)

/**
 * 服务端已确认的记录及其序号。
 *
 * [noop] 为真表示载荷与墓碑都和服务端一模一样，服务端没有真正写入、也没有消耗新序号，
 * 只把原序号回给客户端对齐水位。它仍要参与 `markLanded` 清脏，但不应计入「上传条数」。
 */
@Serializable
data class PushApplied(
    val collection: String,
    val key: String,
    val seq: Long = 0,
    val noop: Boolean = false
)

/**
 * 服务端判定「有人在我之后改过」而未覆盖的条目：服务端版本胜出。
 *
 * [payload] / [deleted] 是服务端当前那一版的完整内容（P9 起）：客户端把它落地到本机，
 * 才算真正「以服务端为准」——只对齐序号、内容却各留各的，是最隐蔽的静默分叉。
 * 旧版服务端不回这两个字段，缺省为空，客户端按「无载荷可落地」处理并如实记录。
 */
@Serializable
data class PushConflict(
    val collection: String,
    val key: String,
    val baseSeq: Long = 0,
    val seq: Long = 0,
    val payload: String = "",
    val deleted: Boolean = false,
    val updatedBy: String = ""
)

/** 一次推送的响应。 */
@Serializable
data class PushResponse(
    val applied: List<PushApplied> = emptyList(),
    val conflicts: List<PushConflict> = emptyList(),
    val lastSeq: Long = 0,
    /** 请求批次已满，说明可能还有余量；客户端据此继续下一批。 */
    val hasMore: Boolean = false
)

/** 拉回的一条记录（`GET /v1/pull`）。 */
@Serializable
data class PullChange(
    val collection: String,
    val key: String,
    val payload: String,
    val seq: Long = 0,
    val deleted: Boolean = false,
    /** 最后改这条的人（账号名或「AI助手」），供界面显示「这条最后是谁改的」。 */
    val updatedBy: String = "",
    val updatedAt: Long = 0
)

/** 一次拉取的响应；[hasMore] 为真时客户端**不推水位**，下一轮接着拉。 */
@Serializable
data class PullResponse(
    val changes: List<PullChange> = emptyList(),
    /**
     * 本次分页实际扫描到的服务端序号（**含被「排除本设备」过滤掉的行**）。
     *
     * 不能用 [changes] 的最后一条来推进水位：整页恰好都是本设备自己写的记录时，
     * changes 为空而扫描位置其实前进了。旧服务端不返回该字段，客户端回退读 [lastSeq]。
     */
    val scannedSeq: Long = 0,
    /** 兼容旧字段：新服务端返回的值与 [scannedSeq] 相同。 */
    val lastSeq: Long = 0,
    val hasMore: Boolean = false,
    /** 服务端当前全局序号；旧服务端不返回，按 0 处理。 */
    val seq: Long = 0,
    /**
     * `since` 比服务端当前序号还大：库被删掉/重建过，旧水位已失效。
     * 服务端已把本次 `since` 钳到 0，客户端应清空本地水位/缓存并重新走快照。
     */
    val resetRequired: Boolean = false
)

// ---------- 轻量同步元信息与快照（GET /v1/meta、/v1/snapshot） ----------

/** 某个集合的当前规模与最后写入序号。 */
@Serializable
data class SyncMetaCollection(
    val collection: String,
    /** 活数据条数（不含墓碑）。 */
    val count: Int = 0,
    /** 总行数（含墓碑）。 */
    val total: Int = 0,
    /** 该集合最后一条的全局序号。 */
    val version: Long = 0
)

/**
 * 同步元信息：客户端每次同步先看这里。
 *
 * [instanceId] 是服务端实例标识，删库重建后会变；[seq] 不变时客户端连业务数据都不用拉。
 */
@Serializable
data class SyncMetaResponse(
    val instanceId: String = "",
    val seq: Long = 0,
    val collections: List<SyncMetaCollection> = emptyList()
)

/**
 * 按集合拉回的当前活数据快照（不含墓碑）。
 *
 * [nextCursor] 用于同一集合继续翻页；[done] 为真表示该集合已取完。
 */
@Serializable
data class SnapshotResponse(
    val collection: String = "",
    val upTo: Long = 0,
    val changes: List<PullChange> = emptyList(),
    val nextCursor: Long = 0,
    val done: Boolean = true
)

// ---------- 服务端对账统计（P9，GET /v1/stats） ----------

/** 服务端某个集合的计数：live 为非墓碑条数，deleted 为墓碑条数。 */
@Serializable
data class SyncStatsCollection(
    val collection: String,
    val live: Int = 0,
    val deleted: Int = 0,
    val total: Int = 0,
    val lastSeq: Long = 0
)

/** 服务端记账里的一台设备：最后活跃时间与它拉到的水位。 */
@Serializable
data class SyncStatsDevice(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("user_id") val userId: String = "",
    val name: String = "",
    @SerialName("last_seen_at") val lastSeenAt: Long = 0,
    @SerialName("last_pull_seq") val lastPullSeq: Long = 0
)

/** 对账总览：服务端各集合计数、设备列表、累计冲突台账条数与当前序号水位。 */
@Serializable
data class SyncStatsResponse(
    val seq: Long = 0,
    val collections: List<SyncStatsCollection> = emptyList(),
    val devices: List<SyncStatsDevice> = emptyList(),
    val conflictCount: Int = 0
)
