package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import com.goodyaoshi.lemonbox.data.repository.AnniversaryRepository
import com.goodyaoshi.lemonbox.util.AnniversaryClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * 纪念日列表行的展示模型：日期口径与状态文案在这里统一算好，
 * 列表页、首页速览都复用同一套「还有 N 天 / 已 N 天」的措辞。
 */
data class AnniversaryRow(
    val anniversary: Anniversary,
    /** 日期标签：生日农历显示「农历八月十五」、公历「M月D日」；其余带年份。 */
    val dateLabel: String,
    /** 状态文案：倒数=「还有 N 天/已过 N 天」，正数=「第 N 天」，生日=「下次 M/D · 满 N 岁」。 */
    val statusText: String,
    /** 距下一次发生的天数：未来正数、今天 0、累计负数；算不出为 null。 */
    val days: Long?
) {
    val enabled: Boolean get() = anniversary.enabled
    val name: String get() = anniversary.name
    val note: String get() = anniversary.note
}

/** 纪念日列表：全部未删除（含停用），按「越快到越靠前」排序，累计的排后面按天数多的在前。 */
@HiltViewModel
class AnniversaryViewModel @Inject constructor(
    private val anniversaryRepository: AnniversaryRepository
) : ViewModel() {

    val rows: StateFlow<List<AnniversaryRow>> = anniversaryRepository.getAllAnniversaries()
        .map { list ->
            val today = LocalDate.now()
            list.map { it.toRow(today) }
                .sortedWith(
                    compareBy(
                        // 未到的（倒数/循环）在前，按剩余天数升序；
                        { it.days == null },
                        { (it.days ?: 0L) < 0 },
                        { it.days ?: 0L },
                        // 已过的累计：在一起越久越靠前
                        { -(it.days ?: 0L) }
                    )
                )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 停用 / 恢复：停用后不再提醒，列表里整行变淡。 */
    fun setEnabled(anniversary: Anniversary, enabled: Boolean) {
        viewModelScope.launch {
            anniversaryRepository.setEnabled(anniversary, enabled)
        }
    }
}

/** 单条纪念日 → 展示行；文案规则与 AnniversaryClock 的口径一一对应。 */
fun Anniversary.toRow(today: LocalDate = LocalDate.now()): AnniversaryRow {
    val days = AnniversaryClock.daysUntil(this, today)
    val lunar = AnniversaryClock.lunarLabel(this)
    val anchor = AnniversaryClock.parseDate(date)
    val unit = Anniversary.normalizeRepeatUnit(repeatUnit)

    val dateLabel = when {
        type == Anniversary.TYPE_BIRTHDAY && lunar != null -> lunar
        type == Anniversary.TYPE_BIRTHDAY && anchor != null ->
            "${anchor.monthValue}月${anchor.dayOfMonth}日"
        anchor != null -> "${anchor.year}年${anchor.monthValue}月${anchor.dayOfMonth}日"
        else -> date
    }

    val statusText = when {
        days == null -> "日期还没填好"
        type == Anniversary.TYPE_BIRTHDAY -> {
            val next = AnniversaryClock.nextOccurrence(this, today)
            val nextText = next?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: ""
            // 满几岁按「下一次发生 - 出生」口径：公历整年差，农历按农历年差
            val age = if (next != null && anchor != null) {
                AnniversaryClock.ageTurnedAt(anchor, next, isLunar)
            } else {
                null
            }
            val ageSuffix = age?.takeIf { it > 0 }?.let { " · 满 $it 岁" } ?: ""
            when {
                days == 0L -> "就是今天！$ageSuffix"
                nextText.isBlank() -> "每年循环"
                else -> "下次 $nextText · 还有 $days 天$ageSuffix"
            }
        }
        type == Anniversary.TYPE_COUNTUP -> when {
            days > 0 -> "还有 $days 天开始计"
            else -> "第 ${-days + 1} 天"
        }
        unit != Anniversary.REPEAT_NONE -> {
            val next = AnniversaryClock.nextOccurrence(this, today)
            val nextText = next?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: ""
            val cycle = Anniversary.repeatLabel(unit, repeatInterval)
            when {
                days == 0L -> "就是今天！"
                nextText.isBlank() -> cycle
                else -> "$cycle · 下次 $nextText · 还有 $days 天"
            }
        }
        days >= 0 -> when {
            days == 0L -> "就是今天！"
            else -> "还有 $days 天"
        }
        else -> "已过 ${-days} 天"
    }

    return AnniversaryRow(
        anniversary = this,
        dateLabel = dateLabel,
        statusText = statusText,
        days = days
    )
}

/**
 * 新增 / 编辑纪念日：编辑模式下从导航参数读 id 载入；
 * 农历录入时把农历月/日换算成最近的公历锚点存进 [Anniversary.date]。
 */
@HiltViewModel
class AnniversaryEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val anniversaryRepository: AnniversaryRepository
) : ViewModel() {

    /** -1 表示新增；编辑模式为既有 id。 */
    val anniversaryId: Long = savedStateHandle.get<Long>("anniversaryId") ?: -1L
    val isEditMode: Boolean get() = anniversaryId > 0

    /** 编辑模式载入的原始数据；新增时始终为 null。 */
    val loaded: StateFlow<Anniversary?> = if (isEditMode) {
        anniversaryRepository.getByIdFlow(anniversaryId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    } else {
        flowOf(null)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    fun save(anniversary: Anniversary, onDone: () -> Unit) {
        viewModelScope.launch {
            if (isEditMode) {
                val original = anniversaryRepository.getById(anniversaryId) ?: return@launch
                anniversaryRepository.update(
                    anniversary.copy(
                        id = original.id,
                        syncId = original.syncId,
                        createdAt = original.createdAt,
                        // 保留原字段模式：编辑不抹掉本地通知去重与软删标记
                        lastNotifiedDate = original.lastNotifiedDate,
                        deletedAt = original.deletedAt
                    )
                )
            } else {
                anniversaryRepository.create(anniversary)
            }
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        if (!isEditMode) return
        viewModelScope.launch {
            anniversaryRepository.getById(anniversaryId)?.let {
                anniversaryRepository.softDelete(it)
            }
            onDone()
        }
    }
}
