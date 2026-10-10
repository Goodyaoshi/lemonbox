package com.goodyaoshi.lemonbox.data.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 局域网内发现到的可同步设备。
 * [token] 现已不再随 mDNS 广播，恒为 null；仅当对方为仍在广播配对码的旧版本时才可能非空。
 * 现代版本一律要求用户手动输入对方屏幕上显示的 6 位配对码。
 */
data class SyncPeer(
    val name: String,
    val host: String,
    val port: Int,
    val token: String? = null
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

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var resolving = false

    /** 本机广播的服务名；发现列表里据此排除自己。 */
    private var ownServiceName: String? = null

    private val _peers = MutableStateFlow<List<SyncPeer>>(emptyList())
    val peers: StateFlow<List<SyncPeer>> = _peers.asStateFlow()

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

    private fun registerService(port: Int) {
        registrationListener?.let { listener -> runCatching { nsdManager.unregisterService(listener) } }
        val name = "柠檬百宝箱-${appPreferences.deviceId.take(4)}"
        ownServiceName = name
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            this.port = port
            // 安全考量：TXT 记录只广播设备 ID，绝不广播配对码。
            // 同一 WiFi 下任何设备都能读到 TXT 记录，广播配对码等于把鉴权秘密公开，
            // 因此配对码改为由用户从对方屏幕上读取后手动输入（下方「手动输入」）。
            setAttribute(ATTR_DEVICE, appPreferences.deviceId)
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
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                resolving = false
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                resolving = false
                val resolved = serviceInfo ?: return
                val host = resolved.host?.hostAddress ?: return
                // 现代版本不再广播配对码；仅旧版本可能仍带上，读到则沿用，否则由用户手动输入。
                val token = resolved.attributes?.get(ATTR_TOKEN)
                    ?.toString(StandardCharsets.UTF_8)
                    ?.takeIf { it.isNotBlank() }
                val peer = SyncPeer(
                    name = resolved.serviceName,
                    host = host,
                    port = resolved.port,
                    token = token
                )
                _peers.value = _peers.value.filterNot { it.name == peer.name } + peer
            }
        }
        runCatching { nsdManager.resolveService(service, listener) }
            .onFailure { resolving = false }
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

        /**
         * TXT 记录键：设备号 / 配对码。
         * 本机只广播 [ATTR_DEVICE]；[ATTR_TOKEN] 仅为兼容仍在广播配对码的旧版本而保留「读取」能力。
         */
        private const val ATTR_TOKEN = "token"
        private const val ATTR_DEVICE = "device"

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