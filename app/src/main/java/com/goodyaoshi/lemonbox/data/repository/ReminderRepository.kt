package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.dao.ReminderDao
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.ReminderRepeatType
import com.goodyaoshi.lemonbox.data.local.entity.ReminderSource
import com.goodyaoshi.lemonbox.util.MonotonicClock
import com.goodyaoshi.lemonbox.util.ReminderClock
import com.goodyaoshi.lemonbox.util.TodoReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 待办提醒的数据入口：所有写操作之后都会重新调排后台任务。
 * 用户操作盖 syncId/updatedAt（随备份/同步传递）；Worker 推进的调度状态不动时间戳。
 */
@Singleton
class ReminderRepository @Inject constructor(
    private val reminderDao: ReminderDao,
    private val scheduler: TodoReminderScheduler
) {
    fun getAllReminders(): Flow<List<Reminder>> = reminderDao.getAllReminders()

    fun getActiveReminders(): Flow<List<Reminder>> = reminderDao.getActiveReminders()

    /**
     * 今天（含已提醒还没完成）的待办提醒：进行中且触发时刻在今天 24 点前。
     * 首页「今天的事」与「我的」页的待办徽标共用这一份口径，保证两处数字永远一致（IA-7）。
     */
    fun getDueTodayReminders(): Flow<List<Reminder>> = reminderDao.getActiveReminders()
        .map { list ->
            val endOfToday = LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            list.filter { it.nextFireAt < endOfToday }
        }

    /** 进行中的菜谱准备提醒来源键（meal_prep:日期），周菜谱据此把按钮显示为「已提醒」。 */
    fun getMealPrepKeys(): Flow<Set<String>> = reminderDao.getActiveReminders()
        .map { list ->
            list.filter { it.sourceKind == ReminderSource.MEAL_PREP }
                .mapNotNull { it.sourceKey }
                .toSet()
        }

    suspend fun findActiveBySourceKey(sourceKey: String): Reminder? =
        reminderDao.findActiveBySourceKey(sourceKey)

    /** 新建提醒；算不出未来的触发时刻（比如给已过去的时间建一次性提醒）返回 false。 */
    suspend fun create(reminder: Reminder): Boolean {
        val first = ReminderClock.firstFireAt(reminder) ?: return false
        reminderDao.insert(
            reminder.copy(
                nextFireAt = first,
                syncId = reminder.syncId ?: UUID.randomUUID().toString(),
                updatedAt = System.currentTimeMillis(),
                dirty = true
            )
        )
        scheduler.reschedule()
        return true
    }

    /**
     * 编辑提醒（GAP-8）：重算下一次触发时间并清掉已提醒标记，让改动立即生效；
     * 一次性提醒的新时刻已过时返回 false，由页面就地提示（与新建同一套规则）。
     */
    suspend fun update(reminder: Reminder): Boolean {
        val first = ReminderClock.firstFireAt(reminder) ?: return false
        reminderDao.update(
            reminder.copy(
                nextFireAt = first,
                notifiedAt = 0,
                updatedAt = System.currentTimeMillis(),
                dirty = true
            )
        )
        scheduler.reschedule()
        return true
    }

    /**
     * 稍后（GAP-9）：把下一次触发时间顺延 [minutes] 分钟，重复方式与其它字段保持不变。
     * 用于到点后暂时不想做、但又不愿勾「完成」污染周期节奏的场景。
     */
    suspend fun snooze(reminder: Reminder, minutes: Long) {
        val next = ZonedDateTime.now().plusMinutes(minutes).toInstant().toEpochMilli()
        reminderDao.update(
            reminder.copy(
                nextFireAt = next,
                notifiedAt = 0,
                updatedAt = System.currentTimeMillis(),
                dirty = true
            )
        )
        scheduler.reschedule()
    }

    /** 开关：关闭即暂停；重新开启时触发时刻已过的顺延到下一轮（一次性提醒时刻已过则不恢复）。 */
    suspend fun setEnabled(reminder: Reminder, enabled: Boolean) {
        if (enabled == reminder.enabled) return
        if (!enabled) {
            reminderDao.update(
                reminder.copy(
                    enabled = false,
                    updatedAt = System.currentTimeMillis(),
                    dirty = true
                )
            )
            scheduler.reschedule()
            return
        }
        val now = ZonedDateTime.now()
        val next = if (reminder.nextFireAt > now.toInstant().toEpochMilli()) {
            reminder.nextFireAt
        } else {
            ReminderClock.nextAfter(reminder, LocalDate.now(), now)
        } ?: return
        // 恢复时清掉已提醒标记，到点会重新通知。
        reminderDao.update(
            reminder.copy(
                enabled = true,
                nextFireAt = next,
                notifiedAt = 0,
                updatedAt = System.currentTimeMillis(),
                dirty = true
            )
        )
        scheduler.reschedule()
    }

    /** 完成：一次性提醒记为完成；周期提醒从今天推进下一轮（提前做完也能提前重置节奏）。 */
    suspend fun complete(reminder: Reminder) {
        if (!reminder.enabled) return
        if (reminder.repeat == ReminderRepeatType.ONCE) {
            reminderDao.update(
                reminder.copy(
                    enabled = false,
                    completedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    dirty = true
                )
            )
        } else {
            val next = ReminderClock.nextAfter(reminder, LocalDate.now(), ZonedDateTime.now())
                ?: return
            reminderDao.update(
                reminder.copy(
                    nextFireAt = next,
                    notifiedAt = 0,
                    updatedAt = System.currentTimeMillis(),
                    dirty = true
                )
            )
        }
        scheduler.reschedule()
    }

    /**
     * 撤销完成（H1）：用完成前的快照原样写回——一次性提醒恢复启用，周期提醒回到上一轮触发时间。
     * 快照必须来自完成前的实体（首页「今天的事」勾掉时先捕获再落库）。
     */
    suspend fun restoreCompleted(reminder: Reminder) {
        reminderDao.update(
            reminder.copy(updatedAt = System.currentTimeMillis(), dirty = true)
        )
        scheduler.reschedule()
    }

    /** 未删过则软删（可撤销）；已在回收站里再删即彻底删除，改写成永久删除墓碑。 */
    suspend fun delete(reminder: Reminder) {
        val now = MonotonicClock.now()
        if (reminder.deletedAt != null) {
            reminderDao.markPurged(ids = listOf(reminder.id), timestamp = now)
        } else {
            reminderDao.softDelete(id = reminder.id, deletedAt = now, updatedAt = now)
        }
        scheduler.reschedule()
    }

    /** 撤销删除（R1）：清掉墓碑标记让提醒复活，并按原计划重排调度。 */
    suspend fun restore(reminder: Reminder) {
        reminderDao.update(
            reminder.copy(deletedAt = null, updatedAt = System.currentTimeMillis(), dirty = true)
        )
        scheduler.reschedule()
    }

    // ---- 备份/同步 ----

    suspend fun getAllSnapshot(): List<Reminder> = reminderDao.getAllSnapshot()

    /** [markDirty] 时本机改动尚未进服务端（LAN 同步 / 本地恢复），标脏让增量推送带走它。 */
    suspend fun insertSynced(reminder: Reminder, markDirty: Boolean = false) {
        reminderDao.insert(reminder.copy(dirty = markDirty))
    }

    suspend fun updateSynced(reminder: Reminder, markDirty: Boolean = false) {
        reminderDao.update(reminder.copy(dirty = markDirty))
    }

    /** 收到对方的永久删除墓碑：本地那份同样标成永久删除，以便继续向第三方传播。 */
    suspend fun markPurged(ids: List<Long>, purgedAt: Long) {
        if (ids.isEmpty()) return
        reminderDao.markPurged(ids, purgedAt)
        scheduler.reschedule()
    }

    /** 收敛来源型提醒（菜谱准备 / 胶囊解锁）在多设备上各自生成的重复项，返回清理条数。 */
    suspend fun deduplicateReminders(): Int {
        val duplicates = selectReminderDuplicates(reminderDao.getAllSnapshot())
        if (duplicates.isEmpty()) return 0
        val now = MonotonicClock.now()
        duplicates.forEach { id ->
            reminderDao.softDelete(id = id, deletedAt = now, updatedAt = now)
        }
        scheduler.reschedule()
        return duplicates.size
    }

    suspend fun purgeDeletedOlderThan(cutoff: Long) {
        reminderDao.purgeDeletedOlderThan(cutoff)
    }

    /** 清到期的永久删除墓碑，保留期更长（理由见 `ItemRepository.purgePurgedItemsOlderThan`）。 */
    suspend fun purgePurgedOlderThan(cutoff: Long) {
        reminderDao.purgePurgedOlderThan(cutoff)
    }
}

/**
 * 选出应被收敛掉的来源型提醒重复项 id。
 *
 * 抽成顶层纯函数是因为它决定**哪一条存活**：规则与设备无关（同 sourceKey 取 syncId
 * 字典序最小者），否则两台设备各留不同副本、互相删掉对方的，最终一条都不剩。
 * 手动提醒（sourceKey 为空）与已删墓碑不参与。
 */
internal fun selectReminderDuplicates(all: List<Reminder>): List<Long> =
    all.filter { it.deletedAt == null && !it.sourceKey.isNullOrBlank() }
        .groupBy { it.sourceKey }
        .values
        .filter { it.size > 1 }
        .flatMap { group ->
            val keeper = group.minByOrNull { it.syncId ?: "id:${it.id}" }
            group.filter { it.id != keeper?.id }.map { it.id }
        }
