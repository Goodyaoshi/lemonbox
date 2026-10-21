package com.goodyaoshi.lemonbox.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
 * 使用 OkHttp 而不是裸 [java.net.HttpURLConnection]，唯一原因是 DoH：
 * 系统 DNS 被污染时需要在「域名 → IP」这一步走加密解析，同时仍让 TLS 按域名做
 * SNI 与证书校验。OkHttp 的 [okhttp3.Dns] 正好提供这个切口，且 Coil 已经传递依赖它。
 *
 * 地址来自 [SyncEndpointStore]：默认是构建时注入的公开域名，用户可另配一个自定义地址；
 * 登录前按候选顺序探活 `/health`，能连上的那个才会被使用。
 *
 * 凭据只有登录换来的会话令牌，由 [SyncSessionStore] 加密保管，每次请求临时取用。
 * 对象存储的 AccessKey / SecretKey 只在服务端，客户端永远看不到、也不需要。
 */
@Singleton
class RemoteSyncClient @Inject constructor(
    private val sessionStore: SyncSessionStore,
    private val endpointStore: SyncEndpointStore
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val dohResolver = DohResolver()

    /**
     * 只对同步域名的解析走 DoH；其他域名（图片、局域网等）保持系统 DNS。
     * 连接池与线程池由同一个 client 复用。
     */
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(DohDns(dohResolver) { endpointStore.candidateHosts() })
        .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(DATA_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .build()

    /** 当前使用的服务端根地址（最近一次探活成功的地址，未探活则为默认地址）。 */
    val baseUrl: String
        get() = endpointStore.activeBaseUrl()

    /** 是否配置了服务端地址（本地构建可能未注入，用户也可通过自定义地址补上）。 */
    fun isConfigured(): Boolean = endpointStore.candidates().isNotEmpty()

    /** 主动探活当前候选地址，供设置页「测试连接」使用。 */
    suspend fun probe(): EndpointProbe = withContext(Dispatchers.IO) {
        probeEndpoints()
    }

    /**
     * 按候选顺序探活 `/health`。调用方已在 IO 上下文；请求本身可被协程取消。
     * 全部失败时返回第一个候选与逐项失败原因，便于界面直接展示。
     */
    private suspend fun probeEndpoints(): EndpointProbe {
        val all = endpointStore.candidates()
        if (all.isEmpty()) {
            return EndpointProbe("", false, "这个安装包没有配置同步服务器地址")
        }
        val failures = mutableListOf<String>()
        for (base in all) {
            val result = probeOne(base)
            if (result.reachable) {
                endpointStore.markActive(base)
                return result
            }
            failures += result.detail
        }
        return EndpointProbe(all.first(), false, failures.joinToString("；"))
    }

    private suspend fun probeOne(base: String): EndpointProbe {
        return try {
            val httpRequest = Request.Builder().url("$base/health").get().build()
            httpClient.newCall(httpRequest).await().use { response ->
                val text = response.body?.string().orEmpty()
                val ok = response.isSuccessful && runCatching {
                    json.parseToJsonElement(text)
                        .jsonObject["ok"]
                        ?.jsonPrimitive
                        ?.content == "true"
                }.getOrDefault(false)
                EndpointProbe(
                    baseUrl = base,
                    reachable = ok,
                    detail = if (ok) "已连接 $base" else "$base 返回 HTTP ${response.code}"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EndpointProbe(base, false, "$base：${NetworkError.describe(e)}")
        }
    }

    /**
     * 登录并把会话加密落本机。
     *
     * 先探活候选地址：默认域名被污染/阻断时，会自动改用用户配置的备用地址。
     * 探活失败时把每个地址的具体原因拼进错误里，而不是笼统说「连不上」。
     * [password] 只在这一步交给服务端校验，本机不保存它。
     */
    suspend fun login(
        username: String,
        password: String,
        deviceId: String,
        deviceName: String
    ): RemoteSession = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            throw RemoteApiException(0, "这个安装包没有配置同步服务器地址")
        }
        val probe = probeEndpoints()
        if (!probe.reachable) {
            throw RemoteApiException(0, "连不上同步服务器：${probe.detail}")
        }
        val endpoint = probe.baseUrl
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
            request(
                method = "POST",
                path = "/v1/login",
                body = payload,
                token = null,
                baseUrl = endpoint,
                readTimeoutMs = API_TIMEOUT_MS
            )
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
                request(
                    method = "POST",
                    path = "/v1/logout",
                    body = null,
                    token = session.token,
                    baseUrl = session.baseUrl,
                    readTimeoutMs = API_TIMEOUT_MS
                )
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
     *
     * [includeSelf] 不排除本设备写过的记录：常规同步必须排除（自己推的再拉回来是浪费），
     * 「以服务端为准重建」则必须带上——重建后本机是空的，服务端上很多记录本就是这台设备写的。
     */
    suspend fun pull(since: Long, limit: Int, includeSelf: Boolean = false): PullResponse =
        withContext(Dispatchers.IO) {
            val selfParam = if (includeSelf) "&includeSelf=1" else ""
            json.decodeFromString(
                PullResponse.serializer(),
                authedRequest("GET", "/v1/pull?since=$since&limit=$limit$selfParam", body = null)
            )
        }

    /** 服务端对账统计：各集合计数、设备列表与冲突台账数（P9 诊断页用）。 */
    suspend fun stats(): SyncStatsResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(SyncStatsResponse.serializer(), authedRequest("GET", "/v1/stats", body = null))
    }

    /** 取当前会话令牌带上去发一次请求；未登录时直接抛，不白跑一趟必然 401 的请求。 */
    private suspend fun authedRequest(method: String, path: String, body: String?): String {
        val session = sessionStore.current()
            ?: throw RemoteApiException(401, "请先登录柠檬账号")
        return try {
            request(
                method = method,
                path = path,
                body = body,
                token = session.token,
                baseUrl = session.baseUrl,
                readTimeoutMs = DATA_TIMEOUT_MS
            )
        } catch (e: RemoteApiException) {
            // 只有网络层失败（statusCode=0）才换地址重试；HTTP 4xx/5xx 原样带回。
            if (e.statusCode != 0) throw e
            val probe = probeEndpoints()
            if (!probe.reachable || probe.baseUrl == session.baseUrl) throw e
            request(
                method = method,
                path = path,
                body = body,
                token = session.token,
                baseUrl = probe.baseUrl,
                readTimeoutMs = DATA_TIMEOUT_MS
            )
        }
    }

    /**
     * 鉴权 JSON 请求，供对象存储网关（[com.goodyaoshi.lemonbox.data.imagecloud.BlobGateway]）
     * 复用同一套会话令牌、超时与错误翻译；非 2xx 抛 [RemoteApiException]。
     */
    suspend fun authedJsonRequest(method: String, path: String, body: String?): String =
        withContext(Dispatchers.IO) { authedRequest(method, path, body) }

    /** 发一个请求并读回响应体；非 2xx 抛 [RemoteApiException]。 */
    private suspend fun request(
        method: String,
        path: String,
        body: String?,
        token: String?,
        baseUrl: String,
        readTimeoutMs: Int
    ): String {
        val endpoint = baseUrl.trimEnd('/')
        if (endpoint.isEmpty()) throw RemoteApiException(0, "同步服务器地址为空")
        val builder = Request.Builder().url(endpoint + path)
        token?.let { builder.header("Authorization", "Bearer $it") }
        // GET/HEAD 不允许带 body；其余方法即使 body 为空也要显式给 null。
        builder.method(method, body?.toRequestBody(JSON_MEDIA_TYPE))
        val client = httpClient.newBuilder()
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        return try {
            // await 而不是 execute：协程被取消（用户点「取消同步」/ 总超时）时要中止
            // 正在进行的网络请求。阻塞式 execute 不响应取消，会让状态永远停在「正在同步」。
            client.newCall(builder.build()).await().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw RemoteApiException(response.code, errorMessage(response.code, text))
                }
                text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteApiException) {
            throw e
        } catch (e: IOException) {
            // 把 DNS/TLS/超时/拒绝连接区分开，并带上实际尝试的地址。
            throw RemoteApiException(0, "${NetworkError.describe(e)}（地址：$endpoint）")
        } catch (e: Exception) {
            throw RemoteApiException(0, "请求失败：${e.message ?: e.javaClass.simpleName}（地址：$endpoint）")
        }
    }

    /**
     * 把 OkHttp 的异步请求桥接成可取消的挂起调用。
     *
     * 不能用阻塞的 `execute()`：它在协程被取消时不会返回，「取消同步」与总超时都
     * 只能干等一个最长 60 秒的读超时，界面就表现为一直卡在「正在同步…」。
     */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isCancelled) response.close() else continuation.resume(response)
            }
        })
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

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
