package com.goodyaoshi.lemonbox.util

import kotlin.math.max

/**
 * 单调递增的毫秒时间戳。
 *
 * 跨设备合并用 updatedAt「较新者胜」。若设备时钟回拨（手动改时间、NTP 校正），
 * 直接使用 System.currentTimeMillis() 会让后发生的修改带上更小的 updatedAt，
 * 导致合并时被旧数据覆盖。这里保证同一进程内返回的时间戳永不回退。
 */
object MonotonicClock {

    private var lastValue = 0L

    @Synchronized
    fun now(): Long {
        val current = System.currentTimeMillis()
        lastValue = max(lastValue, current)
        return lastValue
    }
}