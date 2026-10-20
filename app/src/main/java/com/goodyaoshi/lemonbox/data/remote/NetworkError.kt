package com.goodyaoshi.lemonbox.data.remote

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 把网络层异常翻译成用户能据此行动的中文原因。
 *
 * 之前所有异常都被压成「连不上服务器，检查网络或稍后再试」，DNS 污染、TLS 拦截、
 * 超时、服务端不可达看起来完全一样，既无法自查也没法判断是不是域名问题。
 */
internal object NetworkError {

    fun describe(error: Throwable): String = when (error) {
        is UnknownHostException ->
            "域名解析失败，可能是 DNS 污染；已尝试 DoH 解析"
        is SSLException ->
            "TLS 握手失败（可能是证书、SNI 被干扰或网络中间人）"
        is SocketTimeoutException ->
            "连接或读取超时（网络不通或服务端无响应）"
        is ConnectException ->
            "连接被拒绝或网络不可达"
        is IOException ->
            "网络错误：${error.message ?: error.javaClass.simpleName}"
        else ->
            error.message ?: error.javaClass.simpleName
    }
}
