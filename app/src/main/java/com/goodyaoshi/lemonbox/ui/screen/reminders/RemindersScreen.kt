package com.goodyaoshi.lemonbox.ui.screen.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.ReminderRepeatType
import com.goodyaoshi.lemonbox.data.local.entity.ReminderSource
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.EditorSelectionChip
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.InlineNotice
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SkeletonList
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TagBlue
import com.goodyaoshi.lemonbox.ui.theme.TagBlueText
import com.goodyaoshi.lemonbox.ui.theme.TagGreen
import com.goodyaoshi.lemonbox.ui.theme.TagGreenText
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.RemindersViewModel
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.ReminderClock
import java.time.LocalDate

/** 提醒列表的分组标题。 */
private data class ReminderGroup(
    val label: String,
    val reminders: List<Reminder>
)

@Composable
fun RemindersScreen(
    onBack: () -> Unit,
    viewModel: RemindersViewModel = hiltViewModel()
) {
    val reminders by viewModel.reminders.collectAsState()
    val activeItems by viewModel.activeItems.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    // 删除前先确认（R1）：破坏性操作不再裸奔，确认后仍可在 Snackbar 里撤销。
    var pendingDelete by remember { mutableStateOf<Reminder?>(null) }
    val appSnackbar = LocalAppSnackbar.current

    val today = LocalDate.now()
    val active = reminders.filter { it.enabled }
    val archived = reminders.filterNot { it.enabled }
    val groups = buildList {
        add(
            ReminderGroup(
                "今天",
                active.filter { ReminderClock.fireAtText(it.nextFireAt, today).startsWith("今天") }
            )
        )
        add(
            ReminderGroup(
                "明天",
                active.filter { ReminderClock.fireAtText(it.nextFireAt, today).startsWith("明天") }
            )
        )
        add(
            ReminderGroup(
                "以后",
                active.filterNot {
                    ReminderClock.fireAtText(it.nextFireAt, today).startsWith("今天") ||
                        ReminderClock.fireAtText(it.nextFireAt, today).startsWith("明天")
                }
            )
        )
        if (archived.isNotEmpty()) {
            add(ReminderGroup("已完成 / 已暂停", archived))
        }
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
                    text = "待办提醒",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "新建提醒",
                        tint = OrangeStart
                    )
                }
            }

            if (isLoading) {
                // 首帧：提醒还没读到，先画骨架，别让「还没加载」看起来像「一条提醒都没有」。
                SkeletonList(itemCount = 3, showHeader = false)
            } else if (reminders.isEmpty()) {
                EmptyState(
                    title = "还没有提醒",
                    // 去掉「点右上角 +」这种屏外指路（I5），空态直接给「新建提醒」按钮。
                    message = "新建一条提醒，或在首页菜谱的某天点「提醒准备」自动生成。",
                    actionLabel = "新建提醒",
                    onAction = { showAddDialog = true },
                    modifier = Modifier.padding(top = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp
                    )
                ) {
                    groups.forEach { group ->
                        if (group.reminders.isEmpty()) return@forEach
                        item(key = "header_${group.label}") {
                            Text(
                                text = group.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextHint,
                                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                            )
                        }
                        items(group.reminders, key = { it.id }) { reminder ->
                            ReminderRow(
                                reminder = reminder,
                                onToggle = { viewModel.toggleEnabled(reminder) },
                                onComplete = { viewModel.complete(reminder) },
                                onDelete = { pendingDelete = reminder },
                                modifier = Modifier.padding(vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        // 保存失败时的就地提示（I10）：留在对话框内显示，不再用一闪而过的 Toast。
        var addError by remember { mutableStateOf<String?>(null) }
        AddReminderDialog(
            activeItems = activeItems,
            errorMessage = addError,
            onDismissRequest = {
                addError = null
                showAddDialog = false
            },
            onConfirm = { reminder ->
                addError = null
                viewModel.create(
                    title = reminder.title,
                    note = reminder.note,
                    repeatType = reminder.repeat,
                    targetDate = reminder.targetDate,
                    intervalDays = reminder.intervalDays,
                    weekdays = Reminder.decodeWeekdays(reminder.weekdays),
                    fireTime = reminder.fireTime,
                    doseTimes = Reminder.decodeDoseTimes(reminder.doseTimes).map { it.toString() },
                    courseDays = reminder.courseDays,
                    linkedItemId = reminder.linkedItemId
                ) { saved ->
                    if (saved) {
                        showAddDialog = false
                    } else {
                        // 保存失败只可能是提醒时刻已经过了：在对话框里就地说明。
                        addError = "这个时间已经过了，换个时间吧"
                    }
                }
            }
        )
    }

    pendingDelete?.let { target ->
        // 删除确认（R1）：软删入回收站语义，误删可撤销，不再是划掉就没了。
        AppDialog(
            title = "删除提醒",
            onDismissRequest = { pendingDelete = null },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                viewModel.delete(target)
                appSnackbar?.showUndo("已删除「${target.title}」", DeleteCopy.UNDO_ACTION) {
                    viewModel.restore(target)
                }
                pendingDelete = null
            }
        ) {
            Text(
                text = if (target.enabled) {
                    "删除后这条提醒就不会再响了，删错了可以撤销。"
                } else {
                    "确定删除这条已完成的提醒吗？删除后仍可撤销。"
                },
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

/** 一条提醒：标题 + 触发时间/重复方式，进行中的可完成、可暂停；归档的只留删除。 */
@Composable
private fun ReminderRow(
    reminder: Reminder,
    onToggle: () -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppSurfaceCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        shadowElevation = 8.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(OrangeStart.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Alarm,
                    contentDescription = null,
                    tint = OrangeStart,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = reminder.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (reminder.enabled) TextPrimary else TextHint,
                        textDecoration = if (reminder.completedAt != null) {
                            TextDecoration.LineThrough
                        } else {
                            null
                        },
                        maxLines = 2,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (reminder.sourceKind == ReminderSource.MEAL_PREP) {
                        Spacer(modifier = Modifier.width(6.dp))
                        PillTag(
                            text = "菜谱准备",
                            backgroundColor = TagBlue,
                            contentColor = TagBlueText
                        )
                    }
                    if (reminder.isMedication) {
                        Spacer(modifier = Modifier.width(6.dp))
                        PillTag(
                            text = "用药",
                            backgroundColor = TagGreen,
                            contentColor = TagGreenText
                        )
                    }
                    if (reminder.awaitingConfirmation) {
                        Spacer(modifier = Modifier.width(6.dp))
                        PillTag(
                            text = "待完成",
                            backgroundColor = TagOrange,
                            contentColor = TagOrangeText
                        )
                    }
                }
                Text(
                    text = if (reminder.enabled) {
                        if (reminder.awaitingConfirmation) {
                            "已提醒，做完记得点「完成」 · " + ReminderClock.describeRepeat(reminder)
                        } else {
                            "${ReminderClock.fireAtText(reminder.nextFireAt)} · " +
                                ReminderClock.describeRepeat(reminder)
                        }
                    } else {
                        reminder.completedAt?.let { "已完成 ${DateUtil.formatDateTime(it)}" } ?: "已暂停"
                    },
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (reminder.enabled && reminder.note.isNotBlank()) {
                    Text(
                        text = reminder.note,
                        fontSize = 12.sp,
                        color = TextHint,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (reminder.notifiedAt > 0) {
                    // 触发记录（R1）：上次真的响过是什么时候，漏提醒时不用靠猜。
                    Text(
                        text = "上次触发 ${DateUtil.formatDateTime(reminder.notifiedAt)}",
                        fontSize = 12.sp,
                        color = TextHint,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (reminder.enabled) {
                Spacer(modifier = Modifier.width(8.dp))
                PillTag(
                    text = "完成",
                    backgroundColor = TagGreen,
                    contentColor = TagGreenText,
                    onClick = onComplete
                )
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除",
                        tint = TextHint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            // 开关始终可见并绑定真实启用状态（I4）：此前写死 checked = true，
            // 用户既无法从界面判断提醒是否启用，点击时开关也不会翻转（"点了没用"的错觉）。
            // 不写死尺寸，交给 M3 默认的 48dp 最小交互热区，同时读屏能播报开关对应的提醒名。
            Switch(
                checked = reminder.enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.semantics {
                    contentDescription = "启用提醒：${reminder.title}"
                }
            )
        }
    }
}

/** 新建提醒：标题、重复方式（一次/每天/每隔 N 天/每周几）、提醒时间；「按疗程用药」（F17）覆盖为每日多次服药。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AddReminderDialog(
    activeItems: List<ItemDetail>,
    onDismissRequest: () -> Unit,
    onConfirm: (Reminder) -> Unit,
    errorMessage: String? = null
) {
    var title by remember { mutableStateOf("") }
    var repeatType by remember { mutableStateOf(ReminderRepeatType.ONCE) }
    var dayOffset by remember { mutableIntStateOf(1) }
    var intervalDays by remember { mutableIntStateOf(2) }
    var weekdays by remember { mutableStateOf(setOf(1)) }
    var fireTime by remember { mutableStateOf("19:00") }
    var showTimePicker by remember { mutableStateOf(false) }
    var medication by remember { mutableStateOf(false) }
    var doseTimes by remember { mutableStateOf(setOf("08:00", "20:00")) }
    var courseDays by remember { mutableIntStateOf(7) }
    var linkedItemId by remember { mutableStateOf<Long?>(null) }
    var showDoseTimePicker by remember { mutableStateOf(false) }
    var showItemPicker by remember { mutableStateOf(false) }

    val targetDate = remember(dayOffset) { LocalDate.now().plusDays(dayOffset.toLong()).toString() }
    val targetLabel = remember(dayOffset) {
        val date = LocalDate.now().plusDays(dayOffset.toLong())
        when (dayOffset) {
            0 -> "今天"
            1 -> "明天"
            else -> "${date.monthValue}/${date.dayOfMonth}"
        }
    }
    val linkedItemName = activeItems.firstOrNull { it.item.id == linkedItemId }?.item?.name

    // 自定义时间选择器：固定档位之外可以精确到任意分钟（如「14:35 提醒我拿快递」）。
    if (showTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = fireTime.substringBefore(":").toIntOrNull() ?: 19,
            initialMinute = fireTime.substringAfter(":").toIntOrNull() ?: 0,
            is24Hour = true
        )
        AppDialog(
            title = "自定义提醒时间",
            onDismissRequest = { showTimePicker = false },
            confirmText = "确定",
            onConfirm = {
                fireTime = "%02d:%02d".format(pickerState.hour, pickerState.minute)
                showTimePicker = false
            }
        ) {
            TimePicker(state = pickerState)
        }
    }

    // 用药时刻选择器（F17）：选中的时刻加入每日服药计划。
    if (showDoseTimePicker) {
        val pickerState = rememberTimePickerState(initialHour = 12, initialMinute = 0, is24Hour = true)
        AppDialog(
            title = "加一个服药时刻",
            onDismissRequest = { showDoseTimePicker = false },
            confirmText = "添加",
            onConfirm = {
                doseTimes = doseTimes + "%02d:%02d".format(pickerState.hour, pickerState.minute)
                showDoseTimePicker = false
            }
        ) {
            TimePicker(state = pickerState)
        }
    }

    // 关联库存选择器（F17）：从在库家当里挑一盒药，提醒里播报剩余量。
    if (showItemPicker) {
        AppDialog(
            title = "关联药品库存",
            subtitle = "到点提醒会带上剩余量，不够吃完疗程时提示补货。",
            onDismissRequest = { showItemPicker = false },
            dismissText = "关闭"
        ) {
            PickerRow(text = "不关联", selected = linkedItemId == null) {
                linkedItemId = null
                showItemPicker = false
            }
            if (activeItems.isEmpty()) {
                Text(
                    text = "还没有在库家当，先去「家当」里入库这盒药吧。",
                    fontSize = 12.sp,
                    color = TextHint,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    activeItems.forEach { detail ->
                        PickerRow(
                            text = "${detail.item.name}（剩 ${detail.item.quantity}${detail.item.unit}）",
                            selected = detail.item.id == linkedItemId
                        ) {
                            linkedItemId = detail.item.id
                            showItemPicker = false
                        }
                    }
                }
            }
        }
    }

    AppDialog(
        title = "新提醒",
        subtitle = "到点发通知提醒你做事，比如「今晚解冻肉」「每周三清理洗碗机」。",
        onDismissRequest = onDismissRequest,
        confirmText = "保存",
        confirmEnabled = title.isNotBlank() && (!medication || doseTimes.isNotEmpty()),
        onConfirm = {
            onConfirm(
                Reminder(
                    title = title.trim(),
                    repeatType = repeatType.name,
                    intervalDays = intervalDays,
                    weekdays = Reminder.encodeWeekdays(weekdays.toList()),
                    fireTime = fireTime,
                    targetDate = targetDate.takeIf { repeatType == ReminderRepeatType.ONCE },
                    doseTimes = if (medication) Reminder.encodeDoseTimes(doseTimes.toList()) else "",
                    courseDays = if (medication) courseDays else 0,
                    linkedItemId = if (medication) linkedItemId else null
                )
            )
        }
    ) {
        // 保存失败的就地提示（I10）：出现在对话框内、紧邻输入项，用户改完时间即可重试。
        if (errorMessage != null) {
            InlineNotice(message = errorMessage)
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(if (medication) "药名 / 要做的事" else "要做的事") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!medication) {
                repeatOptions.forEach { (type, label) ->
                    EditorSelectionChip(
                        text = label,
                        selected = repeatType == type,
                        onClick = { repeatType = type }
                    )
                }
            }
            // 按疗程用药（F17）：每日多次 + 疗程天数 + 库存联动，重复方式固定为每天。
            EditorSelectionChip(
                text = "按疗程用药",
                selected = medication,
                onClick = {
                    medication = !medication
                    if (medication) repeatType = ReminderRepeatType.DAILY
                }
            )
        }

        when {
            medication -> {
                Text(
                    text = "每天在选中时刻各提醒一次，到点带疗程进度和库存余量。",
                    fontSize = 12.sp,
                    color = TextHint
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val doseOptions = AppPreferences.REMINDER_TIME_OPTIONS +
                        doseTimes.filter { it !in AppPreferences.REMINDER_TIME_OPTIONS }
                    doseOptions.distinct().forEach { time ->
                        EditorSelectionChip(
                            text = time,
                            selected = time in doseTimes,
                            onClick = {
                                doseTimes = if (time in doseTimes) doseTimes - time else doseTimes + time
                            }
                        )
                    }
                    EditorSelectionChip(
                        text = "加时刻…",
                        selected = false,
                        onClick = { showDoseTimePicker = true }
                    )
                }
                StepRow(label = "疗程天数") {
                    StepButton("−") { courseDays = (courseDays - 1).coerceAtLeast(0) }
                    Text(
                        text = if (courseDays == 0) "不限" else "$courseDays 天",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    StepButton("+") { courseDays = (courseDays + 1).coerceAtMost(90) }
                }
                StepRow(label = "关联库存") {
                    EditorSelectionChip(
                        text = linkedItemName?.let { "已关联：$it" } ?: "选一盒药…",
                        selected = linkedItemId != null,
                        onClick = { showItemPicker = true }
                    )
                }
            }

            repeatType == ReminderRepeatType.ONCE -> {
                StepRow(label = "提醒日期") {
                    StepButton("−") { dayOffset = (dayOffset - 1).coerceAtLeast(0) }
                    Text(
                        text = targetLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    StepButton("+") { dayOffset = (dayOffset + 1).coerceAtMost(30) }
                }
            }

            repeatType == ReminderRepeatType.INTERVAL -> {
                StepRow(label = "每隔几天") {
                    StepButton("−") { intervalDays = (intervalDays - 1).coerceAtLeast(1) }
                    Text(
                        text = "$intervalDays",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    StepButton("+") { intervalDays = (intervalDays + 1).coerceAtMost(30) }
                }
            }

            repeatType == ReminderRepeatType.WEEKLY -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReminderClock.WEEKDAY_LABELS.forEachIndexed { index, label ->
                        val day = index + 1
                        EditorSelectionChip(
                            text = label,
                            selected = day in weekdays,
                            onClick = {
                                weekdays = if (day in weekdays) {
                                    weekdays - day
                                } else {
                                    weekdays + day
                                }
                            }
                        )
                    }
                }
            }
        }

        if (!medication) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppPreferences.REMINDER_TIME_OPTIONS.forEach { time ->
                    EditorSelectionChip(
                        text = time,
                        selected = fireTime == time,
                        onClick = { fireTime = time }
                    )
                }
                // 档位之外的时间走自定义；选中态显示当前自定义的时间。
                EditorSelectionChip(
                    text = if (fireTime in AppPreferences.REMINDER_TIME_OPTIONS) "自定义…" else fireTime,
                    selected = fireTime !in AppPreferences.REMINDER_TIME_OPTIONS,
                    onClick = { showTimePicker = true }
                )
            }
        }
    }
}

/** 弹窗里的单行选择项（F17 关联库存选择器用）。 */
@Composable
private fun PickerRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) OrangeStart else TextPrimary,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已选",
                tint = OrangeStart,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private val repeatOptions = listOf(
    ReminderRepeatType.ONCE to "一次",
    ReminderRepeatType.DAILY to "每天",
    ReminderRepeatType.INTERVAL to "每隔几天",
    ReminderRepeatType.WEEKLY to "每周几"
)

@Composable
private fun StepRow(
    label: String,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 13.sp, color = TextSecondary)
        Spacer(modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Box(
        // 热区对齐 48dp（视觉块仍 34dp），便于点按。
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceWarmDeep),
            contentAlignment = Alignment.Center
        ) {
            Text(text = text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = OrangeStart)
        }
    }
}
