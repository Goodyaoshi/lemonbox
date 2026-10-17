package com.goodyaoshi.lemonbox.data.remote

import com.goodyaoshi.lemonbox.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 与同步服务端交互失败。[statusCode] 为 HTTP 状态码，网络层没拿到响应时为 0。
 * [message] 已是可直接展示给用户的中文说明（服务端自带的文案优先）。
 */
class RemoteApiException(
    val statusCode: Int,
    override val message: String
) : Exception(message)

/**
 * 同步服务端的 HTTP 客户端。
 *
 * 裸 [HttpURLConnection] + kotlinx-serialization，不引任何 HTTP 框架：
 * 与局域网同步（`SyncClient`）同一套写法，依赖最少、行为最可控。
 *
 * 地址来自 `BuildConfig.SYNC_BASE_URL`（**非密钥**，Worker 的公开域名）；
 * 凭据只有登录换来的会话令牌，由 [SyncSessionStore] 加密保管，每次请求临时取用。
 * 对象存储的 AccessKey / SecretKey 只在服务端，客户端永远看不到、也不需要。
 */
@Singleton
class RemoteSyncClient @Inject constructor(
    private val sessionStore: SyncSessionStore
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 服务端根地址，已去掉结尾斜杠。 */
    val baseUrl: String
        get() = BuildConfig.SYNC_BASE_URL.trim().trimEnd('/')

    /** 是否配置了服务端地址（本地构建可能未注入）。 */
    fun isConfigured(): Boolean = baseUrl.isNotEmpty()

    /**
     * 登录并把会话加密落本机。
     *
     * 账号由服务端预先配置，客户端不内置任何账号密码；[password] 只在这一步交给服务端校验，
     * 本机不保存它。失败时抛 [RemoteApiException]（账号密码不对、网络不通等），文案可直接展示。
     */
    suspend fun login(
        username: String,
        password: String,
        deviceId: String,
        deviceName: String
    ): RemoteSession = withContext(Dispatchers.IO) {
        val endpoint = baseUrl
        if (endpoint.isEmpty()) {
            throw RemoteApiException(0, "这个安装包没有配置同步服务器地址")
        }
        val payload = json.encodeToString(
            LoginRequest(
                username = username.trim(),
                password = password,
                deviceId = deviceId,
                deviceName = deviceName
            )
        )
        val response = json.decodeFromString(
            LoginResponse.serializer(),
            request("POST", "/v1/login", payload, token = null, readTimeoutMs = API_TIMEOUT_MS)
        )
        val session = RemoteSession(
            baseUrl = endpoint,
            token = response.token,
            userId = response.user.id,
            username = response.user.name,
            expiresAt = response.expiresAt
        )
        if (!sessionStore.save(session)) {
            throw RemoteApiException(0, "系统密钥库不可用，无法在本机保存登录状态")
        }
        session
    }

    /**
     * 退出登录：先在服务端作废这次会话，再清本机凭据。
     *
     * 服务端那步失败（网络不通等）不影响本机退出——本机不该留着一个用不了的令牌，
     * 而服务端会话到期自会失效。
     */
    suspend fun logout() = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        if (session != null) {
            runCatching {
                request("POST", "/v1/logout", body = null, token = session.token, readTimeoutMs = API_TIMEOUT_MS)
            }
        }
        sessionStore.clear()
    }

    /**
     * 推送一批变更。服务端单次上限 200 条，超出会直接报错，故由调用方分批。
     */
    suspend fun push(changes: List<PushChange>): PushResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(PushRequest.serializer(), PushRequest(changes))
        json.decodeFromString(PushResponse.serializer(), authedRequest("POST", "/v1/push", body))
    }

    /**
     * 按服务端序号增量拉取一批记录。服务端单次上限 500 条，
     * [hasMore] 为真表示还有余量，由调用方接着拉下一批。
     */
    suspend fun pull(since: Long, limit: Int): PullResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(
            PullResponse.serializer(),
            authedRequest("GET", "/v1/pull?since=$since&limit=$limit", body = null)
        )
    }

    /** 取当前会话令牌带上去发一次请求；未登录时直接抛，不白跑一趟必然 401 的请求。 */
    private fun authedRequest(method: String, path: String, body: String?): String {
        val session = sessionStore.current() ?: throw RemoteApiException(401, "请先登录柠檬账号")
        return request(method, path, body, session.token, DATA_TIMEOUT_MS)
    }

    /** 发一个请求并读回响应体；非 2xx 抛 [RemoteApiException]。 */
    private fun request(
        method: String,
        path: String,
        body: String?,
        token: String?,
        readTimeoutMs: Int
    ): String {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            this.readTimeout = readTimeoutMs
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw RemoteApiException(code, errorMessage(code, text))
            return text
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteApiException) {
            throw e
        } catch (e: Exception) {
            // 网络层异常统一翻译成人话，避免把 UnknownHostException 之类抛到界面上。
            throw RemoteApiException(0, "连不上服务器，检查网络或稍后再试")
        } finally {
            connection.disconnect()
        }
    }

    /** 优先用服务端自带的说明，其次按状态码给兜底文案。 */
    private fun errorMessage(code: Int, body: String): String {
        val serverMessage = runCatching {
            json.decodeFromString(RemoteErrorBody.serializer(), body).error
        }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (serverMessage != null) return serverMessage
        return when {
            code == 401 || code == 403 -> "登录已过期，请重新登录"
            code == 404 -> "服务端地址不对"
            code >= 500 -> "服务器出错了，稍后再试"
            else -> "请求失败（$code）"
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000

        /** 登录 / 退出这类短交互的超时；推拉数据走 [DATA_TIMEOUT_MS]。 */
        const val API_TIMEOUT_MS = 30_000

        /** 推拉一批记录的超时：一批最多几百条，给足读回的时间。 */
        const val DATA_TIMEOUT_MS = 60_000
    }
}
