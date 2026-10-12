package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 时间胶囊（F14）：写给未来的一段话，设定一个解锁时间，到点前只能看到
 * 「还有 N 天」，到点后才允许打开阅读。
 *
 * [unlockAt] 为可打开的起始时间戳；[openedAt] 记录首次打开的时间（null = 还没打开）。
 * [forWhom] 是可选的对象标注（写给谁），纯展示用，不参与解锁逻辑。
 */
@Entity(tableName = "time_capsules")
data class TimeCapsule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String = "",
    /** 可打开的起始时间戳（毫秒）；到达该时间前内容不可见。 */
    val unlockAt: Long,
    /** 首次打开的时间戳（毫秒）；null 表示尚未打开。 */
    val openedAt: Long? = null,
    /** 写给谁（可选标注，如「给三年后的自己」）。 */
    val forWhom: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** 跨设备合并用的稳定标识。 */
    val syncId: String? = UUID.randomUUID().toString(),
    val updatedAt: Long? = System.currentTimeMillis(),
    /** 软删除墓碑；非空表示已删除。 */
    val deletedAt: Long? = null
) {
    /** 是否已到可打开时间。 */
    fun isUnlocked(now: Long = System.currentTimeMillis()): Boolean = now >= unlockAt

    /** 距可打开还剩的整天数（不足一天按 1 天算）；已解锁返回 0。 */
    fun daysUntilUnlock(now: Long = System.currentTimeMillis()): Long {
        if (isUnlocked(now)) return 0
        val remaining = unlockAt - now
        return (remaining + DAY_MILLIS - 1) / DAY_MILLIS
    }

    companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
