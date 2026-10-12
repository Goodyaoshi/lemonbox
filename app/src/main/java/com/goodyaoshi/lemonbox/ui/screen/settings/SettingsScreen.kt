package com.goodyaoshi.lemonbox.ui.screen.settings

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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.BuildConfig
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.settings.ThemeMode
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.SettingsViewModel
import com.goodyaoshi.lemonbox.util.BrandCopy

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
    onOpenNotificationReliability: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val reminderTimes by viewModel.reminderTimes.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val customReminderTimes by viewModel.customReminderTimes.collectAsState()
    val mealPrepDayShift by viewModel.mealPrepDayShift.collectAsState()
    val mealPrepFireTime by viewModel.mealPrepFireTime.collectAsState()
    val budgetReminderEnabled by viewModel.budgetReminderEnabled.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val appLockEnabled by viewModel.appLockEnabled.collectAsState()

    var showTimePicker by remember { mutableStateOf(false) }
    var showMealPrepTimePicker by remember { mutableStateOf(false) }
    var showAppLockDialog by remember { mutableStateOf(false) }
    // 「关于与更新」整块默认折叠（IA-5）：GitHub 检查更新 / 下载 / 安装引导是低频操作，
    // 平铺会把外观、提醒时间等高频设置淹掉；折叠后只留一行标题。
    var aboutExpanded by remember { mutableStateOf(false) }

    // 固定档位 + 用户自定义候选，合并排序后统一展示。
    val allTimeOptions =
        (AppPreferences.REMINDER_TIME_OPTIONS + customReminderTimes).distinct().sorted()

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
                    text = "安全",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "应用锁已从「我的」迁到这里：开启后进入 App 都要先验证。",
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
                            text = "应用锁",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (appLockEnabled) {
                                "已开启，进入需要 PIN 或系统密码 / 指纹"
                            } else {
                                "给私人内容加一道锁（PIN + 系统密码 / 指纹）"
                            },
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    ActionPillButton(
                        label = if (appLockEnabled) "管理" else "开启",
                        onClick = { showAppLockDialog = true }
                    )
                }
            }

            // 「在一起」起始日与计数前缀已下沉到纪念日页页头（IA-6），
            // 全局设置只保留真正全局的项，单域配置不再混排。

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
                // 通知可靠性独立成二级页（SET1）：MIUI 保活等平台适配内容不再占据主设置页。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onOpenNotificationReliability)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "通知可靠性",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "电池白名单、自启动权限与常驻保活，保证提醒准点触发",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }

            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                // 可展开卡片（IA-5）：整行标题可点，尾部 chevron 折叠朝右、展开转向下；
                // 折叠态隐藏全部更新内容，常规设置项不受影响。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { aboutExpanded = !aboutExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "关于与更新",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = if (aboutExpanded) "收起关于与更新" else "展开关于与更新",
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(if (aboutExpanded) 90f else 0f)
                    )
                }
                if (aboutExpanded) {
                    Text(
                        text = "有新版本时可在这里直接下载安装：WiFi 下直接下载；移动数据会先提示，确认后再用流量下载。",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 6.dp)
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
            if (showAppLockDialog) {
                AppLockSettingsDialog(
                    enabled = appLockEnabled,
                    onDismiss = { showAppLockDialog = false },
                    onEnable = { pin ->
                        viewModel.enableAppLock(pin)
                        showAppLockDialog = false
                    },
                    verifyPin = viewModel::verifyAppLockPin,
                    onDisable = {
                        viewModel.disableAppLock()
                        showAppLockDialog = false
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

/** 应用锁设置弹窗（F19）：未开启时设 PIN 开启；已开启时输 PIN 关闭。 */
@Composable
private fun AppLockSettingsDialog(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onEnable: (String) -> Unit,
    verifyPin: (String) -> Boolean,
    onDisable: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    if (enabled) {
        AppDialog(
            title = "关闭应用锁",
            onDismissRequest = onDismiss,
            confirmText = "关闭",
            destructiveConfirm = true,
            confirmEnabled = pin.length >= MIN_PIN_LENGTH,
            onConfirm = {
                if (verifyPin(pin)) onDisable() else error = true
            }
        ) {
            Text(
                text = "输入当前 PIN 以关闭应用锁。",
                color = TextSecondary,
                fontSize = 13.sp
            )
            PinField(value = pin, onValueChange = { pin = it; error = false }, isError = error)
        }
    } else {
        AppDialog(
            title = "开启应用锁",
            onDismissRequest = onDismiss,
            confirmText = "开启",
            confirmEnabled = pin.length >= MIN_PIN_LENGTH,
            onConfirm = { onEnable(pin) }
        ) {
            Text(
                text = "设一个 4-12 位数字 PIN。之后进入 App 需要验证，也可以用系统密码 / 指纹。",
                color = TextSecondary,
                fontSize = 13.sp
            )
            PinField(value = pin, onValueChange = { pin = it }, isError = false)
        }
    }
}

@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.filter { ch -> ch.isDigit() }.take(MAX_PIN_LENGTH)) },
            singleLine = true,
            label = { Text("PIN") },
            isError = isError,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        if (isError) {
            Text(
                text = "PIN 不对，再试一次",
                color = StatusExpired,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 12

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