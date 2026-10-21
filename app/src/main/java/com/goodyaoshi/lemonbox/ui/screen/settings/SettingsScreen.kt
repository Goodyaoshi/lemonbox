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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.BuildConfig
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
            // 触达面积达标（F7）：胶囊约 34dp 高，先撑出 48dp 最小可点区，再绘制胶囊本身，视觉尺寸不变。
            .minimumInteractiveComponentSize()
            // 胶囊走 CircleShape（F6）：不再内联 RoundedCornerShape(999.dp)。
            .clip(CircleShape)
            .background(if (selected) OrangeStart else SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            // 字号走主题字阶（F6）：labelLarge 为 14sp。
            style = MaterialTheme.typography.labelLarge,
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
            // 触达面积达标（F7）：胶囊约 34dp 高，先撑出 48dp 最小可点区，再绘制胶囊本身，视觉尺寸不变。
            .minimumInteractiveComponentSize()
            // 胶囊走 CircleShape（F6）：不再内联 RoundedCornerShape(999.dp)。
            .clip(CircleShape)
            .background(OrangeStart.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            // 字号走主题字阶（F6）：labelLarge 为 14sp。
            style = MaterialTheme.typography.labelLarge,
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
    /** 跳「家当设置」：提醒阶梯（提前几天）在那里配，设置页只放时间点。 */
    onOpenHouseholdSettings: () -> Unit = {},
    /**
     * 跳「家当设置 › 有效期与提醒」Tab（P2-24）。
     * 「提前几天提醒」链接改走这个入口，导航层据此带上初始 Tab 直达阶梯；
     * 不传时退回 [onOpenHouseholdSettings]，保证旧调用方仍照常工作。
     */
    onOpenExpiryReminderSettings: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val reminderTimes by viewModel.reminderTimes.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val customReminderTimes by viewModel.customReminderTimes.collectAsState()
    val mealPrepDayShift by viewModel.mealPrepDayShift.collectAsState()
    val mealPrepFireTime by viewModel.mealPrepFireTime.collectAsState()
    val budgetReminderEnabled by viewModel.budgetReminderEnabled.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

    var showTimePicker by remember { mutableStateOf(false) }
    var showMealPrepTimePicker by remember { mutableStateOf(false) }
    // 「关于与更新」整块默认折叠（IA-5）：GitHub 检查更新 / 下载 / 安装引导是低频操作，
    // 平铺会把外观、提醒时间等高频设置淹掉；折叠后只留一行标题。
    var aboutExpanded by remember { mutableStateOf(false) }
    // 常驻「下载安装包」入口的加速 / 直连选择框。
    var showDownloadChoice by remember { mutableStateOf(false) }

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
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 12.dp
            ) {
                Text(
                    text = "外观",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "深色模式可跟随系统，也可手动固定。",
                    style = MaterialTheme.typography.bodySmall,
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

            // 「在一起」起始日与计数前缀已下沉到纪念日页页头（IA-6），
            // 全局设置只保留真正全局的项，单域配置不再混排。

            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 12.dp
            ) {
                Text(
                    text = "提醒",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "通知时间点、备菜预填与预算超支提示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.size(14.dp))

                // ---- 小组：通知时间 ----
                Text(
                    text = "通知时间",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    // 提前几天（提醒阶梯）在家当设置里配，这里明说去处并给出跳转，
                    // 免得用户读完「命中提醒阶梯」却找不到在哪调。
                    text = "可多选。命中提醒阶梯的物品会在这些时间点汇总通知你。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.size(4.dp))
                Row(
                    modifier = Modifier
                        // 触达面积达标（F7）：行内链接本不足 48dp，先撑出最小可点区。
                        .minimumInteractiveComponentSize()
                        .clip(MaterialTheme.shapes.small)
                        .clickable {
                            // 直达「有效期与提醒」Tab（P2-24）：阶梯就在那个 Tab 里，
                            // 不再落到默认的「管理分类」让人再找一遍。
                            (onOpenExpiryReminderSettings ?: onOpenHouseholdSettings)()
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "提前几天提醒 ›",
                        style = MaterialTheme.typography.bodySmall,
                        color = OrangeStart,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.size(10.dp))
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
                    style = MaterialTheme.typography.bodySmall,
                    color = OrangeStart,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.size(18.dp))

                // ---- 小组：备菜提醒默认 ----
                Text(
                    text = "备菜提醒默认",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = "点周菜谱的「提醒准备」时会按这里预填。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.size(10.dp))
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
                    style = MaterialTheme.typography.bodySmall,
                    color = OrangeStart,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.size(18.dp))

                // ---- 小组：预算超支提醒（属提醒主题，不再单开一张与「外观」平级的卡）----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "预算超支提醒",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (budgetReminderEnabled) {
                                "设了月度预算后，超支时首页和记账页会亮出提示条。"
                            } else {
                                "已关闭，不再提示超支"
                            },
                            style = MaterialTheme.typography.bodySmall,
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
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 12.dp
            ) {
                // 通知可靠性独立成二级页（SET1）：MIUI 保活等平台适配内容不再占据主设置页。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 触达面积达标（F7）：整行跳转先撑出 48dp 最小可点区。
                        .minimumInteractiveComponentSize()
                        .clip(MaterialTheme.shapes.large)
                        .clickable(onClick = onOpenNotificationReliability)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "通知可靠性",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "电池白名单、自启动权限与常驻保活，保证提醒准点触发",
                            style = MaterialTheme.typography.bodySmall,
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
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 12.dp
            ) {
                // 可展开卡片（IA-5）：整行标题可点，尾部 chevron 折叠朝右、展开转向下；
                // 折叠态隐藏全部更新内容，常规设置项不受影响。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 触达面积达标（F7）：可展开标题行先撑出 48dp 最小可点区。
                        .minimumInteractiveComponentSize()
                        .clip(MaterialTheme.shapes.large)
                        .clickable { aboutExpanded = !aboutExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "关于与更新",
                        style = MaterialTheme.typography.titleLarge,
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
                        text = "可检查新版本，也可随时下载安装包（重装、分享给他人）。下载交给浏览器，" +
                            "完成后在通知栏点开安装包即可。",
                        style = MaterialTheme.typography.bodySmall,
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
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = when {
                                    updateState.downloading -> "正在下载并校验安装包…"
                                    updateState.checking -> "正在检查更新…"
                                    else -> "从 GitHub Release 获取最新版本"
                                },
                                style = MaterialTheme.typography.bodySmall,
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
                    // 常驻下载入口：没有新版本时同样可用——重装、或把安装包分享给他人
                    // 都需要拿到当前版本的安装包，不该只在「有新版本」时才出现。
                    Spacer(modifier = Modifier.size(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "下载安装包",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = if (updateState.downloading) {
                                    val percent = updateState.downloadProgress
                                    if (percent >= 0f) {
                                        "正在下载 ${(percent * 100).toInt()}%，校验通过后打开安装器"
                                    } else {
                                        "正在下载，校验通过后打开安装器"
                                    }
                                } else {
                                    "应用内下载，校验 SHA-256 与签名后安装"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        ActionPillButton(
                            label = if (updateState.downloading) "下载中…" else "校验安装",
                            onClick = { viewModel.downloadAndInstall() }
                        )
                    }
                    // 浏览器下载保留为兜底：个别 ROM 不允许应用内调起安装器。
                    // 它无法由应用校验，因此不作为默认路径。
                    TextButton(onClick = { showDownloadChoice = true }) {
                        Text(
                            text = "改用浏览器下载（不校验，兜底）",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    updateState.message?.let { message ->
                        Spacer(modifier = Modifier.size(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
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
                    dismissText = "稍后"
                ) {
                    Text(
                        text = "推荐应用内校验安装：先比对发布方 SHA-256，再比对安装包签名与当前应用，" +
                            "两者都通过才会打开系统安装器。即使下载走了加速代理，签名不符也装不上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    TextButton(
                        onClick = { viewModel.downloadAndInstall() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "校验安装（推荐）",
                            color = OrangeStart,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Text(
                        text = "个别机型不允许应用内调起安装器时，可改用浏览器下载（应用无法校验）：",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(
                            onClick = { viewModel.downloadInBrowser(direct = false) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "浏览器·加速下载", style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(
                            onClick = { viewModel.downloadInBrowser(direct = true) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "浏览器·直连下载", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (showDownloadChoice) {
                AppDialog(
                    title = "下载安装包",
                    onDismissRequest = { showDownloadChoice = false },
                    subtitle = "浏览器下载无法由应用校验，仅作为应用内安装失败时的兜底；" +
                        "两个地址内容相同，加速方式不可用时换直连。",
                    dismissText = "取消"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(
                            onClick = {
                                showDownloadChoice = false
                                viewModel.downloadInBrowser(direct = false)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "浏览器·加速下载", style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(
                            onClick = {
                                showDownloadChoice = false
                                viewModel.downloadInBrowser(direct = true)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "浏览器·直连下载", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
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