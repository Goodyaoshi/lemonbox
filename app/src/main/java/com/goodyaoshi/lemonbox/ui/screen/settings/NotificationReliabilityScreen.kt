package com.goodyaoshi.lemonbox.ui.screen.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.SettingsViewModel
import com.goodyaoshi.lemonbox.util.BrandCopy
import com.goodyaoshi.lemonbox.util.ReminderReliability

/**
 * 通知可靠性（SET1）：电池白名单、自启动权限与常驻保活的独立二级页。
 * 从主设置页拆出，平台适配指引不再与外观、纪念日等常规设置混排。
 */
@Composable
fun NotificationReliabilityScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val keepAliveEnabled by viewModel.keepAliveEnabled.collectAsState()
    val autoStartConfirmed by viewModel.autoStartConfirmed.collectAsState()

    // 应用级提示通道：用于「临时关闭后台」等结果的反馈。
    val appSnackbar = LocalAppSnackbar.current

    // 自启动「去设置」后是否已跳转（SET1）：系统不提供自启动检测，跳转本身不等于已开启，
    // 因此先记录「已前往」，回到本页由用户点「我已开启」确认，避免不撒谎地写死「已完成」。
    // 用 rememberSaveable（P3-29）：跳系统设置会导致进程重建，普通 remember 会丢失「已前往」，
    // 回来后按钮退回「去设置」，用户会以为跳转没生效。
    var autoStartVisited by rememberSaveable { mutableStateOf(false) }
    // 「临时关闭后台」二次确认（SET1）：这是立即生效的破坏性动作，先弹确认再执行。
    var showReleaseConfirm by remember { mutableStateOf(false) }

    // 电池优化白名单状态：从系统对话框回来（onResume）时刷新。
    // 取 LocalActivity 而不是把 LocalContext 向下转型：后者会被 lint 判为 ContextCastToActivity，
    // 而且「上下文就是宿主 Activity」这件事在 Compose 里本就该由 LocalActivity 表达。
    val activityContext = LocalActivity.current as? ComponentActivity
    var batteryOptimized by remember {
        mutableStateOf(
            activityContext?.let { ReminderReliability.isIgnoringBatteryOptimizations(it) } ?: true
        )
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
                    text = "通知可靠性",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
            }

            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                // 形状与字号走令牌（F6）：卡片统一到 extraLarge，标题走 titleLarge（18sp）。
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 12.dp
            ) {
                Text(
                    text = "为什么提醒会不响？",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "小米等手机把应用划出最近任务时会停止并取消全部提醒。完成下面几项设置即可稳定触发。",
                    // 字号走主题字阶（F6）：bodySmall 为 12sp。
                    style = MaterialTheme.typography.bodySmall,
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
                    // 状态文案诚实化（SET1）：白名单可真实检测，达成就显示「已允许」。
                    doneLabel = "已允许",
                    actionLabel = "一键允许",
                    onAction = {
                        activityContext?.let { ReminderReliability.requestIgnoreBatteryOptimizations(it) }
                    }
                )
                ReliabilityItem(
                    title = "自启动权限",
                    subtitle = when {
                        autoStartConfirmed -> "已按你的确认记录为已开启（系统不提供自动检测）"
                        autoStartVisited -> "已前往系统设置，请返回后确认自启动开关已点亮"
                        else -> "点亮${BrandCopy.APP_NAME}的自启动开关，否则划卡后提醒会失效"
                    },
                    done = autoStartConfirmed,
                    // 状态文案诚实化（SET1）：自启动无法自动检测，只能记录用户自述，不写「已完成」。
                    doneLabel = "已确认开启",
                    actionLabel = when {
                        autoStartConfirmed -> "重新设置"
                        autoStartVisited -> "我已开启"
                        else -> "去设置"
                    },
                    onAction = {
                        when {
                            // 已确认：再进系统设置复核，先撤销旧记录，回来后重新确认（SET1）。
                            autoStartConfirmed -> {
                                activityContext?.let { ReminderReliability.openAutoStartSettings(it) }
                                viewModel.clearAutoStartConfirmed()
                                autoStartVisited = true
                            }
                            // 已跳转过：由用户自述确认已开启，并给撤销出口（SET1）。
                            autoStartVisited -> {
                                viewModel.markAutoStartConfirmed()
                                autoStartVisited = false
                                appSnackbar?.showUndo("已记录为已开启自启动", "撤销") {
                                    viewModel.clearAutoStartConfirmed()
                                }
                            }
                            // 首次只跳转，不写状态（SET1）：跳转 ≠ 已开启，等用户回来确认。
                            else -> {
                                activityContext?.let { ReminderReliability.openAutoStartSettings(it) }
                                autoStartVisited = true
                            }
                        }
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
                            // 字号走主题字阶（F6）：titleSmall 为 14sp。
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "开启后通知栏常驻一条无声通知、进程保持前台（占用少量内存）；关掉更省内存，但划卡后提醒可能失效",
                            // 字号走主题字阶（F6）：bodySmall 为 12sp。
                            style = MaterialTheme.typography.bodySmall,
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
                            // 字号走主题字阶（F6）：titleSmall 为 14sp。
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "立即释放后台内存（含常驻通知），适合临时不用提醒、想让手机省电时；下次打开应用自动恢复",
                            // 字号走主题字阶（F6）：bodySmall 为 12sp。
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    ActionPillButton(
                        label = "释放",
                        // 二次确认（SET1）：这是立即生效的内存释放，先弹确认，执行后再给撤销回执。
                        onClick = { showReleaseConfirm = true }
                    )
                }
            }
        }
    }

    // 「临时关闭后台」的二次确认（SET1）：破坏性动作不一键生效，执行后额外给撤销出口。
    if (showReleaseConfirm) {
        AppDialog(
            title = "临时关闭后台？",
            subtitle = "会立刻停掉常驻保活与那条通知，释放后台内存；下次打开应用会自动恢复。",
            confirmText = "关闭后台",
            destructiveConfirm = true,
            onConfirm = {
                viewModel.releaseBackgroundNow()
                showReleaseConfirm = false
                appSnackbar?.showUndo("已临时关闭后台，下次打开应用会自动恢复", "撤销") {
                    // 撤销即按当前开关状态重新拉起保活服务（SET1）。
                    viewModel.setKeepAliveEnabled(keepAliveEnabled)
                }
            },
            onDismissRequest = { showReleaseConfirm = false }
        )
    }
}

/**
 * 动作胶囊按钮：立刻触发一个动作（如「一键允许」「去设置」）。
 * 浅色底 + 品牌色文字 + 尾部箭头，并声明 Button 角色（I9）。
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

/** 可靠性检查项的一行：左边标题与说明，右边状态或跳转按钮。 */
@Composable
private fun ReliabilityItem(
    title: String,
    subtitle: String,
    done: Boolean,
    doneLabel: String,
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
                // 字号走主题字阶（F6）：titleSmall 为 14sp。
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                // 字号走主题字阶（F6）：bodySmall 为 12sp。
                style = MaterialTheme.typography.bodySmall,
                color = if (done) OrangeStart else TextSecondary
            )
        }
        if (done) {
            Text(
                text = doneLabel,
                // 字号走主题字阶（F6）：labelLarge 为 14sp。
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = OrangeStart
            )
        } else {
            ActionPillButton(
                label = actionLabel,
                onClick = onAction
            )
        }
    }
}
