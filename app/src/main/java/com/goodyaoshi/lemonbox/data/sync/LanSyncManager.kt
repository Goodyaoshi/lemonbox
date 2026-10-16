package com.goodyaoshi.lemonbox.data.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.BrandCopy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 局域网内发现到的可同步设备。
 * [token] 是配对码：本机不再广播它，因此自己这条通常为空；发现到的对端若仍在广播
 * （旧版本）则会带上读到的值。现代版本一律要求用户手动输入对方屏幕上显示的 6 位配对码。
 */
data class SyncPeer(
    val name: String,
    val host: String,
    val port: Int,
    val token: String? = null,
    /** 对方设备型号（如 Pixel 8），用于在列表里区分到底是哪台设备。 */
    val model: String? = null
)

/**
 * 管理局域网同步的两件事：把本机注册到局域网（mDNS），以及发现其它设备。
 * 发现失败时用户仍可手动输入对方显示的 IP。
 */
@Singleton
class LanSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncServer: SyncServer,
    private val appPreferences: AppPreferences
) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** 供 Android 14+ 的 ServiceInfoCallback 回调使用。 */
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var resolving = false

    /** 本机广播的服务名；发现列表里据此排除自己。 */
    private var ownServiceName: String? = null

    private val _peers = MutableStateFlow<List<SyncPeer>>(emptyList())
    val peers: StateFlow<List<SyncPeer>> = _peers.asStateFlow()

    /** 子网扫描用的后台作用域：扫描会在本类被回收前一直可用。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _scanning = MutableStateFlow(false)

    /** 是否正在扫描整个局域网网段。 */
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0)

    /** 扫描进度（0–100），供界面显示「已发现 x%」。 */
    val scanProgress: StateFlow<Int> = _scanProgress.asStateFlow()

    /** 本机型号，用于在「本机」卡片上标明这台设备到底是什么。 */
    val localDeviceModel: String get() = Build.MODEL?.takeIf { it.isNotBlank() } ?: "本机"

    /** 开始被连接：启动服务端并广播自己。返回本机局域网 IP，供对方手动输入兜底。 */
    fun startHosting(token: String): String? {
        if (!syncServer.start(token)) return null
        acquireMulticastLock()
        registerService(syncServer.listeningPort)
        return localIpAddress()
    }

    fun stopHosting() {
        stopDiscovery()
        registrationListener?.let { listener -> runCatching { nsdManager.unregisterService(listener) } }
        registrationListener = null
        ownServiceName = null
        syncServer.stop()
        releaseMulticastLock()
    }

    fun startDiscovery() {
        if (discoveryListener != null) return
        acquireMulticastLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) = Unit

            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                stopDiscovery()
            }

            override fun onServiceFound(service: NsdServiceInfo?) {
                // 自己广播的服务也会被自己发现，跳过，否则列表里会出现本机。
                val found = service ?: return
                if (found.serviceName == ownServiceName) return
                resolveService(found)
            }

            override fun onServiceLost(service: NsdServiceInfo?) {
                val name = service?.serviceName ?: return
                _peers.value = _peers.value.filterNot { it.name == name }
            }
        }
        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            discoveryListener = null
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let { listener -> runCatching { nsdManager.stopServiceDiscovery(listener) } }
        discoveryListener = null
        releaseMulticastLock()
    }

    fun clearPeers() {
        _peers.value = emptyList()
    }

    /**
     * 兜底发现：mDNS 依赖组播，在「路由器开了 AP 隔离」或部分手机开出的个人热点下会直接失效，
     * 表现就是「怎么都搜不到对方」。这里改用最朴素的办法——把本机所在 /24 网段挨个探一遍，
     * 谁的同步端口 `/ping` 有回应就是谁。
     *
     * 因此这条路径不依赖组播、不依赖任何发现协议，只要两台设备在同一网段能互相 ping 通就有效，
     * 个人热点场景同样适用。结果按 IP 去重并入 [peers]，不会和 mDNS 的结果重复。
     */
    fun scanLan() {
        if (_scanning.value) return
        val localIp = localIpAddress() ?: return
        val prefix = localIp.substringBeforeLast('.', "")
        if (prefix.isEmpty()) return
        _scanning.value = true
        _scanProgress.value = 0
        scope.launch {
            try {
                val targets = (1..254)
                    .map { "$prefix.$it" }
                    .filter { it != localIp }
                val total = targets.size
                val done = AtomicInteger(0)
                val semaphore = Semaphore(SCAN_CONCURRENCY)
                coroutineScope {
                    targets.forEach { host ->
                        launch {
                            semaphore.withPermit {
                                probePeer(host, SyncServer.DEFAULT_PORT)?.let { ping ->
                                    publishScannedPeer(ping, host, SyncServer.DEFAULT_PORT)
                                }
                                _scanProgress.value = done.incrementAndGet() * 100 / total
                            }
                        }
                    }
                }
            } finally {
                _scanning.value = false
                _scanProgress.value = 100
            }
        }
    }

    /** 用一次短超时 HTTP 请求探一个 IP：返回对端的设备信息，没有设备则返回 null。 */
    private fun probePeer(host: String, port: Int): PingResponse? = runCatching {
        val connection = URL("http://$host:$port/ping").openConnection() as HttpURLConnection
        connection.connectTimeout = SCAN_TIMEOUT_MS
        connection.readTimeout = SCAN_TIMEOUT_MS
        connection.requestMethod = "GET"
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            json.decodeFromString<PingResponse>(body)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** 把扫描到的设备并入 [peers]：排除自己，并对已在列表里的同一 IP 去重。 */
    private fun publishScannedPeer(ping: PingResponse, host: String, port: Int) {
        if (ping.deviceId == appPreferences.deviceId) return
        // 同一台设备可能既被 mDNS 发现、又被扫描到，保留信息更全的 mDNS 那条，不重复添加。
        if (_peers.value.any { it.host == host }) return
        val peer = SyncPeer(
            name = ping.deviceName.ifBlank { host },
            host = host,
            port = port,
            token = null,
            model = ping.deviceModel.takeIf { it.isNotBlank() }
        )
        _peers.value = _peers.value + peer
    }

    private fun registerService(port: Int) {
        registrationListener?.let { listener -> runCatching { nsdManager.unregisterService(listener) } }
        val name = "${BrandCopy.APP_NAME}-${appPreferences.deviceId.take(4)}"
        ownServiceName = name
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            this.port = port
            // 安全考量：TXT 记录只广播设备 ID 与型号，绝不广播配对码。
            // 同一 WiFi 下任何设备都能读到 TXT 记录，广播配对码等于把鉴权秘密公开，
            // 因此配对码改为由用户从对方屏幕上读取后手动输入（下方「手动输入」）。
            setAttribute(ATTR_DEVICE, appPreferences.deviceId)
            setAttribute(ATTR_MODEL, Build.MODEL ?: "")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) = Unit

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun resolveService(service: NsdServiceInfo) {
        // resolveService 不支持并发，逐个解析
        if (resolving) return
        resolving = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveServiceWithCallback(service)
        } else {
            resolveServiceLegacy(service)
        }
    }

    /** Android 14 起 resolveService 已废弃，改用官方推荐的 ServiceInfoCallback。 */
    private fun resolveServiceWithCallback(service: NsdServiceInfo) {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                resolving = false
            }

            override fun onServiceInfoCallbackUnregistered() = Unit

            override fun onServiceLost() {
                resolving = false
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                resolving = false
                publishPeer(serviceInfo)
            }
        }
        runCatching {
            nsdManager.registerServiceInfoCallback(service, mainExecutor, callback)
        }.onFailure { resolving = false }
    }

    /** API 26–33 上系统只提供已废弃的 resolveService，故仅在此分支屏蔽其废弃告警。 */
    @Suppress("DEPRECATION")
    private fun resolveServiceLegacy(service: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                resolving = false
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                resolving = false
                publishPeer(serviceInfo ?: return)
            }
        }
        runCatching { nsdManager.resolveService(service, listener) }
            .onFailure { resolving = false }
    }

    /** 把解析结果落成 [SyncPeer]；新旧系统取主机地址的入口不同。 */
    private fun publishPeer(resolved: NsdServiceInfo) {
        val host = hostAddressOf(resolved) ?: return
        // 彻底排除自己：以 TXT 记录里的设备号为准，而不是仅靠广播服务名。
        // 服务名依赖「先注册后被发现」的时序，界面初始化时可能先开始发现再开始广播，
        // 存在自己先冒出来的竞态；用设备号判断与顺序无关，最稳。
        val peerDeviceId = resolved.attributes?.get(ATTR_DEVICE)
            ?.toString(StandardCharsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
        if (peerDeviceId != null && peerDeviceId == appPreferences.deviceId) return
        // 现代版本不再广播配对码；仅旧版本可能仍带上，读到则沿用，否则由用户手动输入。
        val token = resolved.attributes?.get(ATTR_TOKEN)
            ?.toString(StandardCharsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
        val model = resolved.attributes?.get(ATTR_MODEL)
            ?.toString(StandardCharsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
        val peer = SyncPeer(
            name = resolved.serviceName,
            host = host,
            port = resolved.port,
            token = token,
            model = model
        )
        _peers.value = _peers.value.filterNot { it.name == peer.name } + peer
    }

    /** getHost() 自 Android 14 起废弃，14+ 读 getHostAddresses()，低版本仍只能读 getHost()。 */
    @Suppress("DEPRECATION")
    private fun hostAddressOf(info: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.firstOrNull()?.hostAddress
        } else {
            info.host?.hostAddress
        }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifiManager.createMulticastLock("lemon-sync").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
    }

    companion object {
        /** mDNS 服务类型，两端必须一致。 */
        const val SERVICE_TYPE = "_lemonsync._tcp"

        /** 子网扫描并发度：同时探测的 IP 数，兼顾扫描速度与对网络/电量的冲击。 */
        private const val SCAN_CONCURRENCY = 48

        /** 单个 IP 的探测超时（毫秒）：局域网内有设备会立刻回应，没设备即超时跳过。 */
        private const val SCAN_TIMEOUT_MS = 400

        /**
         * TXT 记录键：设备号 / 型号 / 配对码。
         * 本机广播 [ATTR_DEVICE] 与 [ATTR_MODEL]（用于双方互相识别、排除自己）；
         * [ATTR_TOKEN] 仅为兼容仍在广播配对码的旧版本而保留「读取」能力。
         */
        private const val ATTR_TOKEN = "token"
        private const val ATTR_DEVICE = "device"
        private const val ATTR_MODEL = "model"

        /** 取本机局域网 IPv4 地址，供对方手动输入兜底。 */
        fun localIpAddress(): String? {
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
            }.getOrNull()
        }
    }
}