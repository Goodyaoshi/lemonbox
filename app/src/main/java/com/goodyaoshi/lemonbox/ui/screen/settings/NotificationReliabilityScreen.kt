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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
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
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                Text(
                    text = "为什么提醒会不响？",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "小米等手机把应用划出最近任务时会停止并取消全部提醒。完成下面几项设置即可稳定触发。",
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
                            text = "开启后通知栏常驻一条无声通知、进程保持前台（占用少量内存）；关掉更省内存，但划卡后提醒可能失效",
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
                            text = "立即释放后台内存（含常驻通知），适合临时不用提醒、想让手机省电时；下次打开应用自动恢复",
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
        }
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
            ActionPillButton(
                label = actionLabel,
                onClick = onAction
            )
        }
    }
}
