package com.goodyaoshi.lemonbox.ui.screen.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.settings.ThemeMode
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.SettingsViewModel
import com.goodyaoshi.lemonbox.util.ReminderReliability

private val themeModeOptions = listOf(
    ThemeMode.SYSTEM to "跟随系统",
    ThemeMode.LIGHT to "浅色",
    ThemeMode.DARK to "深色"
)

/** 单选胶囊：选中态用品牌色铺底 + 白字，未选中态用主题化浅底 + 次级文字，深浅色通用。 */
@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) OrangeStart else SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else TextSecondary
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val reminderLadder by viewModel.reminderLadder.collectAsState()
    val reminderTimes by viewModel.reminderTimes.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val keepAliveEnabled by viewModel.keepAliveEnabled.collectAsState()
    val customReminderTimes by viewModel.customReminderTimes.collectAsState()
    val mealPrepDayShift by viewModel.mealPrepDayShift.collectAsState()
    val mealPrepFireTime by viewModel.mealPrepFireTime.collectAsState()
    val budgetReminderEnabled by viewModel.budgetReminderEnabled.collectAsState()

    var showTimePicker by remember { mutableStateOf(false) }
    var showMealPrepTimePicker by remember { mutableStateOf(false) }

    // 固定档位 + 用户自定义候选，合并排序后统一展示。
    val allTimeOptions =
        (AppPreferences.REMINDER_TIME_OPTIONS + customReminderTimes).distinct().sorted()

    // 电池优化白名单状态：从系统对话框回来（onResume）时刷新。
    val activityContext = LocalContext.current as? ComponentActivity
    var batteryOptimized by remember {
        mutableStateOf(activityContext?.let { ReminderReliability.isIgnoringBatteryOptimizations(it) } ?: true)
    }
    DisposableEffect(activityContext) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                activityContext?.let {
                    batteryOptimized = ReminderReliability.isIgnoringBatteryOptimizations(it)
                }
            }
        }
        activityContext?.lifecycle?.addObserver(observer)
        onDispose { activityContext?.lifecycle?.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
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
                    text = "设置",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                Text(
                    text = "外观",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "深色模式可以跟随系统自动切换，也可以手动固定为浅色或深色。",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.size(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    themeModeOptions.forEach { (mode, label) ->
                        OptionChip(
                            label = label,
                            selected = mode == themeMode,
                            onClick = { viewModel.setThemeMode(mode) }
                        )
                    }
                }
            }

            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                Text(
                    text = "到期提醒阶梯",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "可多选。物品有效期进入所选的天数档位时提醒一次，未单独设置的物品都跟随这份默认阶梯。",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.size(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppPreferences.REMINDER_LADDER_OPTIONS.forEach { days ->
                        val selected = reminderLadder.contains(days)
                        OptionChip(
                            label = "$days 天",
                            selected = selected,
                            onClick = {
                                val updated = if (selected) {
                                    reminderLadder - days
                                } else {
                                    (reminderLadder + days).sorted()
                                }
                                viewModel.setReminderLadder(updated)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = if (reminderLadder.isEmpty()) {
                        "尚未选择档位，临期与到期当天仍会提醒"
                    } else {
                        "当前阶梯：${reminderLadder.joinToString("、") { "$it 天" }}"
                    },
                    fontSize = 12.sp,
                    color = OrangeStart,
                    fontWeight = FontWeight.Medium
                )
            }

            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                Text(
                    text = "提醒时间",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "可多选。每天在这些时间点各检查一次，命中提醒阶梯的物品会收到汇总通知（系统可能略有延迟）。",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.size(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allTimeOptions.forEach { time ->
                        val selected = reminderTimes.contains(time)
                        val custom = customReminderTimes.contains(time)
                        OptionChip(
                            label = time,
                            selected = selected,
                            onClick = {
                                when {
                                    // 取消勾选自定义时间时连候选一起移除，固定档位保留。
                                    selected && custom -> viewModel.removeCustomReminderTime(time)
                                    selected -> viewModel.setReminderTimes(reminderTimes - time)
                                    else -> viewModel.setReminderTimes((reminderTimes + time).sorted())
                                }
                            }
                        )
                    }
                    OptionChip(
                        label = "自定义…",
                        selected = false,
                        onClick = { showTimePicker = true }
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = if (reminderTimes.isEmpty()) {
                        "尚未选择时间，将不会发送到期提醒"
                    } else {
                        "每天 ${reminderTimes.joinToString("、")} 各提醒一次"
                    },
                    fontSize = 12.sp,
                    color = OrangeStart,
                    fontWeight = FontWeight.Medium
                )
            }

            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                Text(
                    text = "备菜提醒默认",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "点周菜谱的「提醒准备」时，会按这里的时间预填好，确认一下就能生成提醒。",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.size(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OptionChip(
                        label = "前一天",
                        selected = mealPrepDayShift == -1,
                        onClick = { viewModel.setMealPrepDayShift(-1) }
                    )
                    OptionChip(
                        label = "当天",
                        selected = mealPrepDayShift == 0,
                        onClick = { viewModel.setMealPrepDayShift(0) }
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 候选含当前默认值，自定义后立即显示为选中项。
                    (allTimeOptions + mealPrepFireTime).distinct().sorted().forEach { time ->
                        OptionChip(
                            label = time,
                            selected = mealPrepFireTime == time,
                            onClick = { viewModel.setMealPrepFireTime(time) }
                        )
                    }
                    OptionChip(
                        label = "自定义…",
                        selected = false,
                        onClick = { showMealPrepTimePicker = true }
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "当前默认：" + (if (mealPrepDayShift == -1) "前一天 " else "当天 ") + mealPrepFireTime,
                    fontSize = 12.sp,
                    color = OrangeStart,
                    fontWeight = FontWeight.Medium
                )
            }

            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                Text(
                    text = "记账提醒",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "设置了月度总预算后，本月支出达到或超过预算时，首页和记账页会亮出超支提示条。",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.size(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "预算超支提醒",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (budgetReminderEnabled) "超支时会在首页提示" else "已关闭，不再提示超支",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = budgetReminderEnabled,
                        onCheckedChange = { viewModel.setBudgetReminderEnabled(it) }
                    )
                }
            }

            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                Text(
                    text = "提醒可靠性",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "小米等手机把应用划出最近任务时会强制停止并取消全部提醒。完成下面两项设置后，提醒就能稳定准点触发。",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.size(12.dp))

                ReliabilityItem(
                    title = "电池优化白名单",
                    subtitle = if (batteryOptimized) {
                        "已允许，省电策略不再限制提醒"
                    } else {
                        "建议允许，否则提醒可能被省电推迟或直接取消"
                    },
                    done = batteryOptimized,
                    actionLabel = "一键允许",
                    onAction = {
                        activityContext?.let { ReminderReliability.requestIgnoreBatteryOptimizations(it) }
                    }
                )
                ReliabilityItem(
                    title = "自启动权限",
                    subtitle = "点亮柠檬百宝箱的自启动开关",
                    done = false,
                    actionLabel = "去设置",
                    onAction = {
                        activityContext?.let { ReminderReliability.openAutoStartSettings(it) }
                    }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "常驻保活",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "通知栏常驻一条无声通知，防止划卡清理杀掉提醒",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = keepAliveEnabled,
                        onCheckedChange = { viewModel.setKeepAliveEnabled(it) }
                    )
                }
            }

            if (showTimePicker) {
                TimePickerCardDialog(
                    title = "自定义提醒时间",
                    initialTime = reminderTimes.lastOrNull() ?: "19:00",
                    onDismiss = { showTimePicker = false },
                    onConfirm = { time ->
                        viewModel.addCustomReminderTime(time)
                        showTimePicker = false
                    }
                )
            }
            if (showMealPrepTimePicker) {
                TimePickerCardDialog(
                    title = "备菜提醒时间",
                    initialTime = mealPrepFireTime,
                    onDismiss = { showMealPrepTimePicker = false },
                    onConfirm = { time ->
                        viewModel.setMealPrepFireTime(time)
                        showMealPrepTimePicker = false
                    }
                )
            }
        }
    }
}

/** 时间选择弹窗：24 小时制，确认后以 HH:mm 文本回调。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerCardDialog(
    title: String,
    initialTime: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val pickerState = rememberTimePickerState(
        initialHour = initialTime.substringBefore(":").toIntOrNull() ?: 19,
        initialMinute = initialTime.substringAfter(":").toIntOrNull() ?: 0,
        is24Hour = true
    )
    AppDialog(
        title = title,
        onDismissRequest = onDismiss,
        confirmText = "确定",
        onConfirm = { onConfirm("%02d:%02d".format(pickerState.hour, pickerState.minute)) }
    ) {
        TimePicker(state = pickerState)
    }
}

/** 可靠性检查项的一行：左边标题与说明，右边状态或跳转按钮。 */
@Composable
private fun ReliabilityItem(
    title: String,
    subtitle: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = if (done) OrangeStart else TextSecondary
            )
        }
        if (done) {
            Text(
                text = "已完成",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = OrangeStart
            )
        } else {
            OptionChip(
                label = actionLabel,
                selected = true,
                onClick = onAction
            )
        }
    }
}