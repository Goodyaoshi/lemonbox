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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.forecast.RestockForecast
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.StatusWarning
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.RestockForecastViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 常买物品预测（F08）：按消耗速度推算每件家当离用完还有多久，
 * 快用完的排在最前，可一键加进待买清单。
 */
@Composable
fun RestockForecastScreen(
    onBack: () -> Unit,
    viewModel: RestockForecastViewModel = hiltViewModel()
) {
    val forecasts by viewModel.forecasts.collectAsState()
    val appSnackbar = LocalAppSnackbar.current

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
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
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
                if (forecasts.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = "还没有可预测的物品",
                            message = "记录几件按件消耗的东西、点过几次「用1件」之后，就能算出补货时机了。",
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(CardWhite)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = "每天约 ${formatDaily(forecast.dailyConsumption)} ${item.unit}" +
                    " · 还能用 ${formatDays(forecast.daysUntilEmpty)}",
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
                fontSize = 12.sp,
                color = TextHint
            )
        } else {
            Text(
                text = "加入待买",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
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
