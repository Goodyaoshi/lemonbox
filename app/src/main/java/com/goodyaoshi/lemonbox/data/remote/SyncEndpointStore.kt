package com.goodyaoshi.lemonbox.data.remote

import android.content.Context
import com.goodyaoshi.lemonbox.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次服务器探活的结果。[detail] 面向用户，说明为什么连不上或连到了哪里。
 */
data class EndpointProbe(
    val baseUrl: String,
    val reachable: Boolean,
    val detail: String
)

/**
 * 同步服务端地址的候选与选择。
 *
 * 背景：默认域名是免费二级域（`*.eu.cc`），存在被 DNS 污染/阻断的风险；而固定解析到
 * Cloudflare IP 并不可靠（anycast IP 会变、证书/SNI 对不上）。这里做两件事：
 *
 *  1. 允许用户填一个**自定义服务器地址**（同一 Worker 的备用域名、反代地址等）；
 *  2. 登录/同步前按「自定义地址 → 构建默认地址」顺序探活 `/health`，选第一个可用的，
 *     并把实际使用地址存下来。这样换域名不需要重新发版。
 *
 * 地址只接受 https：把同步令牌发到明文地址等于把家庭数据交给链路上的任何人。
 *
 * 注意：探活本身由 [RemoteSyncClient] 执行（那里持有配置了 DoH 的 OkHttpClient），
 * 本类刻意不引用 OkHttp 类型，避免 Hilt/KSP 在处理构造函数时被第三方类型拖累。
 */
@Singleton
class SyncEndpointStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 安装包里注入的默认地址（BuildConfig.SYNC_BASE_URL）。 */
    val defaultBaseUrl: String = BuildConfig.SYNC_BASE_URL.trim().trimEnd('/')

    fun customBaseUrl(): String? =
        prefs.getString(KEY_CUSTOM, null)?.takeIf { it.isNotBlank() }

    /** 保存/清除自定义地址；改动后清掉 active，下一次登录会重新探活。 */
    fun setCustomBaseUrl(raw: String?) {
        val normalized = normalize(raw)
        prefs.edit().apply {
            if (normalized == null) remove(KEY_CUSTOM) else putString(KEY_CUSTOM, normalized)
            remove(KEY_ACTIVE)
        }.apply()
    }

    /** 当前候选：自定义地址优先，其次是构建默认地址。 */
    fun candidates(): List<String> = buildList {
        customBaseUrl()?.let(::add)
        if (defaultBaseUrl.isNotBlank()) add(defaultBaseUrl)
    }.distinct()

    /** 最近一次探活成功的地址；还没探活过则用默认地址。 */
    fun activeBaseUrl(): String {
        val active = prefs.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }
        return if (active != null && isKnownBaseUrl(active)) active else defaultBaseUrl
    }

    /** 记录探活成功的地址。 */
    fun markActive(base: String) {
        val normalized = normalize(base) ?: return
        prefs.edit().putString(KEY_ACTIVE, normalized).apply()
    }

    fun isKnownBaseUrl(url: String): Boolean =
        normalize(url)?.let { it in candidates() } == true

    /** 当前候选地址的 host 集合，供 [DohDns] 判断哪些域名需要走 DoH。 */
    fun candidateHosts(): Set<String> = candidates().mapNotNull { base ->
        runCatching { URL(base).host.takeIf { it.isNotBlank() } }.getOrNull()
    }.toSet()

    /** 规范化：去空格与结尾斜杠，只接受 https。 */
    private fun normalize(raw: String?): String? {
        val value = raw?.trim()?.trimEnd('/') ?: return null
        if (value.isEmpty()) return null
        return runCatching {
            val url = URL(value)
            if (url.protocol == "https" && url.host.isNotBlank()) value else null
        }.getOrNull()
    }

    private companion object {
        const val PREFS_NAME = "lemon_sync_endpoint"
        const val KEY_CUSTOM = "custom_base_url"
        const val KEY_ACTIVE = "active_base_url"
    }
}
