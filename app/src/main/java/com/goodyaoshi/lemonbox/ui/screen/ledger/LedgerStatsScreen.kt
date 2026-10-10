package com.goodyaoshi.lemonbox.ui.screen.ledger

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.LedgerMonthBar
import com.goodyaoshi.lemonbox.ui.components.LedgerPieChart
import com.goodyaoshi.lemonbox.ui.components.LedgerPieSlice
import com.goodyaoshi.lemonbox.ui.components.LedgerRankRow
import com.goodyaoshi.lemonbox.ui.components.SegmentedTabs
import com.goodyaoshi.lemonbox.ui.components.ledgerIconFor
import com.goodyaoshi.lemonbox.ui.theme.LemonSlice
import com.goodyaoshi.lemonbox.ui.theme.LeafGreen
import com.goodyaoshi.lemonbox.ui.theme.LightTagPurpleText
import com.goodyaoshi.lemonbox.ui.theme.MintGreen
import com.goodyaoshi.lemonbox.ui.theme.SageAccent
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.StatusInfo
import com.goodyaoshi.lemonbox.ui.theme.StatusNormal
import com.goodyaoshi.lemonbox.ui.theme.StatusWarning
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.theme.WarmBrownSoft
import com.goodyaoshi.lemonbox.ui.viewmodel.LedgerStatsViewModel
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath
import kotlin.math.roundToInt

/** 统计饼图的固定配色（循环取色）。 */
private val statSliceColors = listOf(
    LeafGreen,
    MintGreen,
    LemonSlice,
    SageAccent,
    StatusWarning,
    StatusInfo,
    StatusExpired,
    StatusNormal,
    LightTagPurpleText,
    WarmBrownSoft
)

/** 记账统计：支出/收入切换 + 环形饼图 + 分类排行，分类可点进当月明细。 */
@Composable
fun LedgerStatsScreen(
    onBack: () -> Unit,
    onOpenCategory: (kind: Int, categoryId: Long?, monthKey: String) -> Unit,
    // 统计页自身没有记一笔入口（I5）：空态需要一条直达记账的通道，故从导航层传入。
    onNavigateToRecordEdit: (Long?) -> Unit,
    viewModel: LedgerStatsViewModel = hiltViewModel()
) {
    val stats by viewModel.stats.collectAsState()
    val yearMonth by viewModel.yearMonth.collectAsState()
    var kindIndex by remember { mutableIntStateOf(0) }

    val slices = if (kindIndex == 0) stats.expenseSlices else stats.incomeSlices
    val totalCents = if (kindIndex == 0) stats.expenseCents else stats.incomeCents
    val kindLabel = if (kindIndex == 0) "支出" else "收入"
    val kind = if (kindIndex == 0) LedgerCategory.KIND_EXPENSE else LedgerCategory.KIND_INCOME
    val monthKey = yearMonth.toString()

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
                    text = "记账统计",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            // 固定切换：月份与支出/收入常驻页头下方，图表排行再长滚动后也能切。
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                LedgerMonthBar(
                    year = yearMonth.year,
                    month = yearMonth.monthValue,
                    onPrev = viewModel::prevMonth,
                    onNext = viewModel::nextMonth
                )
                Spacer(modifier = Modifier.height(12.dp))
                SegmentedTabs(
                    labels = listOf("支出", "收入"),
                    selectedIndex = kindIndex,
                    onSelect = { kindIndex = it }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                if (slices.isEmpty()) {
                    EmptyState(
                        title = "本月还没有${kindLabel}记录",
                        // 原文案让用户「回记账页」却没有入口（I5），改为直接给按钮并改掉屏外引用。
                        message = "记一笔${kindLabel}，这里就会亮起来。",
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
                            Box(contentAlignment = Alignment.Center) {
                                LedgerPieChart(
                                    slices = slices.mapIndexed { index, slice ->
                                        LedgerPieSlice(
                                            value = slice.amountCents,
                                            color = statSliceColors[index % statSliceColors.size]
                                        )
                                    },
                                    modifier = Modifier.size(150.dp)
                                )
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = kindLabel,
                                        // 标签文字走主题字阶（F6）并提到 12sp（F7）。
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextHint
                                    )
                                    Text(
                                        text = DateUtil.formatCurrency(LedgerMath.centsToYuan(totalCents)),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                slices.take(5).forEachIndexed { index, slice ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onOpenCategory(kind, slice.categoryId, monthKey)
                                            }
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(
                                                    statSliceColors[index % statSliceColors.size],
                                                    CircleShape
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = slice.name,
                                            fontSize = 12.sp,
                                            color = TextSecondary,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${(slice.ratio * 100).roundToInt()}%",
                                            // 标签文字走主题字阶（F6）并提到 12sp（F7）。
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TextHint
                                        )
                                    }
                                }
                                if (slices.size > 5) {
                                    Text(
                                        text = "其他 ${slices.size - 5} 项分类",
                                        // 说明文字走主题字阶（F6）并提到 12sp（F7）。
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextHint,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    AppSurfaceCard(
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                        shadowElevation = 12.dp
                    ) {
                        slices.forEachIndexed { index, slice ->
                            LedgerRankRow(
                                rank = index + 1,
                                name = slice.name,
                                amountText = DateUtil.formatCurrency(LedgerMath.centsToYuan(slice.amountCents)),
                                ratio = slice.ratio,
                                icon = ledgerIconFor(slice.icon),
                                modifier = Modifier.padding(vertical = 6.dp),
                                onClick = { onOpenCategory(kind, slice.categoryId, monthKey) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
