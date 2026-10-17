package com.goodyaoshi.lemonbox.data.remote

import kotlinx.serialization.Serializable

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
    val deleted: Boolean = false
)

/** 一次推送的请求体；设备身份由会话令牌携带，不在报文里重复。 */
@Serializable
data class PushRequest(val changes: List<PushChange>)

/** 服务端已写下的记录及其新序号。 */
@Serializable
data class PushApplied(val collection: String, val key: String, val seq: Long = 0)

/** 服务端判定「有人在我之后改过」而未覆盖的条目：服务端版本胜出。 */
@Serializable
data class PushConflict(
    val collection: String,
    val key: String,
    val baseSeq: Long = 0,
    val seq: Long = 0,
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
    val lastSeq: Long = 0,
    val hasMore: Boolean = false
)
