package com.goodyaoshi.lemonbox.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS over HTTPS 解析器：在系统 DNS 被污染时仍能拿到业务域名的真实 A/AAAA 记录。
 *
 * 为什么不是「固定解析到 Cloudflare IP」：Cloudflare 的 anycast IP 会按地区与时间变化，
 * 直接连 IP 还会让 TLS 证书/SNI 对不上，长期维护成本高。DoH 只替换「域名 → IP」这一步，
 * 后续仍用域名完成 SNI 与证书校验，行为与普通 HTTPS 完全一致。
 *
 * 端点顺序刻意把国内可用的阿里、腾讯放前面，Cloudflare/Google 作为兜底。
 * 每个端点都用 JSON API（`application/dns-json`），失败就换下一个。
 */
internal class DohResolver(
    private val timeoutMs: Int = 3_000
) {

    private data class CacheEntry(val expiresAt: Long, val addresses: List<InetAddress>)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val json = Json { ignoreUnknownKeys = true }

    private val endpoints = listOf(
        "https://dns.alidns.com/resolve",
        "https://doh.pub/dns-query",
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/resolve"
    )

    /** 返回解析到的地址；全部端点失败时返回空列表，由调用方决定是否回退系统 DNS。 */
    fun resolve(hostname: String): List<InetAddress> {
        cache[hostname]?.let { entry ->
            if (entry.expiresAt > System.currentTimeMillis()) return entry.addresses
        }
        for (endpoint in endpoints) {
            val addresses = query(endpoint, hostname)
            if (addresses.isNotEmpty()) {
                cache[hostname] = CacheEntry(System.currentTimeMillis() + CACHE_TTL_MS, addresses)
                return addresses
            }
        }
        return emptyList()
    }

    private fun query(endpoint: String, hostname: String): List<InetAddress> {
        var connection: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(hostname, "UTF-8")
            connection = (URL("$endpoint?name=$encoded&type=A").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/dns-json")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val root = json.parseToJsonElement(text).jsonObject
            (root["Answer"] as? JsonArray).orEmpty()
                .mapNotNull { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.intOrNull == 1 }
                .mapNotNull { it["data"]?.jsonPrimitive?.content }
                .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        } catch (e: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        /** DoH 结果缓存 5 分钟：家庭网络里域名解析很少变化，避免每个请求都多一次往返。 */
        const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
