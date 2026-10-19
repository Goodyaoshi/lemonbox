package com.goodyaoshi.lemonbox.ui.screen.forecast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.forecast.RestockForecast
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SkeletonList
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.StatusWarning
import com.goodyaoshi.lemonbox.ui.theme.TagBlue
import com.goodyaoshi.lemonbox.ui.theme.TagBlueText
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TagRed
import com.goodyaoshi.lemonbox.ui.theme.TagRedText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.RestockForecastViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 常买物品预测（F08）：按消耗速度推算每件家当离用完还有多久，
 * 快用完的排在最前，可一键加进待买清单。
 */
@Composable
fun RestockForecastScreen(
    onBack: () -> Unit,
    // 空态「去家当用掉 1 件」的直达入口（P2-20）：此前空态只有一句文字，用户无路可走。
    onOpenHousehold: () -> Unit = {},
    viewModel: RestockForecastViewModel = hiltViewModel()
) {
    val forecasts by viewModel.forecasts.collectAsState()
    val appSnackbar = LocalAppSnackbar.current

    // 加载态（P2）：数据从 Room 流式读取，首帧是无初始值的空列表，
    // 先给一小段等待窗口画骨架，避免数据未到就先渲染「还没有可预测的物品」造成误读。
    var dataSettled by remember { mutableStateOf(false) }
    LaunchedEffect(forecasts) {
        if (forecasts.isNotEmpty()) dataSettled = true
    }
    LaunchedEffect(Unit) {
        delay(500)
        dataSettled = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
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
                    text = "补货预测",
                    // 页面主标题统一走主题字阶（F6）：headlineLarge 为 28sp。
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "tip") {
                    Text(
                        text = "按平时的消耗速度估算还能用多久，快用完的排在前面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)
                    )
                }
                if (!dataSettled) {
                    item(key = "loading") {
                        // 加载态（P2）：数据未到时先画骨架，与列表卡片同构。
                        SkeletonList(showHeader = false)
                    }
                } else if (forecasts.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = "还没有可预测的物品",
                            message = "记录几件按件消耗的东西、点过几次「用1件」之后，就能算出补货时机了。",
                            // 空态直达（P2-20）：补一键去家当列表，去用掉 1 件攒出消耗数据。
                            actionLabel = "去家当用掉 1 件",
                            onAction = onOpenHousehold,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else {
                    items(forecasts, key = { it.detail.item.id }) { forecast ->
                        ForecastRow(
                            forecast = forecast,
                            onAddToBuy = {
                                viewModel.addToBuy(forecast.detail.item.id)
                                appSnackbar?.postMessage("已加入待买清单")
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ForecastRow(
    forecast: RestockForecast,
    onAddToBuy: () -> Unit
) {
    val item = forecast.detail.item
    val accent = when (forecast.urgency) {
        0 -> StatusExpired
        1 -> StatusWarning
        else -> TextHint
    }
    // 紧迫度不只靠颜色（P2）：再给一句明确文案并渲染成标签，色觉障碍用户也能读懂轻重。
    val urgencyText = when (forecast.urgency) {
        0 -> "快用完了"
        1 -> "该补货了"
        else -> "还够用"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 圆角走形状令牌（F6）：收敛到 large（20dp）。
            .clip(MaterialTheme.shapes.large)
            .background(CardWhite)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    // 字号走主题字阶（F6）：titleSmall 为 14sp。
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                PillTag(
                    text = urgencyText,
                    backgroundColor = when (forecast.urgency) {
                        0 -> TagRed
                        1 -> TagOrange
                        else -> TagBlue
                    },
                    contentColor = when (forecast.urgency) {
                        0 -> TagRedText
                        1 -> TagOrangeText
                        else -> TagBlueText
                    }
                )
            }
            Text(
                text = "每天约 ${formatDaily(forecast.dailyConsumption)} ${item.unit}" +
                    " · 还能用 ${formatDays(forecast.daysUntilEmpty)}",
                // 字号走主题字阶（F6）：bodySmall 为 12sp。
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = "预计 ${formatDate(forecast.predictedEmptyAt)} 用完",
                style = MaterialTheme.typography.bodySmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (item.needRestock) {
            Text(
                text = "已在待买",
                // 字号走主题字阶（F6）：bodySmall 为 12sp。
                style = MaterialTheme.typography.bodySmall,
                color = TextHint
            )
        } else {
            Text(
                text = "加入待买",
                // 字号走主题字阶（F6）：labelLarge 为 14sp。
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier
                    // 触达面积达标（F7）：原胶囊仅约 32dp 高，先撑出 48dp 最小可点区，视觉尺寸不变。
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(TagOrange)
                    .clickable(onClick = onAddToBuy)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

private val forecastDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

private fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(forecastDateFormatter)

/** 日均消耗：不足 1 件保留一位小数，便于看「两天一件」这类节奏。 */
private fun formatDaily(daily: Double): String =
    if (daily >= 1.0) {
        "%.1f".format(daily)
    } else {
        "%.2f".format(daily)
    }

/** 距用完天数：一天以内按「今天/明天」口语化，其余四舍五入到天。 */
private fun formatDays(days: Double): String {
    if (days <= 0) return "已经用完"
    val rounded = ceil(days).roundToInt()
    return when (rounded) {
        0 -> "不到 1 天"
        1 -> "1 天"
        else -> "$rounded 天"
    }
}
