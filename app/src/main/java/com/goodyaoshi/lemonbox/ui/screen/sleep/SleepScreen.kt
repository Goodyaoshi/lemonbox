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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.widget.WidgetSnapshot
import com.goodyaoshi.lemonbox.ui.viewmodel.SleepViewModel
import com.goodyaoshi.lemonbox.util.BrandCopy

/** 深夜渐变色，营造安静的睡前氛围。 */
private val NightTop = Color(0xFF1B1B2F)
private val NightBottom = Color(0xFF2C2440)
private val NightCard = Color.White.copy(alpha = 0.08f)
private val NightAccent = Color(0xFFF2C879)
private val NightTextPrimary = Color(0xFFF4EFE6)
private val NightTextSecondary = Color(0xB3F4EFE6)

/** 就寝时间候选档位。 */
private val BEDTIME_OPTIONS = listOf(
    "21:00", "21:30", "22:00", "22:30", "23:00", "23:30", "00:00"
)

/**
 * 睡前氛围（F15）：一处安静的夜里界面，给你今天的收尾与「该休息了」的提醒。
 */
@Composable
fun SleepScreen(
    onBack: () -> Unit,
    viewModel: SleepViewModel = hiltViewModel()
) {
    val review by viewModel.review.collectAsState()
    val enabled by viewModel.bedtimeEnabled.collectAsState()
    val time by viewModel.bedtimeTime.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NightTop, NightBottom)))
    ) {
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
                        tint = NightTextPrimary
                    )
                }
                Text(
                    text = "睡前氛围",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = NightTextPrimary
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Text(
                    text = "今天也辛苦了",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = NightTextPrimary
                )
                Text(
                    text = "把手机先放一放，给今天收个尾，然后好好睡一觉。",
                    fontSize = 13.sp,
                    color = NightTextSecondary,
                    modifier = Modifier.padding(top = 6.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))
                NightSection(title = "今日速览") {
                    SnapshotLine("待买清单", snapshotToBuy(review))
                    SnapshotLine("今天的事", snapshotToday(review))
                    SnapshotLine("本月支出", snapshotMonth(review))
                }

                Spacer(modifier = Modifier.height(16.dp))
                NightSection(title = "就寝提醒") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bedtime,
                            contentDescription = null,
                            tint = NightAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "到点提醒该休息了",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NightTextPrimary
                            )
                            Text(
                                text = if (enabled) "每晚 $time 提醒" else "当前未开启",
                                fontSize = 12.sp,
                                color = NightTextSecondary
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = viewModel::setBedtimeEnabled,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = NightAccent,
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
                    fontSize = 12.sp,
                    color = NightTextSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun NightSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(NightCard)
            .padding(16.dp)
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = NightAccent,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        content()
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
            fontSize = 14.sp,
            color = NightTextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = NightTextPrimary
        )
    }
}

@Composable
private fun TimeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) NightAccent else Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) NightTop else NightTextPrimary
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
