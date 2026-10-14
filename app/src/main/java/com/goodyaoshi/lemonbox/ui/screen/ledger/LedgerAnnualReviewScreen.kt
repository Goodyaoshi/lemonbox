package com.goodyaoshi.lemonbox.ui.screen.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AmountTnumStyle
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.LedgerTrendBarChart
import com.goodyaoshi.lemonbox.ui.components.LedgerTrendLegend
import com.goodyaoshi.lemonbox.ui.components.StatTile
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OnLemonSoft
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.StatusNormal
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.LedgerAnnualReviewUi
import com.goodyaoshi.lemonbox.ui.viewmodel.LedgerAnnualReviewViewModel
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath
import java.util.Locale

/** 年度回顾：给定年份汇总总收支/笔数/最高项/同比，并附一张全年支出柱状图。 */
@Composable
fun LedgerAnnualReviewScreen(
    onBack: () -> Unit,
    // 空态需要一条直达记账的通道，与统计页/趋势页保持一致。
    onNavigateToRecordEdit: (Long?) -> Unit,
    viewModel: LedgerAnnualReviewViewModel = hiltViewModel()
) {
    val review by viewModel.review.collectAsState()

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
                    text = "年度回顾",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            // 年份切换常驻页头下方：翻看往年时也能随手切换。
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                YearBar(
                    year = review.year,
                    canGoNext = review.canGoNext,
                    onPrev = viewModel::prevYear,
                    onNext = viewModel::nextYear
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                if (!review.hasData) {
                    EmptyState(
                        title = "${review.year}年还没有记录",
                        message = "开始记下每一笔，年底就有故事可讲。",
                        actionLabel = "记一笔",
                        onAction = { onNavigateToRecordEdit(null) },
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    AnnualHeroCard(review)

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        StatTile(
                            label = "全年支出",
                            value = yuanText(review.expenseCents),
                            modifier = Modifier.weight(1f),
                            accent = StatusExpired
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        StatTile(
                            label = "全年收入",
                            value = yuanText(review.incomeCents),
                            modifier = Modifier.weight(1f),
                            accent = StatusNormal
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        StatTile(
                            label = "记账笔数",
                            value = "${review.recordCount} 笔",
                            modifier = Modifier.weight(1f),
                            accent = OrangeStart
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        StatTile(
                            label = "日均支出",
                            value = yuanText(review.dailyAverageCents),
                            modifier = Modifier.weight(1f),
                            accent = OrangeStart
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    AppSurfaceCard(
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                        shadowElevation = 12.dp
                    ) {
                        ReviewRow(
                            label = "支出最多的月份",
                            value = review.topMonthLabel
                                ?.let { "$it · ${yuanText(review.topMonthExpenseCents)}" }
                                ?: "暂无"
                        )
                        ReviewRow(
                            label = "支出最多的分类",
                            value = review.topCategoryName
                                ?.let { "$it · ${yuanText(review.topCategoryCents)}" }
                                ?: "暂无"
                        )
                        ReviewRow(label = "与上年相比", value = deltaText(review))
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    AppSurfaceCard(
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(18.dp),
                        shadowElevation = 12.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "每月收支",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            LedgerTrendLegend()
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        LedgerTrendBarChart(
                            points = review.monthlyPoints,
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

/** 年度回顾的主视觉卡：柠檬渐变 + 全年结余。 */
@Composable
private fun AnnualHeroCard(review: LedgerAnnualReviewUi) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(colors = listOf(LemonStart, LemonEnd)))
            .drawBehind {
                // 装饰圆只画不占位，避免把卡片撑出下半截空白。
                val radius = 74.dp.toPx()
                val circleCenter = Offset(size.width - radius, radius)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
                        center = circleCenter,
                        radius = radius * 2f
                    ),
                    radius = radius * 2f,
                    center = circleCenter
                )
            }
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(OnLemon.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CalendarMonth,
                        contentDescription = null,
                        tint = OnLemon,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${review.year} 年度回顾",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = OnLemon
                    )
                    Text(
                        text = "这一年，每一笔都算数",
                        fontSize = 12.sp,
                        color = OnLemonSoft
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "全年结余",
                fontSize = 12.sp,
                color = OnLemonSoft
            )
            Text(
                text = yuanText(review.balanceCents),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = OnLemon,
                style = AmountTnumStyle,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** 亮点卡里的一行：左侧标签，右侧金额/结论。 */
@Composable
private fun ReviewRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            textAlign = TextAlign.End,
            style = AmountTnumStyle
        )
    }
}

/** 年份切换条：‹ 2026年 ›，不允许翻到未来年份。 */
@Composable
private fun YearBar(
    year: Int,
    canGoNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        YearArrow(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "上一年",
            enabled = true,
            onClick = onPrev
        )
        Text(
            text = "${year}年",
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        YearArrow(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "下一年",
            enabled = canGoNext,
            onClick = onNext
        )
    }
}

@Composable
private fun YearArrow(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (enabled) SurfaceWarmDeep else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) TextSecondary else TextHint,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun yuanText(cents: Long): String = DateUtil.formatCurrency(LedgerMath.centsToYuan(cents))

/** 同比文案：上年无记录时优雅降级，不硬凑一个百分比。 */
private fun deltaText(review: LedgerAnnualReviewUi): String {
    if (review.prevYearExpenseCents == null) return "去年还没有记录"
    if (review.prevYearExpenseCents <= 0L) return "去年没有支出记录"
    val ratio = review.expenseDeltaRatio ?: return "去年还没有记录"
    return when {
        ratio > 0.005f -> "比去年多 ${formatPercent(ratio)}"
        ratio < -0.005f -> "比去年少 ${formatPercent(-ratio)}"
        else -> "与去年基本持平"
    }
}

private fun formatPercent(ratio: Float): String =
    String.format(Locale.CHINA, "%.0f%%", ratio * 100)
