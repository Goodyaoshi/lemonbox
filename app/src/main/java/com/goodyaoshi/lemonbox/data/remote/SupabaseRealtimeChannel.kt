package com.goodyaoshi.lemonbox.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Supabase Realtime 的「有人改了数据」信号通道（只在前台使用）。
 *
 * 为什么只当信号、不当数据源：真正落地仍然只走 `syncNow()` 那条增量通道
 * （水位、冲突、幂等导入都在那里）。订阅里拿到的行如果直接写库，就会多出第二条
 * 写路径，两套一致性问题迟早打架。
 *
 * 协议是 Realtime v1（Phoenix channel）：
 *  1. `wss://<项目>/realtime/v1/websocket?apikey=…&vsn=1.0.0`
 *  2. `phx_join` 里带 `access_token`（RLS 按登录身份判定）与 postgres_changes 配置
 *  3. 每 [HEARTBEAT_MS] 发一次 heartbeat，否则服务端会主动断开
 *  4. 收到 `postgres_changes` 事件就往 [changes] 发一次信号
 *
 * 断线按指数退避重连，每次重连都重新取令牌（access_token 只有 1 小时）。
 * 订阅失败（例如 `records` 还没加入 Realtime publication）不会报错给用户——
 * 现有的前台/写入/周期触发仍在，实时订阅只是锦上添花。
 */
internal class SupabaseRealtimeChannel(
    private val url: String,
    private val client: OkHttpClient,
    private val tokenProvider: suspend () -> String?
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 缓冲 1、丢弃旧值：短时间多条变更只留一个信号，去抖交给上层。 */
    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private val stopped = AtomicBoolean(false)
    private val ref = AtomicLong(0)
    private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    private var reconnector: Job? = null
    private var attempts = 0

    fun start() {
        connect()
    }

    fun close() {
        if (!stopped.compareAndSet(false, true)) return
        heartbeat?.cancel()
        reconnector?.cancel()
        runCatching { socket?.close(NORMAL_CLOSE, "bye") }
        socket = null
        scope.cancel()
    }

    private fun connect() {
        if (stopped.get()) return
        scope.launch {
            val token = runCatching { tokenProvider() }.getOrNull()
            if (token.isNullOrBlank()) {
                scheduleReconnect()
                return@launch
            }
            val listener = Listener(token)
            socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
        }
    }

    private inner class Listener(private val token: String) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempts = 0
            val join = buildJsonObject {
                put("topic", TOPIC)
                put("event", "phx_join")
                put("ref", nextRef())
                put("payload", buildJsonObject {
                    // Realtime 用这个令牌判断 RLS：没带或过期就收不到任何行。
                    put("access_token", token)
                    put(
                        "config",
                        buildJsonObject {
                            put(
                                "postgres_changes",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("event", "*")
                                            put("schema", "public")
                                            put("table", "records")
                                        }
                                    )
                                }
                            )
                        }
                    )
                })
            }
            webSocket.send(join.toString())
            startHeartbeat(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when {
                text.contains("\"event\":\"postgres_changes\"") -> _changes.tryEmit(Unit)
                // join 被拒 / 发布未开启：断开交给退避重连，别在原地反复重发。
                text.contains("Unable to subscribe") -> webSocket.close(NORMAL_CLOSE, null)
                text.contains("\"event\":\"phx_reply\"") &&
                    text.contains("\"status\":\"error\"") -> webSocket.close(NORMAL_CLOSE, null)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            heartbeat?.cancel()
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            heartbeat?.cancel()
            scheduleReconnect()
        }
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (!stopped.get()) {
                delay(HEARTBEAT_MS)
                val beat = buildJsonObject {
                    put("topic", "phoenix")
                    put("event", "heartbeat")
                    put("ref", nextRef())
                    put("payload", buildJsonObject { })
                }
                if (!webSocket.send(beat.toString())) {
                    // 发送失败＝这条连接已经半死（WiFi/移动网切换后常见）。
                    // 不主动关掉的话，心跳循环退出、连接却还挂着，实时信号会静默消失。
                    runCatching { webSocket.cancel() }
                    scheduleReconnect()
                    return@launch
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (stopped.get()) return
        if (reconnector?.isActive == true) return
        reconnector = scope.launch {
            attempts = (attempts + 1).coerceAtMost(MAX_BACKOFF_STEPS)
            delay(minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl (attempts - 1)))
            connect()
        }
    }

    private fun nextRef(): String = ref.incrementAndGet().toString()

    private companion object {
        const val TOPIC = "realtime:lemonbox"

        /** 服务端 60 秒收不到心跳就断；25 秒留足余量。 */
        const val HEARTBEAT_MS = 25_000L

        const val BASE_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_STEPS = 6
        const val NORMAL_CLOSE = 1000
    }
}
