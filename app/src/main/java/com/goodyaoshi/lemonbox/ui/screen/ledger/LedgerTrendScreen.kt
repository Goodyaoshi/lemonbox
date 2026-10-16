package com.goodyaoshi.lemonbox.ui.screen.ledger

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.LedgerTrendBarChart
import com.goodyaoshi.lemonbox.ui.components.LedgerTrendLegend
import com.goodyaoshi.lemonbox.ui.components.LedgerTrendLineChart
import com.goodyaoshi.lemonbox.ui.components.SegmentedTabs
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.LedgerTrendViewModel

/**
 * 收支趋势：近 6 个月的收入/支出趋势图，全部用 Canvas 自绘。
 * 折线与柱状两图数据完全相同（P1），此前并列两卡占屏却零新增信息；
 * 现合并为一张卡、用切换器二选一，删除冗余占屏。
 */
@Composable
fun LedgerTrendScreen(
    onBack: () -> Unit,
    // 趋势页与统计页一致：空态需要一条直达记账的通道，从导航层传入。
    onNavigateToRecordEdit: (Long?) -> Unit,
    viewModel: LedgerTrendViewModel = hiltViewModel()
) {
    val trend by viewModel.trend.collectAsState()
    // 0 = 折线，1 = 柱状（P1）：两图数据相同，同一坐标系二选一即可。
    var chartMode by remember { mutableIntStateOf(0) }

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
                    text = "收支趋势",
                    // 字号走主题字阶（F6）：titleLarge 为 18sp。
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                if (!trend.hasData) {
                    EmptyState(
                        title = "还没有收支记录",
                        message = "记下几笔，趋势线就会慢慢显出来。",
                        actionLabel = "记一笔",
                        onAction = { onNavigateToRecordEdit(null) },
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    AppSurfaceCard(
                        // 圆角走形状令牌（F6）：extraLarge 为 24dp。
                        shape = MaterialTheme.shapes.extraLarge,
                        contentPadding = PaddingValues(18.dp),
                        shadowElevation = 12.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "近 6 个月收支",
                                // 字号走主题字阶（F6）：titleSmall 为 14sp。
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            LedgerTrendLegend()
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        // 切换器二选一（P1）：折线看趋势、柱状看对比，同一份数据只占一卡。
                        SegmentedTabs(
                            labels = listOf("折线", "柱状"),
                            selectedIndex = chartMode,
                            onSelect = { chartMode = it }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        if (chartMode == 0) {
                            LedgerTrendLineChart(
                                points = trend.points,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(176.dp)
                            )
                        } else {
                            LedgerTrendBarChart(
                                points = trend.points,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(176.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
