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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.settings.ThemeMode
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
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
                    AppPreferences.REMINDER_TIME_OPTIONS.forEach { time ->
                        val selected = reminderTimes.contains(time)
                        OptionChip(
                            label = time,
                            selected = selected,
                            onClick = {
                                val updated = if (selected) {
                                    reminderTimes - time
                                } else {
                                    (reminderTimes + time).sorted()
                                }
                                viewModel.setReminderTimes(updated)
                            }
                        )
                    }
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
        }
    }
}