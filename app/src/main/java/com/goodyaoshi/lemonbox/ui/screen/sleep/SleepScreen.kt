package com.goodyaoshi.lemonbox.ui.screen.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.widget.WidgetSnapshot
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.SectionHeader
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.SleepViewModel
import com.goodyaoshi.lemonbox.util.BrandCopy

/** 就寝时间候选档位。 */
private val BEDTIME_OPTIONS = listOf(
    "21:00", "21:30", "22:00", "22:30", "23:00", "23:30", "00:00"
)

/**
 * 睡前氛围（F15）：给你今天的收尾速览与「该休息了」的提醒。
 * 视觉沿用全应用统一范式（装饰背景 + 白卡 + 主题色板），不再使用独立的夜色配色。
 */
@Composable
fun SleepScreen(
    onBack: () -> Unit,
    viewModel: SleepViewModel = hiltViewModel()
) {
    val review by viewModel.review.collectAsState()
    val enabled by viewModel.bedtimeEnabled.collectAsState()
    val time by viewModel.bedtimeTime.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // 顶栏与其他页面同规格：8/6 内边距、18sp 加粗标题（titleLarge）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
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
                    text = "睡前氛围",
                    // 字号走主题字阶（F6）：titleLarge 为 18sp。
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = "今天也辛苦了",
                        // 字号走主题字阶（F6）：headlineSmall 为 24sp。
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "把手机先放一放，给今天收个尾，然后好好睡一觉。",
                        // 字号走主题字阶（F6）：labelLarge 承载 13sp。
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    Spacer(modifier = Modifier.height(22.dp))
                    SectionHeader(title = "今日速览")
                    Spacer(modifier = Modifier.height(10.dp))
                    AppSurfaceCard(
                        // 圆角走形状令牌（F6）：22dp 卡片收敛到 extraLarge（24dp，默认值）。
                        shadowElevation = 12.dp
                    ) {
                        SnapshotLine("待买清单", snapshotToBuy(review))
                        SnapshotLine("今天的事", snapshotToday(review))
                        SnapshotLine("本月支出", snapshotMonth(review))
                    }

                    Spacer(modifier = Modifier.height(22.dp))
                    SectionHeader(title = "就寝提醒")
                    Spacer(modifier = Modifier.height(10.dp))
                    AppSurfaceCard(
                        // 圆角走形状令牌（F6）：22dp 卡片收敛到 extraLarge（24dp，默认值）。
                        shadowElevation = 12.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bedtime,
                                contentDescription = null,
                                tint = OrangeStart,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "到点提醒该休息了",
                                    // 字号走主题字阶（F6）：titleMedium 为 16sp。
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (enabled) "每晚 $time 提醒" else "当前未开启",
                                    // 字号走主题字阶（F6）：bodySmall 为 12sp。
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = viewModel::setBedtimeEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = OrangeStart,
                                    checkedThumbColor = Color.White
                                )
                            )
                        }
                        if (enabled) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BEDTIME_OPTIONS.forEach { option ->
                                    TimeChip(
                                        label = option,
                                        selected = option == time,
                                        onClick = { viewModel.setBedtimeTime(option) }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "晚安，${BrandCopy.APP_NAME}先替你看着东西。",
                        // 字号走主题字阶（F6）：bodySmall 为 12sp。
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
private fun SnapshotLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            // 字号走主题字阶（F6）：bodyMedium 为 14sp。
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            // 字号走主题字阶（F6）：14sp 数值用 titleSmall 承载强调。
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
    }
}

@Composable
private fun TimeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            // 圆角走形状令牌（F6）：999dp 胶囊收敛到 CircleShape。
            .clip(CircleShape)
            .background(if (selected) OrangeStart else SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            // 字号走主题字阶（F6）：labelLarge 承载 13sp。
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else TextSecondary
        )
    }
}

private fun snapshotToBuy(review: WidgetSnapshot?): String =
    review?.toBuyNames?.size?.let { "$it 件" } ?: "—"

private fun snapshotToday(review: WidgetSnapshot?): String =
    review?.let { "${it.todayReminderCount} 件" } ?: "—"

private fun snapshotMonth(review: WidgetSnapshot?): String =
    review?.let { "¥${formatYuan(it.monthExpenseCents)}" } ?: "—"

private fun formatYuan(cents: Long): String {
    val yuan = cents / 100.0
    return if (yuan % 1.0 == 0.0) yuan.toLong().toString() else "%.2f".format(yuan)
}
