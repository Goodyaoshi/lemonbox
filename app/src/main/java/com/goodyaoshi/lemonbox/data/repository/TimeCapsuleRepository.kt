package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.dao.TimeCapsuleDao
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.ReminderRepeatType
import com.goodyaoshi.lemonbox.data.local.entity.ReminderSource
import com.goodyaoshi.lemonbox.data.local.entity.TimeCapsule
import com.goodyaoshi.lemonbox.util.BrandCopy
import com.goodyaoshi.lemonbox.util.MonotonicClock
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 时间胶囊仓库（F14）：普通写入盖 syncId/updatedAt；打开胶囊只记 openedAt，
 * 同样盖 updatedAt 以便跨设备同步「已打开」状态。
 * 到期提醒（报告 F14「复用 Reminder ONCE」）：创建胶囊时联动生成解锁日当天的
 * 一次性提醒（来源 CAPSULE、sourceKey=capsule:syncId），删除/打开胶囊时同步撤提醒/标完成。
 */
@Singleton
class TimeCapsuleRepository @Inject constructor(
    private val dao: TimeCapsuleDao,
    private val reminderRepository: ReminderRepository
) {
    fun observeAll(): Flow<List<TimeCapsule>> = dao.observeAll()

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun getById(id: Long): TimeCapsule? = dao.getById(id)

    /** 新建胶囊；返回新 id。解锁日合法时联动创建到期提醒（报告 F14 的「到期提醒」）。 */
    suspend fun create(capsule: TimeCapsule): Long {
        val saved = capsule.copy(
            id = 0,
            syncId = capsule.syncId ?: UUID.randomUUID().toString(),
            createdAt = capsule.createdAt.takeIf { it > 0 } ?: MonotonicClock.now(),
            updatedAt = MonotonicClock.now()
        )
        val id = dao.insert(saved)
        scheduleUnlockReminder(saved.copy(id = id))
        return id
    }

    suspend fun update(capsule: TimeCapsule) {
        dao.update(
            capsule.copy(
                syncId = capsule.syncId ?: UUID.randomUUID().toString(),
                updatedAt = MonotonicClock.now()
            )
        )
    }

    /** 首次打开：记录打开时间（已打开过则不重复覆盖），并把待办的到期提醒标为完成。 */
    suspend fun open(capsule: TimeCapsule) {
        if (capsule.openedAt != null) return
        dao.update(capsule.copy(openedAt = MonotonicClock.now(), updatedAt = MonotonicClock.now()))
        reminderRepository.findActiveBySourceKey(capsuleSourceKey(capsule))?.let { reminder ->
            reminderRepository.complete(reminder)
        }
    }

    /** 软删除：墓碑随备份/同步传递；联动撤掉还没触发的到期提醒。 */
    suspend fun softDelete(capsule: TimeCapsule) {
        val now = MonotonicClock.now()
        dao.softDelete(id = capsule.id, deletedAt = now, updatedAt = now)
        reminderRepository.findActiveBySourceKey(capsuleSourceKey(capsule))?.let { reminder ->
            reminderRepository.delete(reminder)
        }
    }

    suspend fun purgeDeletedOlderThan(cutoff: Long) = dao.purgeDeletedOlderThan(cutoff)

    // ---- 到期提醒（复用 Reminder ONCE，报告 F14）----

    /** 胶囊提醒的关联键：capsule:{syncId}，与菜谱准备提醒（meal_prep:日期）同机制。 */
    private fun capsuleSourceKey(capsule: TimeCapsule): String = "capsule:${capsule.syncId}"

    private suspend fun scheduleUnlockReminder(capsule: TimeCapsule) {
        val unlockDate = Instant.ofEpochMilli(capsule.unlockAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val key = capsuleSourceKey(capsule)
        // 去重：同一颗胶囊（跨设备同步场景）只建一条提醒。
        if (reminderRepository.findActiveBySourceKey(key) != null) return
        reminderRepository.create(
            Reminder(
                title = "时间胶囊「${capsule.title}」今天可以打开了",
                note = "到 $BrandCopy.APP_NAME 里拆开这封未来的信吧",
                repeatType = ReminderRepeatType.ONCE.name,
                targetDate = unlockDate.toString(),
                fireTime = "09:00",
                source = ReminderSource.CAPSULE.name,
                sourceKey = key
            )
        )
    }

    // ---- 备份/同步 ----

    suspend fun getAllSnapshot(): List<TimeCapsule> = dao.getAllSnapshot()

    suspend fun insertSynced(capsule: TimeCapsule): Long = dao.insert(capsule)

    suspend fun updateSynced(capsule: TimeCapsule) = dao.update(capsule)
}
