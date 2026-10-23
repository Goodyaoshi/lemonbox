package com.goodyaoshi.lemonbox.data.remote

import android.os.Build
import com.goodyaoshi.lemonbox.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
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
 * 调用图片签名函数所需的会话参数。
 *
 * 凭据仍是登录换来的短时 access_token（外加受 RLS 约束的 publishable key），
 * 对象存储密钥只在边缘函数里；[client] 复用同一套 DoH 解析，避免 `*.supabase.co`
 * 被污染时图片链路单独失联。
 */
data class SupabaseBlobAccess(
    val baseUrl: String,
    val accessToken: String,
    val apiKey: String,
    val client: OkHttpClient
)

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
     * Supabase 的 publishable key：公开、只受 RLS 约束，不是长期密钥。
     * 所有 `/auth/v1` 与 `/rest/v1` 请求都要带 `apikey`，登录后另加 Bearer。
     */
    private val supabaseKey: String = BuildConfig.SUPABASE_KEY

    /** 当前账号所属的 household_id；一次登录内不会变，省掉每次推送都查一次。 */
    @Volatile
    private var householdIdCache: String? = null

    /**
     * 续期串行化。
     *
     * Supabase 每次刷新都会轮换 refresh_token：两个并发请求各拿旧 token 去换，
     * 后到的那次会被判「已使用」返回 400，而 4xx 又会触发清空本机会话——
     * 表现为用户莫名其妙被退出登录。加锁后第二次进入时读到的已是新令牌，直接复用。
     */
    private val refreshMutex = Mutex()

    /**
     * 本次进程内是否已确认「保存的凭据无效」（如密码在别处被改）。
     * 这种情况下每次同步都去撞一次登录接口没有意义；下次启动或手动登录成功会重置。
     */
    @Volatile
    private var autoReloginBlocked = false

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

    /** 当前同步后端：`supabase` 或旧 Worker（`d1`）。 */
    fun backendKind(): String = if (isSupabaseEndpoint(baseUrl)) "supabase" else "d1"

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
                endpointStore.markActive(base, result.supabase)
                return result
            }
            failures += result.detail
        }
        return EndpointProbe(all.first(), false, failures.joinToString("；"))
    }

    private suspend fun probeOne(base: String): EndpointProbe {
        if (isSupabaseEndpoint(base)) return probeProtocol(base, supabase = true)
        // 自定义反代域名（如 https://supabase.example.com）的后缀不像 Supabase，
        // 只能按能力判断：先试 Supabase 的公开 /auth/v1/settings，再退回旧 Worker 的 /health。
        val supabaseProbe = probeProtocol(base, supabase = true)
        if (supabaseProbe.reachable) return supabaseProbe
        return probeProtocol(base, supabase = false)
    }

    private suspend fun probeProtocol(base: String, supabase: Boolean): EndpointProbe {
        return try {
            val httpRequest = if (supabase) {
                // Supabase 没有 /health：读取公开的 Auth 设置即可证明项目可达。
                Request.Builder()
                    .url("$base/auth/v1/settings")
                    .header("apikey", supabaseKey)
                    .get()
                    .build()
            } else {
                Request.Builder().url("$base/health").get().build()
            }
            httpClient.newCall(httpRequest).await().use { response ->
                val text = response.body?.string().orEmpty()
                val ok = if (supabase) {
                    response.isSuccessful
                } else {
                    response.isSuccessful && runCatching {
                        json.parseToJsonElement(text)
                            .jsonObject["ok"]
                            ?.jsonPrimitive
                            ?.content == "true"
                    }.getOrDefault(false)
                }
                EndpointProbe(
                    baseUrl = base,
                    reachable = ok,
                    detail = if (ok) "已连接 $base" else "$base 返回 HTTP ${response.code}",
                    supabase = supabase
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EndpointProbe(base, false, "$base：${NetworkError.describe(e)}", supabase)
        }
    }

    /**
     * 这个地址是不是 Supabase。
     *
     * 以 `.supabase.co` 后缀判断（自建 Supabase 域名可另配 LEMON_SUPABASE_URL，
     * 与构建默认值一致时同样命中）。这样同一个安装包既能连 Supabase，
     * 也能在把 LEMON_SYNC_BASE_URL 指回旧 Worker 时整包回退，不需要分支发版。
     *
     * 反代域名（如 `https://supabase.example.com`）没有 `.supabase.co` 后缀，
     * 靠探活时确认过的结果识别；该结果由 [SyncEndpointStore] 持久化，
     * 保证重启后走 Supabase RPC 而不是旧 Worker 的 `/v1` 系列接口。
     */
    private fun isSupabaseEndpoint(url: String): Boolean {
        val base = url.trim().trimEnd('/')
        if (base.isEmpty()) return false
        if (base == BuildConfig.SUPABASE_URL.trim().trimEnd('/')) return true
        if (base == endpointStore.supabaseBase()) return true
        return runCatching { URL(base).host.lowercase().endsWith(".supabase.co") }
            .getOrDefault(false)
    }

    private fun apikeyHeader(): Map<String, String> = mapOf("apikey" to supabaseKey)

    /**
     * 登录并把会话加密落本机。
     *
     * 先探活候选地址：默认域名被污染/阻断时，会自动改用用户配置的备用地址。
     * 探活失败时把每个地址的具体原因拼进错误里，而不是笼统说「连不上」。
     * 登录成功会同时留一份加密的 [AutoLoginCredential]：会话被服务端撤销或
     * 换域失效后，用它静默重登，用户只要没主动退出就不必再输密码。
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
        val session = if (isSupabaseEndpoint(endpoint)) {
            supabaseLogin(endpoint, username, password, deviceId)
        } else {
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
            val legacy = RemoteSession(
                baseUrl = endpoint,
                token = response.token,
                userId = response.user.id,
                username = response.user.name,
                expiresAt = response.expiresAt
            )
            if (!sessionStore.save(legacy)) {
                throw RemoteApiException(0, "系统密钥库不可用，无法在本机保存登录状态")
            }
            legacy
        }
        // 凭据写入失败不影响本次登录，只是以后不能静默重登。
        sessionStore.saveCredential(AutoLoginCredential(username.trim(), password, deviceId))
        autoReloginBlocked = false
        session
    }

    /**
     * Supabase 登录：用户名先映射成邮箱，再走 Auth 的 password grant。
     *
     * Supabase Auth 只认邮箱，所以「时天 / 柠檬」两个用户名必须有一份固定映射；
     * 它必须与 server/supabase/0002_bind_family.sql 里的映射保持一致。
     * 也接受用户直接填完整邮箱，方便以后换成真实邮箱时不改代码。
     */
    private suspend fun supabaseLogin(
        endpoint: String,
        username: String,
        password: String,
        deviceId: String
    ): RemoteSession {
        val email = resolveSupabaseEmail(username)
        val payload = json.encodeToString(
            SupabasePasswordGrant.serializer(),
            SupabasePasswordGrant(email = email, password = password)
        )
        val text = request(
            method = "POST",
            path = "/auth/v1/token?grant_type=password",
            body = payload,
            token = null,
            baseUrl = endpoint,
            readTimeoutMs = API_TIMEOUT_MS,
            headers = apikeyHeader()
        )
        val token = json.decodeFromString(SupabaseTokenResponse.serializer(), text)
        if (token.accessToken.isBlank()) {
            throw RemoteApiException(0, "登录失败：服务端没有返回会话令牌")
        }
        val session = RemoteSession(
            baseUrl = endpoint,
            token = token.accessToken,
            userId = token.user?.id.orEmpty(),
            username = supabaseUsername(email) ?: username.trim(),
            // expires_in 是秒；留 60 秒余量由 freshSupabaseSession 统一处理，这里按原值记。
            expiresAt = expiryFrom(token.expiresIn),
            refreshToken = token.refreshToken,
            deviceId = deviceId
        )
        householdIdCache = null
        if (!sessionStore.save(session)) {
            throw RemoteApiException(0, "系统密钥库不可用，无法在本机保存登录状态")
        }
        return session
    }

    /** 用户名 → Supabase Auth 邮箱；与 0002_bind_family.sql 的映射一一对应。 */
    private fun resolveSupabaseEmail(raw: String): String {
        val value = raw.trim()
        if (value.contains('@')) return value
        return SUPABASE_EMAILS[value] ?: throw RemoteApiException(
            0,
            "这个安装包只给「时天」「柠檬」两个账号用，请填用户名或完整邮箱"
        )
    }

    private fun supabaseUsername(email: String): String? =
        SUPABASE_EMAILS.entries
            .firstOrNull { it.value.equals(email.trim(), ignoreCase = true) }
            ?.key

    /**
     * 退出登录：先在服务端作废这次会话，再清本机凭据。
     *
     * 服务端那步失败（网络不通等）不影响本机退出——本机不该留着一个用不了的令牌，
     * 而服务端会话到期自会失效。
     */
    suspend fun logout() = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        if (session != null) {
            val supabase = isSupabaseEndpoint(session.baseUrl)
            runCatching {
                request(
                    method = "POST",
                    path = if (supabase) "/auth/v1/logout" else "/v1/logout",
                    body = null,
                    token = session.token,
                    baseUrl = session.baseUrl,
                    readTimeoutMs = API_TIMEOUT_MS,
                    headers = if (supabase) apikeyHeader() else emptyMap()
                )
            }
        }
        householdIdCache = null
        sessionStore.clear()
        // 主动退出才删除静默重登凭据；这是「以后要重新输密码」的唯一开关。
        sessionStore.clearCredential()
    }

    /**
     * 推送一批变更。服务端单次上限 200 条，超出会直接报错，故由调用方分批。
     */
    suspend fun push(changes: List<PushChange>): PushResponse = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        if (session != null && isSupabaseEndpoint(session.baseUrl)) {
            return@withContext supabasePush(changes)
        }
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
            val session = sessionStore.current()
            if (session != null && isSupabaseEndpoint(session.baseUrl)) {
                return@withContext supabasePull(since, limit, includeSelf)
            }
            val selfParam = if (includeSelf) "&includeSelf=1" else ""
            json.decodeFromString(
                PullResponse.serializer(),
                authedRequest("GET", "/v1/pull?since=$since&limit=$limit$selfParam", body = null)
            )
        }

    /** 服务端对账统计：各集合计数、设备列表与冲突台账数（P9 诊断页用）。 */
    suspend fun stats(): SyncStatsResponse = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        if (session != null && isSupabaseEndpoint(session.baseUrl)) {
            return@withContext json.decodeFromString(
                SyncStatsResponse.serializer(),
                supabaseRpc(freshSupabaseSession(), "sync_stats", "{}")
            )
        }
        json.decodeFromString(SyncStatsResponse.serializer(), authedRequest("GET", "/v1/stats", body = null))
    }

    /**
     * 轻量同步元信息：实例标识、当前全局序号与各集合规模。
     *
     * 旧服务端没有该接口时会抛 [RemoteApiException]（404）；调用方用 runCatching
     * 回退到纯增量，不因为缺接口而阻断同步。
     */
    suspend fun meta(includeCollections: Boolean = false): SyncMetaResponse =
        withContext(Dispatchers.IO) {
            val session = sessionStore.current()
            if (session != null && isSupabaseEndpoint(session.baseUrl)) {
                val body = buildJsonObject {
                    put("p_collections", includeCollections)
                }
                return@withContext json.decodeFromString(
                    SyncMetaResponse.serializer(),
                    supabaseRpc(freshSupabaseSession(), "sync_meta", body.toString())
                )
            }
            // 日常只取 instanceId/seq；`?collections=1` 会做一次按集合 GROUP BY，
            // 只在首次/重建、确实要拉快照时用，避免每次 focus 都扫 records。
            val query = if (includeCollections) "?collections=1" else ""
            json.decodeFromString(
                SyncMetaResponse.serializer(),
                authedRequest("GET", "/v1/meta$query", body = null)
            )
        }

    /**
     * 按集合拉当前活数据快照。[upTo] 钉住一个全局序号，保证读到的是一个一致点；
     * [cursor] 为上一页最后一条的序号，[done] 为真表示该集合已取完。
     *
     * `collection` 只来自本地固定的 [com.goodyaoshi.lemonbox.data.backup.SyncTables]
     * 或服务端 meta 返回的集合名（均为 ASCII），直接拼进查询串即可。
     */
    suspend fun snapshot(
        collection: String,
        cursor: Long,
        upTo: Long,
        limit: Int = SNAPSHOT_LIMIT
    ): SnapshotResponse = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        if (session != null && isSupabaseEndpoint(session.baseUrl)) {
            val body = buildJsonObject {
                put("p_collection", collection)
                put("p_cursor", cursor)
                put("p_up_to", upTo)
                put("p_limit", limit)
            }
            return@withContext json.decodeFromString(
                SnapshotResponse.serializer(),
                supabaseRpc(freshSupabaseSession(), "sync_snapshot", body.toString())
            )
        }
        json.decodeFromString(
            SnapshotResponse.serializer(),
            authedRequest(
                "GET",
                "/v1/snapshot?collection=$collection&cursor=$cursor&upTo=$upTo&limit=$limit",
                body = null
            )
        )
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
     * 取一个可用的 Supabase 会话：快过期时用 refresh_token 续期并落本机。
     *
     * access_token 只有一小时，而 App 的后台同步可能隔很久才跑一次；不续期就会在
     * 某次同步里突然全 401。刷新返回 4xx（令牌被撤销/账号被删）时先用保存的凭据静默重登，
     * 重登也失败才清掉本机会话，让界面回到「请重新登录」，而不是反复撞同一堵墙。
     *
     * 全程持 [refreshMutex]：只有确实需要续期时才发起网络请求，并确保同一时刻
     * 只有一个请求在轮换 refresh_token，避免并发刷新把彼此挤成 4xx 而误清会话。
     */
    private suspend fun freshSupabaseSession(): RemoteSession = refreshMutex.withLock {
        val session = sessionStore.current()
            ?: return@withLock autoRelogin()
            ?: throw RemoteApiException(401, "请先登录柠檬账号")
        if (session.refreshToken.isBlank()) return@withLock session
        if (!session.isExpired(System.currentTimeMillis() + TOKEN_REFRESH_AHEAD_MS)) return@withLock session
        try {
            val body = json.encodeToString(
                SupabaseRefreshGrant.serializer(),
                SupabaseRefreshGrant(session.refreshToken)
            )
            val text = request(
                method = "POST",
                path = "/auth/v1/token?grant_type=refresh_token",
                body = body,
                token = null,
                baseUrl = session.baseUrl,
                readTimeoutMs = API_TIMEOUT_MS,
                headers = apikeyHeader()
            )
            val token = json.decodeFromString(SupabaseTokenResponse.serializer(), text)
            val updated = session.copy(
                token = token.accessToken.ifBlank { session.token },
                refreshToken = token.refreshToken.ifBlank { session.refreshToken },
                expiresAt = expiryFrom(token.expiresIn)
            )
            sessionStore.save(updated)
            updated
        } catch (e: RemoteApiException) {
            if (e.statusCode in 400..403) {
                householdIdCache = null
                // refresh_token 被服务端撤销（改密码 / 踢会话）时，先用保存的凭据重建；
                // 只有重建也失败才真正回到未登录，避免「没退出却要重新输密码」。
                return@withLock autoRelogin() ?: run {
                    sessionStore.clear()
                    throw RemoteApiException(401, "登录已过期，请重新登录")
                }
            }
            throw e
        }
    }

    /**
     * 主动续期一次；App 冷启动 / 回到前台时调用，让登录态自己滚下去。
     *
     * 会话已经丢了（换域、被清理、refresh_token 被撤销）时，直接用保存的凭据静默重登。
     * 网络不通一律吞掉——这只是一次后台保活，不该让界面弹错；
     * 凭据确认失效时会话会被清空，界面回到登录页（主动退出后则不再有凭据）。
     */
    suspend fun ensureSessionFresh() = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        if (session == null) {
            // 冷启动前可能被多处同时触发，串行化避免重复登录。
            refreshMutex.withLock { autoRelogin() }
            return@withContext
        }
        val expired = session.isExpired()
        if (!isSupabaseEndpoint(session.baseUrl) || session.refreshToken.isBlank()) {
            // 旧 Worker 会话没有 refresh_token，过期后同样只能靠凭据重建。
            if (expired) refreshMutex.withLock { autoRelogin() }
            return@withContext
        }
        try {
            freshSupabaseSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 保活失败不打断启动流程，下一次同步会再试。
        }
    }

    /**
     * 用本机保存的凭据静默重建会话。
     *
     * 覆盖三类「没退出登录却丢了会话」的情况：refresh_token 被服务端撤销、
     * 服务端地址换了导致旧会话作废、本机会话记录被清理。凭据只在主动退出登录时删除，
     * 因此正常使用下用户不会再被要求输入账号密码。
     */
    private suspend fun autoRelogin(): RemoteSession? {
        if (autoReloginBlocked) return null
        val credential = sessionStore.credential() ?: return null
        return try {
            login(
                username = credential.username,
                password = credential.password,
                deviceId = credential.deviceId,
                deviceName = runCatching { Build.MODEL }.getOrNull().orEmpty().ifBlank { "本机" }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteApiException) {
            // 网络层失败（statusCode=0）留到下次前台/启动再试；4xx 说明凭据确实无效，
            // 本次进程内不再重试。凭据本身保留，用户手动登录成功后会覆盖成新的。
            if (e.statusCode != 0) autoReloginBlocked = true
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 由 `expires_in`（秒）算出到期时刻。
     *
     * 个别网关/反代会漏掉该字段，DTO 默认值为 0；若直接相减会得到一个「一登录就过期」
     * 的会话，故按 Supabase 默认的一小时兜底。
     */
    private fun expiryFrom(expiresIn: Long): Long =
        System.currentTimeMillis() +
            (expiresIn.takeIf { it > 0 } ?: DEFAULT_EXPIRES_IN_SECONDS) * 1000L

    /** 调一个 Supabase RPC；返回体刻意与旧 Worker 的 DTO 同形，调用方无需区分后端。 */
    private suspend fun supabaseRpc(session: RemoteSession, name: String, body: String): String =
        request(
            method = "POST",
            path = "/rest/v1/rpc/$name",
            body = body,
            token = session.token,
            baseUrl = session.baseUrl,
            readTimeoutMs = DATA_TIMEOUT_MS,
            headers = apikeyHeader()
        )

    /**
     * 当前账号的 household_id。
     *
     * 家庭绑定关系在服务端（household_members），客户端不硬编码；一次登录内缓存，
     * 避免每推一批都多打一次 REST。未绑定说明 0002 的建家 SQL 没执行完。
     */
    private suspend fun householdId(session: RemoteSession): String {
        householdIdCache?.let { return it }
        val text = request(
            method = "GET",
            path = "/rest/v1/household_members?select=household_id&limit=1",
            body = null,
            token = session.token,
            baseUrl = session.baseUrl,
            readTimeoutMs = API_TIMEOUT_MS,
            headers = apikeyHeader()
        )
        val id = runCatching {
            json.parseToJsonElement(text)
                .jsonArray
                .firstOrNull()
                ?.jsonObject
                ?.get("household_id")
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()
        if (id.isNullOrBlank()) {
            throw RemoteApiException(0, "这个账号还没有加入家庭，请先在服务端执行绑定 SQL")
        }
        householdIdCache = id
        return id
    }

    private suspend fun supabasePush(changes: List<PushChange>): PushResponse {
        val session = freshSupabaseSession()
        val household = householdId(session)
        // PostgREST 的 RPC 入参按函数形参名传，apply_changes 的形参就是 p_batch。
        val batch = buildJsonObject {
            put("household_id", household)
            put("device_id", session.deviceId)
            put("actor", session.username)
            put("changes", buildJsonArray {
                changes.forEach { change ->
                    // 载荷在本地是 JSON 文本，Supabase 的 jsonb 入参要的是 JSON 值而非字符串。
                    val payloadElement = runCatching { json.parseToJsonElement(change.payload) }
                        .getOrElse { JsonObject(emptyMap()) }
                    val updatedAt = runCatching {
                        payloadElement.jsonObject["updatedAt"]?.jsonPrimitive?.longOrNull
                    }.getOrNull() ?: System.currentTimeMillis()
                    add(buildJsonObject {
                        put("collection", change.collection)
                        put("record_key", change.key)
                        put("base_revision", change.baseSeq)
                        put("payload", payloadElement)
                        put("deleted", change.deleted)
                        put("updated_at", updatedAt)
                        put("force", change.force)
                    })
                }
            })
        }
        val body = buildJsonObject {
            put("p_batch", batch)
        }
        return json.decodeFromString(
            PushResponse.serializer(),
            supabaseRpc(session, "apply_changes", body.toString())
        )
    }

    private suspend fun supabasePull(
        since: Long,
        limit: Int,
        includeSelf: Boolean
    ): PullResponse {
        val session = freshSupabaseSession()
        val body = buildJsonObject {
            put("p_cursor", since)
            put("p_limit", limit)
            // 空串表示不排除本设备（重建/首次同步）；日常同步传本机 deviceId。
            put("p_exclude_device", if (includeSelf) "" else session.deviceId)
        }
        return json.decodeFromString(
            PullResponse.serializer(),
            supabaseRpc(session, "pull_changes", body.toString())
        )
    }

    /**
     * 打开一条前台实时订阅；未登录或仍连旧 Worker 时返回 null。
     *
     * 由调用方在 App 离开前台时 close：后台的长连接会被系统掐掉，留着只会白耗电。
     */
    internal fun openRealtimeChannel(): SupabaseRealtimeChannel? {
        val session = sessionStore.current() ?: return null
        if (!isSupabaseEndpoint(session.baseUrl)) return null
        val wsBase = session.baseUrl.trim().trimEnd('/').replaceFirst("https://", "wss://")
        val key = URLEncoder.encode(supabaseKey, "UTF-8")
        val url = "$wsBase/realtime/v1/websocket?apikey=$key&vsn=1.0.0"
        return SupabaseRealtimeChannel(url, httpClient) { freshSupabaseSession().token }
    }

    /**
     * 上报本轮拉取位置与设备名，供诊断页显示「已同步到序号 N」。
     *
     * 旧 Worker 在 /v1/pull 的最后一页顺手写掉 devices 表；Supabase 的 pull_changes
     * 是 `stable` 函数、不能落库，所以由客户端在**整轮拉取成功**后补一次上报。
     * 失败一律吞掉：这只是一条诊断信息，不该把一次成功的同步变成失败。
     */
    suspend fun reportPullProgress(seq: Long) {
        if (seq <= 0) return
        withContext(Dispatchers.IO) {
            val session = sessionStore.current() ?: return@withContext
            if (!isSupabaseEndpoint(session.baseUrl)) return@withContext
            try {
                val fresh = freshSupabaseSession()
                val body = buildJsonObject {
                    put("p_device_id", fresh.deviceId)
                    put("p_seq", seq)
                    put("p_device_name", runCatching { Build.MODEL.orEmpty() }.getOrDefault(""))
                }
                supabaseRpc(fresh, "report_pull_progress", body.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 诊断字段写不进去不影响数据一致性，下一轮同步会再报。
            }
        }
    }

    /**
     * 鉴权 JSON 请求，供对象存储网关（[com.goodyaoshi.lemonbox.data.imagecloud.BlobGateway]）
     * 在旧 Worker 链路上复用同一套会话令牌、超时与错误翻译；非 2xx 抛 [RemoteApiException]。
     */
    suspend fun authedJsonRequest(method: String, path: String, body: String?): String =
        withContext(Dispatchers.IO) {
            val session = sessionStore.current()
            if (session != null && isSupabaseEndpoint(session.baseUrl)) {
                // Supabase 会话换不来旧 Worker 的登录态；图片走边缘函数签名
                // （见 [supabaseBlobAccess]），这里收到请求说明调用方没分支。
                throw RemoteApiException(0, "图片存储已切换为边缘函数签名，请更新应用后再试")
            }
            authedRequest(method, path, body)
        }

    /**
     * 取图片签名函数的调用参数；未登录或仍连旧 Worker 时返回 null。
     *
     * 每次调用都过一遍 [freshSupabaseSession]：图片同步可能隔很久才跑，
     * 复用过期 token 只会换回一串 401。
     */
    suspend fun supabaseBlobAccess(): SupabaseBlobAccess? = withContext(Dispatchers.IO) {
        val session = sessionStore.current() ?: return@withContext null
        if (!isSupabaseEndpoint(session.baseUrl)) return@withContext null
        val fresh = freshSupabaseSession()
        SupabaseBlobAccess(
            baseUrl = fresh.baseUrl.trimEnd('/'),
            accessToken = fresh.token,
            apiKey = supabaseKey,
            client = httpClient
        )
    }

    /** 发一个请求并读回响应体；非 2xx 抛 [RemoteApiException]。 */
    private suspend fun request(
        method: String,
        path: String,
        body: String?,
        token: String?,
        baseUrl: String,
        readTimeoutMs: Int,
        headers: Map<String, String> = emptyMap()
    ): String {
        val endpoint = baseUrl.trimEnd('/')
        if (endpoint.isEmpty()) throw RemoteApiException(0, "同步服务器地址为空")
        val builder = Request.Builder().url(endpoint + path)
        token?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (name, value) -> builder.header(name, value) }
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

    /**
     * 优先用服务端自带的说明，其次按状态码给兜底文案。
     *
     * Supabase 的错误体是 `{"message":"..."}` / `{"msg":"..."}`，旧 Worker 是 `{"error":"..."}`；
     * 两种都认。常见英文原因翻译成一句能直接给用户看的中文。
     */
    private fun errorMessage(code: Int, body: String): String {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val serverMessage = listOfNotNull(
            obj?.get("error")?.jsonPrimitive?.contentOrNull,
            obj?.get("msg")?.jsonPrimitive?.contentOrNull,
            obj?.get("message")?.jsonPrimitive?.contentOrNull
        ).firstOrNull { it.isNotBlank() }
        if (serverMessage != null) {
            return when {
                serverMessage.equals("Invalid login credentials", ignoreCase = true) ->
                    "账号或密码不对"
                serverMessage.equals("Email not confirmed", ignoreCase = true) ->
                    "邮箱还没确认，请先在 Supabase 里确认这个账号"
                serverMessage.equals("User already registered", ignoreCase = true) ->
                    "这个邮箱已经注册过了"
                else -> serverMessage
            }
        }
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

        /** 快照单页条数（与服务端 `SNAPSHOT_MAX_LIMIT` 对齐）。 */
        const val SNAPSHOT_LIMIT = 500

        /** access_token 到期前多久就提前续期，避免请求飞到一半刚好过期。 */
        const val TOKEN_REFRESH_AHEAD_MS = 60_000L

        /** 服务端没给 expires_in 时按 Supabase 默认的一小时算。 */
        const val DEFAULT_EXPIRES_IN_SECONDS = 3_600L

        /**
         * 用户名 → Supabase Auth 邮箱。
         *
         * Supabase 只认邮箱，而登录框里填的是「时天 / 柠檬」。这份映射必须与
         * server/supabase/0002_bind_family.sql 完全一致；换真实邮箱时两边一起改。
         */
        val SUPABASE_EMAILS = mapOf(
            "时天" to "shitian@lemonbox.app",
            "柠檬" to "ningmeng@lemonbox.app"
        )

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
