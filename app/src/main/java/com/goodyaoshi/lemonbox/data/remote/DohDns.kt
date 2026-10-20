package com.goodyaoshi.lemonbox.data.remote

import okhttp3.Dns
import java.net.InetAddress

/**
 * 只对同步服务端的域名走 DoH，其余域名保持系统 DNS。
 *
 * 这样即使 DoH 端点本身被干扰，也不会影响图片、局域网等其他网络路径；
 * 同步域名解析失败时退回系统 DNS，保证「DoH 不可用」不会比现状更差。
 */
internal class DohDns(
    private val resolver: DohResolver,
    private val syncHosts: () -> Set<String>
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname !in syncHosts()) return Dns.SYSTEM.lookup(hostname)
        val resolved = resolver.resolve(hostname)
        return resolved.ifEmpty { Dns.SYSTEM.lookup(hostname) }
    }
}
