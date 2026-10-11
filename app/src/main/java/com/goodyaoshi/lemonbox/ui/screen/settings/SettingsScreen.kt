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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.goodyaoshi.lemonbox.BuildConfig
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.settings.ThemeMode
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.SettingsViewModel
import com.goodyaoshi.lemonbox.util.BrandCopy
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

/**
 * 动作胶囊按钮：立刻触发一个动作（如「一键允许」「去设置」）。
 * I9：此前这两个动作借 OptionChip(selected = true) 渲染，把「可选项」的视觉语言
 * 用在了「按钮」上，用户难以分辨哪些是配置项、哪些会立即执行。
 * 这里单独抽一个动作按钮：浅色底 + 品牌色文字 + 尾部箭头，并声明 Button 角色。
 */
@Composable
private fun ActionPillButton(
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(OrangeStart.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = OrangeStart
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = OrangeStart,
            modifier = Modifier.size(16.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val reminderTimes by viewModel.reminderTimes.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val keepAliveEnabled by viewModel.keepAliveEnabled.collectAsState()
    val autoStartConfirmed by viewModel.autoStartConfirmed.collectAsState()
    val customReminderTimes by viewModel.customReminderTimes.collectAsState()
    val mealPrepDayShift by viewModel.mealPrepDayShift.collectAsState()
    val mealPrepFireTime by viewModel.mealPrepFireTime.collectAsState()
    val budgetReminderEnabled by viewModel.budgetReminderEnabled.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

    // 应用级提示通道：用于「临时关闭后台」等结果的反馈。
    val appSnackbar = LocalAppSnackbar.current

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
                    style = MaterialTheme.typography.headlineMedium,
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
                    text = "提醒时间",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "可以多选。每天到这些时间点各检查一次，命中提醒阶梯的物品会给你汇总通知（系统可能略有延迟）。",
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
                    text = "小米等手机把应用划出最近任务时会强制停止并取消全部提醒。完成下面几项设置后，提醒就能稳定准点触发。",
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
                    subtitle = if (autoStartConfirmed) {
                        "已按你的确认记录为已设置（系统不提供自动检测）"
                    } else {
                        "点亮${BrandCopy.APP_NAME}的自启动开关，否则划卡后提醒会失效"
                    },
                    done = autoStartConfirmed,
                    actionLabel = if (autoStartConfirmed) "再检查一次" else "去设置",
                    onAction = {
                        activityContext?.let { ReminderReliability.openAutoStartSettings(it) }
                        viewModel.markAutoStartConfirmed()
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
                            text = "开启后通知栏常驻一条无声通知、进程保持前台状态（会占用少量内存）；关掉能更省内存，但划卡后提醒可能失效",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = keepAliveEnabled,
                        onCheckedChange = { viewModel.setKeepAliveEnabled(it) }
                    )
                }
                // 临时关闭后台（问题9）：立即释放前台服务占用的内存，不改动上面的开关，
                // 下次打开应用会按开关状态自动恢复，对应「临时完全关闭、后面再自启动」。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "临时关闭后台",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "点一下立即释放后台内存（含那条常驻通知），适合临时不用提醒、想让手机更省电的时候；下次打开应用会自动恢复",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    ActionPillButton(
                        label = "释放",
                        onClick = {
                            viewModel.releaseBackgroundNow()
                            appSnackbar?.postMessage("已临时关闭后台，下次打开应用会自动恢复")
                        }
                    )
                }
            }

            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                Text(
                    text = "关于与更新",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "有新版本时可在这里直接下载安装：WiFi 下直接下载；移动数据会先提示，确认后再用流量下载。",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.size(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "版本 v${BuildConfig.VERSION_NAME}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = when {
                                updateState.downloading -> "正在下载新版本…"
                                updateState.checking -> "正在检查更新…"
                                else -> "从 GitHub Release 获取最新版本"
                            },
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    if (updateState.checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = OrangeStart
                        )
                    } else {
                        ActionPillButton(
                            label = "检查更新",
                            onClick = { viewModel.checkForUpdate() }
                        )
                    }
                }
                updateState.message?.let { message ->
                    Spacer(modifier = Modifier.size(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message,
                            fontSize = 12.sp,
                            color = OrangeStart,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.consumeUpdateMessage() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭提示",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
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

            updateState.available?.let { info ->
                AppDialog(
                    title = "发现新版本 v${info.versionName}",
                    onDismissRequest = { viewModel.dismissUpdateDialog() },
                    subtitle = buildString {
                        append("安装包约 ")
                        append(formatSize(info.sizeBytes))
                        if (info.notes.isNotBlank()) {
                            append("\n\n")
                            append(info.notes)
                        }
                    },
                    confirmText = if (updateState.downloading) "下载中…" else "下载并安装",
                    onConfirm = { viewModel.downloadAndInstallUpdate() },
                    confirmEnabled = !updateState.downloading,
                    dismissText = "稍后"
                ) {
                    if (updateState.downloading) {
                        val progress = updateState.progress
                        if (progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = if (progress >= 0f) {
                                "已下载 ${(progress * 100).toInt()}%"
                            } else {
                                "正在下载…"
                            },
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            if (updateState.awaitingDataConfirm) {
                AppDialog(
                    title = "用移动数据下载？",
                    onDismissRequest = { viewModel.dismissDataConfirm() },
                    subtitle = "当前不在 WiFi 下，下载安装包会消耗移动数据流量。确认后将继续下载。",
                    confirmText = "继续下载",
                    onConfirm = { viewModel.confirmDownloadOverData() },
                    dismissText = "取消"
                )
            }

            if (updateState.needInstallPermission) {
                AppDialog(
                    title = "需要先允许安装",
                    onDismissRequest = { viewModel.dismissInstallPermissionHint() },
                    subtitle = "系统默认禁止从${BrandCopy.APP_NAME}直接安装应用。到设置里允许后回到这里，再点一次「下载并安装」即可。",
                    confirmText = "去开启",
                    onConfirm = { viewModel.openInstallPermission() }
                )
            }
        }
    }
}

/** 时间选择弹窗：24 小时制，确认后以 HH:mm 文本回调。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
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
            // I9：这里「一键允许」「去设置」是动作而非可选项，改用专门的动作按钮渲染。
            ActionPillButton(
                label = actionLabel,
                onClick = onAction
            )
        }
    }
}

/** 把字节数按 KB/MB 友好展示；未知大小时给出「未知」占位。 */
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "未知大小"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1.0) {
        "%.1f MB".format(mb)
    } else {
        "%.0f KB".format(bytes / 1024.0)
    }
}