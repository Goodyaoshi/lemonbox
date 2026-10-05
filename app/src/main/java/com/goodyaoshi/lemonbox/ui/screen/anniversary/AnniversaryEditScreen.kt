package com.goodyaoshi.lemonbox.ui.screen.anniversary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.ExpiryPickerDialog
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.components.LunarDatePickerDialog
import com.goodyaoshi.lemonbox.ui.components.SegmentedTabs
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.AnniversaryEditViewModel
import com.goodyaoshi.lemonbox.util.AnniversaryClock
import com.tyme.lunar.LunarDay
import com.tyme.lunar.LunarMonth
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 提前提醒的可选天数：0=当天，其余为提前 N 天。 */
private val REMIND_DAY_OPTIONS = listOf(0, 1, 3, 7, 14, 30)

/** 纪念日类型的可选项与说明。 */
private val TYPE_CHIP_OPTIONS = listOf(
    Anniversary.TYPE_COUNTDOWN to "倒数日",
    Anniversary.TYPE_COUNTUP to "正数日",
    Anniversary.TYPE_BIRTHDAY to "生日"
)

private fun typeHint(type: Int): String = when (type) {
    Anniversary.TYPE_COUNTUP -> "从那天起，看已经走过多久"
    Anniversary.TYPE_BIRTHDAY -> "每年都过一天，公历农历都可以"
    else -> "盯着一个未来的日子，一天天倒数"
}

/** 重复周期的可选档位（「不重复」默认选中，单独列在第一位）。 */
private val REPEAT_CHIP_OPTIONS = listOf(
    Anniversary.REPEAT_DAY to "每天",
    Anniversary.REPEAT_WEEK to "每周",
    Anniversary.REPEAT_MONTH to "每月",
    Anniversary.REPEAT_YEAR to "每年"
)

/** 重复间隔的上限：每 99 天/周/月/年封顶，防误触出天文数字。 */
private const val MAX_REPEAT_INTERVAL = 99

/** 农历生日的展示名：「2000年农历八月十五」。 */
private fun lunarDateLabel(year: Int, month: Int, day: Int): String =
    "${year}年农历${LunarMonth.fromYm(2024, month).getName()}${LunarDay.NAMES.getOrNull(day - 1) ?: day}"

/**
 * 新增 / 编辑纪念日：先选类型（倒数日/正数日/生日），日期复用应用统一的选择器
 * （公历走 ExpiryPickerDialog，农历走农历年月日分步的 LunarDatePickerDialog），
 * 生日展示生肖星座年龄，实时预览换算结果，存库统一落公历锚点（农历=出生当天换算值）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnniversaryEditScreen(
    onBack: () -> Unit,
    viewModel: AnniversaryEditViewModel = hiltViewModel()
) {
    val loaded by viewModel.loaded.collectAsState()
    val today = remember { LocalDate.now() }

    var initialized by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var type by remember { mutableIntStateOf(Anniversary.TYPE_COUNTDOWN) }
    var isLunar by remember { mutableStateOf(false) }
    var solarDate by remember { mutableStateOf(today) }
    var lunarYear by remember { mutableIntStateOf(today.year - 25) }
    var lunarMonth by remember { mutableIntStateOf(1) }
    var lunarDay by remember { mutableIntStateOf(1) }
    var repeatUnit by remember { mutableStateOf(Anniversary.REPEAT_NONE) }
    var repeatInterval by remember { mutableIntStateOf(1) }
    var remindDays by remember { mutableStateOf(emptySet<Int>()) }
    var enabled by remember { mutableStateOf(true) }
    var showSolarPicker by remember { mutableStateOf(false) }
    var showLunarPicker by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(loaded) {
        val a = loaded ?: return@LaunchedEffect
        if (initialized) return@LaunchedEffect
        initialized = true
        name = a.name
        note = a.note
        type = a.type
        isLunar = a.isLunar
        AnniversaryClock.parseDate(a.date)?.let {
            solarDate = it
            // 农历生日从公历锚点反推录入用的农历年月日
            if (a.isLunar) {
                AnniversaryClock.solarToLunar(it)?.let { (y, m, d) ->
                    lunarYear = y
                    lunarMonth = m
                    lunarDay = d
                }
            }
        }
        repeatUnit = Anniversary.normalizeRepeatUnit(a.repeatUnit)
        repeatInterval = a.repeatInterval.coerceAtLeast(1)
        remindDays = Anniversary.decodeReminderDays(a.remindDays).toSet()
        enabled = a.enabled
    }

    // 各类型的有效周期：生日固定每年，正数日固定不重复，倒数日听用户的（默认不重复）。
    val effectiveUnit = when (type) {
        Anniversary.TYPE_BIRTHDAY -> Anniversary.REPEAT_YEAR
        Anniversary.TYPE_COUNTUP -> Anniversary.REPEAT_NONE
        else -> repeatUnit
    }
    val effectiveInterval = if (effectiveUnit == Anniversary.REPEAT_NONE) 1 else repeatInterval

    // 预览：与存库同一套换算，用户改任何字段都能立刻看到结果。
    val preview = Anniversary(
        name = "",
        type = type,
        date = solarDate.toString(),
        isLunar = isLunar,
        lunarMonth = lunarMonth,
        lunarDay = lunarDay,
        repeatUnit = effectiveUnit,
        repeatInterval = effectiveInterval
    )
    val previewNext = AnniversaryClock.nextOccurrence(preview)
    val previewDays = AnniversaryClock.daysUntil(preview)
    val isBirthday = type == Anniversary.TYPE_BIRTHDAY
    val previewLabel = if (isBirthday && isLunar) {
        lunarDateLabel(lunarYear, lunarMonth, lunarDay)
    } else {
        "${solarDate.year}年${solarDate.monthValue}月${solarDate.dayOfMonth}日"
    }
    // 农历生日的锚点也是出生当天的公历日期，年龄/生肖/星座同样算得准；年龄按发生年-出生年口径
    val previewAge = if (isBirthday) {
        previewNext?.let { AnniversaryClock.ageTurnedAt(solarDate, it, isLunar) }
    } else {
        null
    }
    val previewExtra = if (isBirthday) {
        val parts = buildList {
            AnniversaryClock.zodiacLabel(solarDate)?.let { add("属$it") }
            AnniversaryClock.constellationLabel(solarDate)?.let { add(it) }
        }
        if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")
    } else {
        ""
    }

    // 换类型时的联动：周期归位、日期方向归位。
    val pickType: (Int) -> Unit = { newType ->
        if (type != newType) {
            type = newType
            when (newType) {
                Anniversary.TYPE_BIRTHDAY -> {
                    repeatUnit = Anniversary.REPEAT_YEAR
                    repeatInterval = 1
                    if (solarDate > today) solarDate = today.minusYears(25)
                }
                Anniversary.TYPE_COUNTUP -> {
                    repeatUnit = Anniversary.REPEAT_NONE
                    repeatInterval = 1
                    if (solarDate > today) solarDate = today
                }
                else -> {
                    repeatUnit = Anniversary.REPEAT_NONE
                    repeatInterval = 1
                }
            }
        }
    }
    // 换档时重置间隔，保持「每天/每周/…」点上去就是字面意思。
    val pickRepeatUnit: (String) -> Unit = { unit ->
        if (repeatUnit != unit) {
            repeatUnit = unit
            repeatInterval = 1
        }
    }
    val repeatUnitWord = when (repeatUnit) {
        Anniversary.REPEAT_DAY -> "天"
        Anniversary.REPEAT_WEEK -> "周"
        Anniversary.REPEAT_MONTH -> "个月"
        else -> "年"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = TextPrimary
                    )
                }
                Text(
                    text = if (viewModel.isEditMode) "编辑纪念日" else "新增纪念日",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppSurfaceCard(
                    shape = RoundedCornerShape(22.dp),
                    contentPadding = PaddingValues(16.dp),
                    shadowElevation = 12.dp
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("名称（如：在一起 / 她的生日 / 考试）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("备注（可选）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                AppSurfaceCard(
                    shape = RoundedCornerShape(22.dp),
                    contentPadding = PaddingValues(16.dp),
                    shadowElevation = 12.dp
                ) {
                    Text(
                        text = "类型",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = typeHint(type),
                        fontSize = 11.sp,
                        color = TextHint
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TYPE_CHIP_OPTIONS.forEach { (value, label) ->
                            SelectionChip(
                                text = label,
                                selected = type == value,
                                onClick = { pickType(value) }
                            )
                        }
                    }
                }

                AppSurfaceCard(
                    shape = RoundedCornerShape(22.dp),
                    contentPadding = PaddingValues(16.dp),
                    shadowElevation = 12.dp
                ) {
                    if (type == Anniversary.TYPE_BIRTHDAY) {
                        SegmentedTabs(
                            labels = listOf("公历", "农历"),
                            selectedIndex = if (isLunar) 1 else 0,
                            onSelect = { checkedLunar ->
                                isLunar = checkedLunar == 1
                                // 切到农历时用当前公历日期反推农历年月日，作为录入起点
                                if (isLunar) {
                                    AnniversaryClock.solarToLunar(solarDate)?.let { (y, m, d) ->
                                        lunarYear = y
                                        lunarMonth = m
                                        lunarDay = d
                                    }
                                }
                            }
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                    }

                    if (type == Anniversary.TYPE_BIRTHDAY && isLunar) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(OrangeTint)
                                .clickable { showLunarPicker = true }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = null,
                                tint = OrangeStart,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = "出生日期：${lunarDateLabel(lunarYear, lunarMonth, lunarDay)}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(OrangeTint)
                                .clickable { showSolarPicker = true }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = null,
                                tint = OrangeStart,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = when (type) {
                                    Anniversary.TYPE_BIRTHDAY -> "出生日期：${solarDate.year}年${solarDate.monthValue}月${solarDate.dayOfMonth}日"
                                    Anniversary.TYPE_COUNTUP -> "从哪天开始：${solarDate.year}年${solarDate.monthValue}月${solarDate.dayOfMonth}日"
                                    else -> "目标日期：${solarDate.year}年${solarDate.monthValue}月${solarDate.dayOfMonth}日"
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        text = previewText(
                            type = type,
                            label = previewLabel,
                            repeatUnit = effectiveUnit,
                            repeatInterval = effectiveInterval,
                            next = previewNext,
                            days = previewDays,
                            ageAtNext = previewAge,
                            birthdayExtra = previewExtra
                        ),
                        fontSize = 12.sp,
                        color = if (previewDays == null) StatusExpired else TextSecondary
                    )
                }

                if (type == Anniversary.TYPE_COUNTDOWN) {
                    AppSurfaceCard(
                        shape = RoundedCornerShape(22.dp),
                        contentPadding = PaddingValues(16.dp),
                        shadowElevation = 12.dp
                    ) {
                        Text(
                            text = "重复",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(
                            text = "不重复数完就算；吃药复查这类可选每 N 天再来一遍",
                            fontSize = 11.sp,
                            color = TextHint
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SelectionChip(
                                text = "不重复",
                                selected = repeatUnit == Anniversary.REPEAT_NONE,
                                onClick = { pickRepeatUnit(Anniversary.REPEAT_NONE) }
                            )
                            REPEAT_CHIP_OPTIONS.forEach { (unit, label) ->
                                SelectionChip(
                                    text = label,
                                    selected = repeatUnit == unit,
                                    onClick = { pickRepeatUnit(unit) }
                                )
                            }
                        }
                        if (repeatUnit != Anniversary.REPEAT_NONE) {
                            Spacer(modifier = Modifier.size(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "每",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.size(10.dp))
                                StepCircle(
                                    label = "−",
                                    enabled = repeatInterval > 1,
                                    onClick = {
                                        repeatInterval = (repeatInterval - 1).coerceAtLeast(1)
                                    }
                                )
                                Spacer(modifier = Modifier.size(10.dp))
                                Text(
                                    text = "$repeatInterval",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = OrangeStart,
                                    modifier = Modifier.width(32.dp),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.size(10.dp))
                                StepCircle(
                                    label = "+",
                                    enabled = repeatInterval < MAX_REPEAT_INTERVAL,
                                    onClick = {
                                        repeatInterval =
                                            (repeatInterval + 1).coerceAtMost(MAX_REPEAT_INTERVAL)
                                    }
                                )
                                Spacer(modifier = Modifier.size(10.dp))
                                Text(
                                    text = repeatUnitWord,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }

                if (type != Anniversary.TYPE_COUNTUP) {
                    AppSurfaceCard(
                        shape = RoundedCornerShape(22.dp),
                        contentPadding = PaddingValues(16.dp),
                        shadowElevation = 12.dp
                    ) {
                        Text(
                            text = "提前提醒",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(
                            text = if (type == Anniversary.TYPE_BIRTHDAY) {
                                "按提醒时间点检查，想准备礼物就提前几天勾上"
                            } else {
                                "怕忘的话提前几天就开始念叨"
                            },
                            fontSize = 11.sp,
                            color = TextHint
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SelectionChip(
                                text = "不提醒",
                                selected = remindDays.isEmpty(),
                                onClick = { remindDays = emptySet() }
                            )
                            REMIND_DAY_OPTIONS.forEach { day ->
                                val selected = day in remindDays
                                SelectionChip(
                                    text = if (day == 0) "当天" else "提前${day}天",
                                    selected = selected,
                                    onClick = {
                                        remindDays = if (selected) remindDays - day else remindDays + day
                                    }
                                )
                            }
                        }
                    }
                }

                if (viewModel.isEditMode) {
                    AppSurfaceCard(
                        shape = RoundedCornerShape(22.dp),
                        contentPadding = PaddingValues(16.dp),
                        shadowElevation = 12.dp
                    ) {
                        SwitchRow(
                            title = "启用",
                            subtitle = "关掉后不再提醒，列表里变淡保留",
                            checked = enabled,
                            onCheckedChange = { enabled = it }
                        )
                    }
                    Text(
                        text = "删除这个纪念日",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = StatusExpired,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { pendingDelete = true }
                            .padding(vertical = 12.dp),
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            GradientButton(
                text = "保存",
                enabled = name.isNotBlank(),
                onClick = {
                    val anchorSolar = if (isLunar) {
                        // 农历生日存出生当天换算的公历日期（含出生年），年龄/生肖才有据可算
                        AnniversaryClock.lunarToSolar(lunarYear, lunarMonth, lunarDay) ?: solarDate
                    } else {
                        solarDate
                    }
                    viewModel.save(
                        Anniversary(
                            name = name.trim(),
                            note = note.trim(),
                            type = type,
                            date = anchorSolar.toString(),
                            isLunar = isLunar,
                            lunarMonth = if (isLunar) lunarMonth else 0,
                            lunarDay = if (isLunar) lunarDay else 0,
                            repeatUnit = effectiveUnit,
                            repeatInterval = if (effectiveUnit == Anniversary.REPEAT_NONE) {
                                1
                            } else {
                                effectiveInterval
                            },
                            remindDays = Anniversary.encodeReminderDays(remindDays.toList()),
                            enabled = enabled
                        ),
                        onDone = onBack
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding()
            )
        }

        if (showSolarPicker) {
            ExpiryPickerDialog(
                selectedDateMillis = solarDate
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli(),
                onDismissRequest = { showSolarPicker = false },
                title = when (type) {
                    Anniversary.TYPE_BIRTHDAY -> "选择出生日期"
                    Anniversary.TYPE_COUNTUP -> "选择开始日期"
                    else -> "选择目标日期"
                },
                yearRange = when (type) {
                    Anniversary.TYPE_COUNTDOWN -> (today.year - 10)..(today.year + 50)
                    else -> (today.year - 120)..today.year
                },
                onConfirm = { millis ->
                    showSolarPicker = false
                    if (millis != null) {
                        val picked = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                        // 正数日从过去的日子起算，不允许选到未来
                        solarDate = if (type == Anniversary.TYPE_COUNTUP && picked > today) today else picked
                    }
                }
            )
        }

        if (showLunarPicker) {
            LunarDatePickerDialog(
                initialLunarYear = lunarYear,
                initialLunarMonth = lunarMonth,
                initialLunarDay = lunarDay,
                onDismissRequest = { showLunarPicker = false },
                onConfirm = { y, m, d ->
                    showLunarPicker = false
                    lunarYear = y
                    lunarMonth = m
                    lunarDay = d
                    // 锚点同步为出生当天的公历日期，预览与存库共用
                    AnniversaryClock.lunarToSolar(y, m, d)?.let { solarDate = it }
                }
            )
        }
    }

    if (pendingDelete) {
        AppDialog(
            title = "删除纪念日",
            subtitle = "删除后提醒和首页速览都不会再显示它。",
            onDismissRequest = { pendingDelete = false },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                pendingDelete = false
                viewModel.delete(onDone = onBack)
            }
        ) {
            Text(
                text = "确定要删除「${name}」吗？",
                fontSize = 14.sp,
                color = TextSecondary
            )
        }
    }
}

/** 预览文案：按类型各成一套，算不出日期时提示修正。 */
private fun previewText(
    type: Int,
    label: String,
    repeatUnit: String,
    repeatInterval: Int,
    next: LocalDate?,
    days: Long?,
    ageAtNext: Int?,
    birthdayExtra: String
): String {
    val ageSuffix = ageAtNext?.takeIf { it > 0 }?.let { " · 满 $it 岁" } ?: ""
    return when {
        type == Anniversary.TYPE_COUNTUP -> when {
            days == null -> "日期还没选好，选一下就能看到换算结果"
            days > 0 -> "那天还没到，正数日要选过去的日子"
            else -> "$label · 从那天起，第 ${-days + 1} 天"
        }
        type == Anniversary.TYPE_BIRTHDAY -> when {
            next == null || days == null -> "日期还没选好，选一下就能看到换算结果"
            days == 0L -> "$label$birthdayExtra$ageSuffix · 就是今天！"
            else -> "$label$birthdayExtra$ageSuffix · 每年循环，下次 ${
                "${next.year}年${next.monthValue}月${next.dayOfMonth}日"
            }（还有 $days 天）"
        }
        else -> {
            if (next == null || days == null) return "日期还没选好，选一下就能看到换算结果"
            val nextText = "${next.year}年${next.monthValue}月${next.dayOfMonth}日"
            val cycle = Anniversary.repeatLabel(repeatUnit, repeatInterval)
            when {
                cycle.isNotEmpty() && days == 0L -> "$label · $cycle，就是今天！"
                cycle.isNotEmpty() -> "$label · $cycle，下次 $nextText（还有 $days 天）"
                days == 0L -> "$label · 就是今天！"
                days > 0 -> "$label · 倒数，还有 $days 天"
                else -> "$label · 已过 ${-days} 天"
            }
        }
    }
}

/** 单个可选项胶囊：选中橙底、未选中暖底，供类型/月/日/周期/提醒天数复用；禁用时降透明。 */
@Composable
private fun SelectionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) OrangeStart else SurfaceWarmDeep)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) CardWhite else TextSecondary
        )
    }
}

/** 间隔步进的小圆钮：− / +，到边界时置灰。 */
@Composable
private fun StepCircle(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(SurfaceWarmDeep)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) TextPrimary else TextHint
        )
    }
}

/** 卡内一行开关：标题 + 副文案 + Switch。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextHint,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = OrangeStart,
                checkedThumbColor = CardWhite
            )
        )
    }
}
