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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.LedgerTrendBarChart
import com.goodyaoshi.lemonbox.ui.components.LedgerTrendLegend
import com.goodyaoshi.lemonbox.ui.components.LedgerTrendLineChart
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.LedgerTrendViewModel

/** 收支趋势：近 6 个月的收入/支出折线与月度对比柱状，全部用 Canvas 自绘。 */
@Composable
fun LedgerTrendScreen(
    onBack: () -> Unit,
    // 趋势页与统计页一致：空态需要一条直达记账的通道，从导航层传入。
    onNavigateToRecordEdit: (Long?) -> Unit,
    viewModel: LedgerTrendViewModel = hiltViewModel()
) {
    val trend by viewModel.trend.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

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
                        tint = TextPrimary
                    )
                }
                Text(
                    text = "收支趋势",
                    fontSize = 18.sp,
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
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(18.dp),
                        shadowElevation = 12.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "近 6 个月趋势",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            LedgerTrendLegend()
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        LedgerTrendLineChart(
                            points = trend.points,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(176.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    AppSurfaceCard(
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(18.dp),
                        shadowElevation = 12.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "月度收支对比",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            LedgerTrendLegend()
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        LedgerTrendBarChart(
                            points = trend.points,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
