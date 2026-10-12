package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.repository.AnniversaryRepository
import com.goodyaoshi.lemonbox.data.repository.CookedDaySnapshot
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import com.goodyaoshi.lemonbox.data.repository.WeekMenuRepository
import com.goodyaoshi.lemonbox.data.repository.WeeklyMealDay
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** 首页「本月账」速览条的数据：当月支出与总预算（分，未设总预算时为 null）。 */
data class MonthSpending(
    val spentCents: Long,
    val budgetCents: Long?
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val reminderRepository: ReminderRepository,
    private val anniversaryRepository: AnniversaryRepository,
    private val ledgerRepository: LedgerRepository,
    private val weekMenuRepository: WeekMenuRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    val activeItems: StateFlow<List<ItemDetail>> = itemRepository.getActiveItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 她的称呼：首页问候里的「柠檬，早上好」用它，可在「我的」页修改。 */
    val nickname: StateFlow<String> = appPreferences.nickname

    /** 临期物品：在库、有有效期，且剩余天数落在它自己（或全局）提醒阶梯的最大窗口内。 */
    val expiringItems: StateFlow<List<ItemDetail>> =
        combine(activeItems, appPreferences.reminderLadder) { items, ladder ->
            items.filter { isExpiringSoon(it.item, ladder) }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 逐件判定是否临期：用物品自己的阶梯窗口（未设置则跟随全局）。 */
    private fun isExpiringSoon(item: Item, globalLadder: List<Int>): Boolean {
        val expireTime = item.expireTime ?: return false
        if (expireTime <= 0L) return false
        val window = Item.reminderWindowDays(item.reminderDays, globalLadder)
        return DateUtil.daysUntil(expireTime) <= window
    }

    /** 未来 N 天菜单（今天吃什么卡要用今天那天），生成与扣料逻辑都在 WeekMenuRepository。 */
    val weekPlan: StateFlow<List<WeeklyMealDay>> = weekMenuRepository.weekPlan
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 首页「纪念日」速览：最近的 1-2 条（未到的按剩余天数升序，累计的在一起越久越靠前）。 */
    val anniversaryHighlights: StateFlow<List<AnniversaryRow>> =
        anniversaryRepository.getActiveAnniversaries().map { list ->
            val today = LocalDate.now()
            list.map { it.toRow(today) }
                .filter { it.days != null }
                .sortedWith(
                    compareBy(
                        { (it.days ?: 0L) < 0 },
                        { if ((it.days ?: 0L) < 0) -(it.days ?: 0L) else it.days ?: 0L }
                    )
                )
                .take(2)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Hero 里那个「只有你们懂的数字」：
     * 优先用设置里配置的「在一起」起始日算「前缀第 N 天」；
     * 未配置时回退到累计型（TYPE_COUNTUP）纪念日里开始最早的一条派生，兼容老数据。
     */
    val togetherDays: StateFlow<String?> = combine(
        appPreferences.togetherStartDate,
        appPreferences.togetherLabelName,
        anniversaryRepository.getActiveAnniversaries()
    ) { startDate, label, list ->
        configuredTogetherDays(startDate, label) ?: derivedTogetherDays(list)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 用设置里配置的起始日计算「前缀第 N 天」；未配置或起算不足 1 天时返回 null。 */
    private fun configuredTogetherDays(startDate: String?, label: String): String? {
        val start = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val days = ChronoUnit.DAYS.between(start, LocalDate.now()) + 1
        return if (days < 1) null else "${label}第 $days 天"
    }

    /** 旧派生逻辑：取累计型纪念日里开始最早的一条，拼成「名称第 N 天」。 */
    private fun derivedTogetherDays(list: List<Anniversary>): String? {
        val today = LocalDate.now()
        return list.map { it.toRow(today) }
            .filter { it.anniversary.type == Anniversary.TYPE_COUNTUP && (it.days ?: 0L) <= 0L }
            .minByOrNull { it.days ?: 0L }
            ?.let { "${it.name}第 ${-(it.days ?: 0L) + 1} 天" }
    }

    /** 一餐做完：仓库内幂等（已做不重复扣料），完成后回传扣料前快照供首页「撤销」（GAP-12）。 */
    fun markDayCooked(dateKey: String, onDone: (CookedDaySnapshot?) -> Unit = {}) {
        viewModelScope.launch { onDone(weekMenuRepository.markDayCooked(dateKey)) }
    }

    /** 撤销「做了」（GAP-12）：按快照把被扣食材写回、撤掉已做标记。 */
    fun undoMarkDayCooked(snapshot: CookedDaySnapshot) {
        viewModelScope.launch { weekMenuRepository.undoMarkDayCooked(snapshot) }
    }

    /** 今天（含已提醒还没完成）的提醒待办：口径统一在 ReminderRepository（IA-7），与「我的」页待办徽标同源。 */
    val todayReminders: StateFlow<List<Reminder>> = reminderRepository.getDueTodayReminders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 待买清单：今天要去补/要买的东西。 */
    val toBuyItems: StateFlow<List<ItemDetail>> = itemRepository.getToBuyItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 本月账速览：开关关闭时为 null；未设总预算时只显示已花。 */
    val monthSpending: StateFlow<MonthSpending?> = combine(
        ledgerRepository.observeBudgets(),
        appPreferences.ledgerMonthStartDay,
        appPreferences.budgetReminderEnabled
    ) { budgets, startDay, enabled ->
        if (!enabled) {
            null
        } else {
            val now = LocalDate.now()
            val (start, end) = LedgerMath.monthRange(now.year, now.monthValue, startDay)
            val total = budgets.firstOrNull { it.categoryId == null }?.amount?.takeIf { it > 0 }
            Triple(total, start, end)
        }
    }.flatMapLatest { target ->
        if (target == null) {
            flowOf(null)
        } else {
            val (total, start, end) = target
            ledgerRepository.observeRecordsBetween(start, end).map { records ->
                val spent = records
                    .filter { it.type == LedgerRecord.TYPE_EXPENSE }
                    .sumOf { it.amount }
                MonthSpending(spentCents = spent, budgetCents = total)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 「今天的事」：提醒待办 + 临期家当 + 待买，提醒在前（要紧），临期次之，待买在后。
     * 被「今天忽略」（H1）的临期事项当天不再出现，明天照常回来；
     * 当天被忽略的条目另见 [todayIgnoredEvents]（G-11）。
     */
    val todayEvents: StateFlow<List<TodayEvent>> = combine(
        todayReminders,
        expiringItems,
        toBuyItems,
        appPreferences.expiryIgnores
    ) { reminders, expiring, toBuy, ignores ->
        val today = LocalDate.now().toString()
        buildList {
            reminders.forEach { reminder ->
                add(
                    TodayEvent(
                        kind = TodayEventKind.TASK,
                        key = "reminder:${reminder.id}",
                        title = reminder.title,
                        detail = reminder.fireTime.takeIf { it.isNotBlank() },
                        target = TodayEventTarget.Reminders,
                        completable = true,
                        reminderId = reminder.id
                    )
                )
            }
            expiring.forEach { detail ->
                val key = "expiry:${detail.item.id}"
                if (ignores.containsToday(key, today)) return@forEach
                add(
                    TodayEvent(
                        kind = TodayEventKind.EXPIRY,
                        key = key,
                        title = detail.item.name,
                        detail = expiryDetail(detail.item.expireTime),
                        target = TodayEventTarget.ItemDetail(detail.item.id),
                        itemId = detail.item.id
                    )
                )
            }
            toBuy.forEach { detail ->
                add(
                    TodayEvent(
                        kind = TodayEventKind.TO_BUY,
                        key = "tobuy:${detail.item.id}",
                        title = detail.item.name,
                        detail = "待买 ${detail.item.quantity}${detail.item.unit}",
                        target = TodayEventTarget.ToBuy
                    )
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 今天已被「今天忽略」的临期事项（G-11）：不进正常清单，但以灰字小行留在卡片下方，
     * 让她记得「不是提醒丢了，是自己今天先不理它」；行尾「撤销」放回后本列表自然清空。
     */
    val todayIgnoredEvents: StateFlow<List<TodayEvent>> = combine(
        expiringItems,
        appPreferences.expiryIgnores
    ) { expiring, ignores ->
        val today = LocalDate.now().toString()
        expiring.mapNotNull { detail ->
            val key = "expiry:${detail.item.id}"
            if (!ignores.containsToday(key, today)) return@mapNotNull null
            // 与 todayEvents 的 EXPIRY 分支同构：标题、detail、去向完全一致，只差「被忽略」这层状态。
            TodayEvent(
                kind = TodayEventKind.EXPIRY,
                key = key,
                title = detail.item.name,
                detail = expiryDetail(detail.item.expireTime),
                target = TodayEventTarget.ItemDetail(detail.item.id),
                itemId = detail.item.id
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 在今日清单里勾掉一条提醒待办（一次性记完成，周期提醒推进下一轮）。
     * 勾掉前先经 [onCompleted] 回传完成前的快照，UI 用它接撤销（H1）。
     */
    fun completeTodayEvent(event: TodayEvent, onCompleted: (Reminder?) -> Unit = {}) {
        val id = event.reminderId ?: return
        viewModelScope.launch {
            todayReminders.value.firstOrNull { it.id == id }?.let { reminder ->
                onCompleted(reminder)
                reminderRepository.complete(reminder)
            }
        }
    }

    /** 撤销勾掉：把提醒恢复到完成前的状态（H1）。 */
    fun undoCompleteTodayEvent(snapshot: Reminder) {
        viewModelScope.launch { reminderRepository.restoreCompleted(snapshot) }
    }

    /** 「今天忽略」一条临期事项（H1）：只影响今天，明天它会照常回来。 */
    fun ignoreExpiryToday(event: TodayEvent) {
        if (event.kind != TodayEventKind.EXPIRY) return
        appPreferences.ignoreExpiryForToday(event.key)
    }

    /** 撤销今天的忽略（Snackbar 撤销用）。 */
    fun undoIgnoreExpiryToday(event: TodayEvent) {
        if (event.kind != TodayEventKind.EXPIRY) return
        appPreferences.undoExpiryIgnore(event.key)
    }

    /** 临期行的副文案：按剩余天数给出紧迫程度。 */
    private fun expiryDetail(expireTime: Long?): String? {
        val time = expireTime ?: return null
        if (time <= 0L) return null
        val days = DateUtil.daysUntil(time)
        return when {
            days < 0 -> "已过期 ${-days} 天"
            days == 0L -> "今天到期"
            else -> "还有 $days 天到期"
        }
    }
}
