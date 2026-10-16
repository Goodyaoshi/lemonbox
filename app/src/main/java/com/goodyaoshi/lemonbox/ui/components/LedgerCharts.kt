package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.TagBlueText
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import java.util.Locale

/**
 * 趋势图上的一个数据点：一个账期的收入/支出合计（分）。
 * 放在 components 包内，与 [LedgerPieSlice] 同层，供 ViewModel 直接产出。
 */
data class LedgerTrendPoint(
    val label: String,
    val expenseCents: Long,
    val incomeCents: Long
)

/**
 * 收支折线图：每个账期两条折线（支出青蓝实线 / 收入暖橙虚线），
 * 带 Y 轴刻度（含 ¥ 单位）、数据点标注与月份标签。
 */
@Composable
fun LedgerTrendLineChart(
    points: List<LedgerTrendPoint>,
    modifier: Modifier = Modifier
) {
    // 语义色是 @Composable getter，需在 Canvas 的绘制作用域外取好。
    val axisColor = DividerSoft
    val labelColor = TextHint
    val valueColor = TextSecondary
    val measurer = rememberTextMeasurer()
    // 字号走主题字阶（F6）：Canvas 内绘制的文字同样读令牌，不再内联 10sp/9sp。
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val valueStyle = MaterialTheme.typography.labelSmall.copy(color = valueColor)
    // 收支配色统一为「蓝橙」（P1）：红=支出/绿=收入这对约 8% 男性分不清；
    // 同时叠加线型作第二区分手段——支出实线、收入虚线，色觉障碍用户也能分辨。
    val expenseColor = TagBlueText
    val incomeColor = TagOrangeText

    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas

        val labelGap = 4.dp.toPx()
        val bottomReserve = 18.dp.toPx()
        val topReserve = 16.dp.toPx()
        val rightReserve = 8.dp.toPx()
        // 全 0 时按 1 处理，避免除零；此时折线贴基线展示，不产生 NaN。
        val maxValue = points.maxOf { maxOf(it.expenseCents, it.incomeCents) }.coerceAtLeast(1L)
        // Y 轴刻度（P1）：按最大刻度文案宽度预留左侧空间，0 与最大值两端标注、带 ¥ 单位。
        val maxTickLayout = measurer.measure(AnnotatedString(yAxisAmount(maxValue)), valueStyle)
        val chartLeft = maxTickLayout.size.width + labelGap
        val chartRight = size.width - rightReserve
        val chartTop = topReserve
        val chartBottom = size.height - bottomReserve
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop
        val count = points.size

        drawLine(
            color = axisColor,
            start = Offset(chartLeft, chartTop),
            end = Offset(chartLeft, chartBottom),
            strokeWidth = 1.dp.toPx()
        )
        drawText(
            maxTickLayout,
            topLeft = Offset(chartLeft - labelGap - maxTickLayout.size.width, chartTop)
        )
        val zeroLayout = measurer.measure(AnnotatedString(yAxisAmount(0L)), valueStyle)
        drawText(
            zeroLayout,
            topLeft = Offset(
                chartLeft - labelGap - zeroLayout.size.width,
                chartBottom - zeroLayout.size.height / 2f
            )
        )

        fun xAt(index: Int): Float = if (count == 1) {
            (chartLeft + chartRight) / 2f
        } else {
            chartLeft + chartWidth * index / (count - 1).toFloat()
        }

        fun yAt(value: Long): Float =
            chartBottom - chartHeight * (value.toFloat() / maxValue.toFloat())

        // 单点时不画连线，只在中间落一个点（避免把两点间连成横线）。
        if (count > 1) {
            val expensePath = Path()
            val incomePath = Path()
            points.forEachIndexed { index, point ->
                val x = xAt(index)
                val expY = yAt(point.expenseCents)
                val incY = yAt(point.incomeCents)
                if (index == 0) {
                    expensePath.moveTo(x, expY)
                    incomePath.moveTo(x, incY)
                } else {
                    expensePath.lineTo(x, expY)
                    incomePath.lineTo(x, incY)
                }
            }
            drawPath(expensePath, expenseColor, style = Stroke(width = 2.dp.toPx()))
            // 收入虚线（第二区分手段）。
            drawPath(
                incomePath,
                incomeColor,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                )
            )
        }

        // 数据点 + 标注 + 月份标签
        points.forEachIndexed { index, point ->
            val x = xAt(index)
            val expY = yAt(point.expenseCents)
            val incY = yAt(point.incomeCents)
            drawCircle(expenseColor, radius = 3.dp.toPx(), center = Offset(x, expY))
            drawCircle(incomeColor, radius = 3.dp.toPx(), center = Offset(x, incY))

            // 只标注支出（主口径），避免同月两个数值互相压盖；为 0 时不标注，减少噪声。
            if (point.expenseCents > 0) {
                val layout = measurer.measure(
                    AnnotatedString(yAxisAmount(point.expenseCents)),
                    valueStyle
                )
                val top = (expY - layout.size.height - 3.dp.toPx()).coerceAtLeast(0f)
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, top))
            }

            val monthLayout = measurer.measure(AnnotatedString(point.label), labelStyle)
            drawText(
                monthLayout,
                topLeft = Offset(x - monthLayout.size.width / 2f, chartBottom + 4.dp.toPx())
            )
        }
    }
}

/**
 * 收支柱状图：每个账期并列两根柱（支出青蓝实心 / 收入暖橙实心），
 * 带 Y 轴刻度（含 ¥ 单位）、基线与月份标签；两种系列靠图例文字区分。
 */
@Composable
fun LedgerTrendBarChart(
    points: List<LedgerTrendPoint>,
    modifier: Modifier = Modifier
) {
    // 语义色是 @Composable getter，需在 Canvas 的绘制作用域外取好。
    val axisColor = DividerSoft
    val labelColor = TextHint
    val measurer = rememberTextMeasurer()
    // 字号走主题字阶（F6）：Canvas 内绘制的文字同样读令牌，不再内联 10sp。
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val valueStyle = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
    // 收支配色统一为「蓝橙」（P1）：不再用红绿色对；配合下方「支出 / 收入」图例文字，
    // 第二区分手段（文字）与颜色并存，色觉障碍用户也能分辨。
    val expenseColor = TagBlueText
    val incomeColor = TagOrangeText

    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas

        val labelGap = 4.dp.toPx()
        val bottomReserve = 18.dp.toPx()
        val topReserve = 6.dp.toPx()
        // 全 0 时按 1 处理，避免除零；柱高为 0 即不绘制。
        val maxValue = points.maxOf { maxOf(it.expenseCents, it.incomeCents) }.coerceAtLeast(1L)
        // Y 轴刻度（P1）：左侧预留刻度文案宽度，0 与最大值两端标注、带 ¥ 单位，量级可读。
        val maxTickLayout = measurer.measure(AnnotatedString(yAxisAmount(maxValue)), valueStyle)
        val chartLeft = maxTickLayout.size.width + labelGap
        val chartRight = size.width
        val chartTop = topReserve
        val chartBottom = size.height - bottomReserve
        val chartHeight = chartBottom - chartTop
        val count = points.size

        drawLine(
            color = axisColor,
            start = Offset(chartLeft, chartTop),
            end = Offset(chartLeft, chartBottom),
            strokeWidth = 1.dp.toPx()
        )
        drawText(
            maxTickLayout,
            topLeft = Offset(chartLeft - labelGap - maxTickLayout.size.width, chartTop)
        )
        val zeroLayout = measurer.measure(AnnotatedString(yAxisAmount(0L)), valueStyle)
        drawText(
            zeroLayout,
            topLeft = Offset(
                chartLeft - labelGap - zeroLayout.size.width,
                chartBottom - zeroLayout.size.height / 2f
            )
        )

        val groupWidth = (chartRight - chartLeft) / count
        val barWidth = groupWidth * 0.28f
        val gap = barWidth * 0.20f
        val corner = CornerRadius(barWidth / 2f, barWidth / 2f)

        points.forEachIndexed { index, point ->
            val groupCenter = chartLeft + groupWidth * (index + 0.5f)
            val expenseHeight = chartHeight * (point.expenseCents.toFloat() / maxValue.toFloat())
            val incomeHeight = chartHeight * (point.incomeCents.toFloat() / maxValue.toFloat())
            val expenseLeft = groupCenter - gap / 2f - barWidth
            val incomeLeft = groupCenter + gap / 2f

            if (expenseHeight > 0f) {
                drawRoundRect(
                    color = expenseColor,
                    topLeft = Offset(expenseLeft, chartBottom - expenseHeight),
                    size = Size(barWidth, expenseHeight),
                    cornerRadius = corner
                )
            }
            if (incomeHeight > 0f) {
                drawRoundRect(
                    color = incomeColor,
                    topLeft = Offset(incomeLeft, chartBottom - incomeHeight),
                    size = Size(barWidth, incomeHeight),
                    cornerRadius = corner
                )
            }

            val monthLayout = measurer.measure(AnnotatedString(point.label), labelStyle)
            drawText(
                monthLayout,
                topLeft = Offset(groupCenter - monthLayout.size.width / 2f, chartBottom + 4.dp.toPx())
            )
        }
    }
}

/** 折线/柱状图共用的图例：支出（青蓝）+ 收入（暖橙），文字作第二区分手段。 */
@Composable
fun LedgerTrendLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendDot(color = TagBlueText, label = "支出")
        LegendDot(color = TagOrangeText, label = "收入")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
    }
}

/** 图表标注的紧凑金额：≥ 1 万元显示「N.N万」，否则取整到元，避免标注压盖。 */
internal fun compactTrendAmount(cents: Long): String {
    val yuan = cents / 100.0
    return when {
        cents <= 0L -> "0"
        yuan >= 10000 -> String.format(Locale.CHINA, "%.1f万", yuan / 10000)
        else -> String.format(Locale.CHINA, "%.0f", yuan)
    }
}

/** Y 轴刻度文案：紧凑金额前统一加 ¥ 单位，让图表量级可读（P1）。 */
private fun yAxisAmount(cents: Long): String = "¥" + compactTrendAmount(cents)
