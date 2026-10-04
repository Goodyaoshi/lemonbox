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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import android.widget.Toast
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.ReminderRepeatType
import com.goodyaoshi.lemonbox.data.local.entity.ReminderSource
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorSelectionChip
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TagBlue
import com.goodyaoshi.lemonbox.ui.theme.TagBlueText
import com.goodyaoshi.lemonbox.ui.theme.TagGreen
import com.goodyaoshi.lemonbox.ui.theme.TagGreenText
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
    var showAddDialog by remember { mutableStateOf(false) }

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
                    text = "家务提醒",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
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

            if (reminders.isEmpty()) {
                EmptyState(
                    title = "还没有提醒",
                    message = "点右上角 + 新建，或在首页菜谱的某天点「提醒准备」自动生成。",
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
                                onDelete = { viewModel.delete(reminder) },
                                modifier = Modifier.padding(vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        val context = LocalContext.current
        AddReminderDialog(
            onDismissRequest = { showAddDialog = false },
            onConfirm = { reminder ->
                viewModel.create(
                    title = reminder.title,
                    note = reminder.note,
                    repeatType = reminder.repeat,
                    targetDate = reminder.targetDate,
                    intervalDays = reminder.intervalDays,
                    weekdays = Reminder.decodeWeekdays(reminder.weekdays),
                    fireTime = reminder.fireTime
                ) { saved ->
                    if (saved) {
                        showAddDialog = false
                    } else {
                        // 保存失败只可能是提醒时刻已经过了。
                        Toast.makeText(context, "这个时间已经过了，换个时间吧", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
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
                }
                Text(
                    text = if (reminder.enabled) {
                        "${ReminderClock.fireAtText(reminder.nextFireAt)} · " +
                            ReminderClock.describeRepeat(reminder)
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
                Switch(
                    checked = true,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.size(40.dp)
                )
            } else {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除",
                        tint = TextHint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** 新建提醒：标题、重复方式（一次/每天/每隔 N 天/每周几）、提醒时间。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddReminderDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (Reminder) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var repeatType by remember { mutableStateOf(ReminderRepeatType.ONCE) }
    var dayOffset by remember { mutableIntStateOf(1) }
    var intervalDays by remember { mutableIntStateOf(2) }
    var weekdays by remember { mutableStateOf(setOf(1)) }
    var fireTime by remember { mutableStateOf("19:00") }

    val targetDate = remember(dayOffset) { LocalDate.now().plusDays(dayOffset.toLong()).toString() }
    val targetLabel = remember(dayOffset) {
        val date = LocalDate.now().plusDays(dayOffset.toLong())
        when (dayOffset) {
            0 -> "今天"
            1 -> "明天"
            else -> "${date.monthValue}/${date.dayOfMonth}"
        }
    }

    AppDialog(
        title = "新提醒",
        subtitle = "到点发通知提醒你做事，比如「今晚解冻肉」「每周三清理洗碗机」。",
        onDismissRequest = onDismissRequest,
        confirmText = "保存",
        confirmEnabled = title.isNotBlank(),
        onConfirm = {
            onConfirm(
                Reminder(
                    title = title.trim(),
                    repeatType = repeatType.name,
                    intervalDays = intervalDays,
                    weekdays = Reminder.encodeWeekdays(weekdays.toList()),
                    fireTime = fireTime,
                    targetDate = targetDate.takeIf { repeatType == ReminderRepeatType.ONCE }
                )
            )
        }
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("要做的事") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeatOptions.forEach { (type, label) ->
                EditorSelectionChip(
                    text = label,
                    selected = repeatType == type,
                    onClick = { repeatType = type }
                )
            }
        }

        when (repeatType) {
            ReminderRepeatType.ONCE -> {
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

            ReminderRepeatType.INTERVAL -> {
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

            ReminderRepeatType.WEEKLY -> {
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

            ReminderRepeatType.DAILY -> Unit
        }

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
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceWarmDeep)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = OrangeStart)
    }
}
