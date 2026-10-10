package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.StatusInfo
import com.goodyaoshi.lemonbox.ui.theme.StatusNormal
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

// ============================================================
// 记账图标目录：key 与数据库 ledger_categories.icon / ledger_assets.icon 对应，
// 种子数据（SeedDataProvider.ensureLedgerSeedData）写入的 key 必须都在这里。
// ============================================================

data class LedgerIconOption(
    val key: String,
    val icon: ImageVector
)

val ledgerIconOptions: List<LedgerIconOption> = listOf(
    LedgerIconOption("restaurant", Icons.Filled.Restaurant),
    LedgerIconOption("shopping", Icons.Filled.ShoppingBag),
    LedgerIconOption("home", Icons.Filled.Home),
    LedgerIconOption("transport", Icons.Filled.DirectionsCar),
    LedgerIconOption("entertainment", Icons.Filled.SportsEsports),
    LedgerIconOption("house", Icons.Filled.Apartment),
    LedgerIconOption("medical", Icons.Filled.MedicalServices),
    LedgerIconOption("other", Icons.Filled.Category),
    LedgerIconOption("salary", Icons.Filled.Work),
    LedgerIconOption("redpacket", Icons.Filled.CardGiftcard),
    LedgerIconOption("invest", Icons.Filled.TrendingUp),
    LedgerIconOption("wechat", Icons.Filled.Chat),
    LedgerIconOption("alipay", Icons.Filled.AccountBalanceWallet),
    LedgerIconOption("cash", Icons.Filled.Payments),
    LedgerIconOption("bank", Icons.Filled.AccountBalance)
)

private val ledgerIconMap: Map<String, ImageVector> =
    ledgerIconOptions.associate { it.key to it.icon }

/** 按字符串 key 取记账图标；空或未知时回退到「其他」。 */
fun ledgerIconFor(key: String?): ImageVector =
    key?.let { ledgerIconMap[it] } ?: Icons.Filled.Category

// ============================================================
// 共用小组件
// ============================================================

/** 月份切换条：‹ 2026年10月 ›。 */
@Composable
fun LedgerMonthBar(
    year: Int,
    month: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MonthArrow(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "上个月",
            onClick = onPrev
        )
        Text(
            text = "${year}年${month}月",
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        MonthArrow(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "下个月",
            onClick = onNext
        )
    }
}

@Composable
private fun MonthArrow(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(SurfaceWarmDeep)
            .clickable(
                interactionSource = interactionSource,
                // 月份切换箭头恢复默认涟漪（I3）。
                indication = LocalIndication.current,
                // 纯图标按钮声明 Button 角色，读屏才会播报「按钮」（F16）。
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            // 箭头是唯一的可点内容，没有文字兜底，必须给出描述（F16）。
            contentDescription = contentDescription,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 收支汇总：本月支出 / 收入 / 结余。 */
@Composable
fun LedgerSummaryCard(
    expenseText: String,
    incomeText: String,
    balanceText: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LedgerSummaryItem("本月支出", expenseText, StatusExpired, Modifier.weight(1f))
        LedgerSummaryItem("本月收入", incomeText, StatusNormal, Modifier.weight(1f))
        LedgerSummaryItem("结余", balanceText, OrangeStart, Modifier.weight(1f))
    }
}

@Composable
private fun LedgerSummaryItem(label: String, value: String, accent: Color, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = TextSecondary, fontSize = 12.sp)
        Text(
            text = value,
            color = accent,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

/** 预算进度条：超支转警示红。 */
@Composable
fun LedgerBudgetBar(
    title: String,
    subtitle: String,
    ratio: Float,
    over: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceWarmDeep)
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = subtitle,
                color = if (over) StatusExpired else TextSecondary,
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(DividerSoft)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio.coerceIn(0.02f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (over) StatusExpired else OrangeStart)
            )
        }
    }
}

/** 账户余额胶囊（横向滑动行里的单个账户）。 */
@Composable
fun LedgerAssetChip(
    name: String,
    balanceText: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceWarmDeep)
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = OrangeStart,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = name,
                color = TextSecondary,
                // 字号走主题字阶（F6），并满足说明文字 ≥12sp（F7）。
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = balanceText,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 饼图：简单环形占比图，中心显示总额；空数据显示占位环。 */
@Composable
fun LedgerPieChart(
    slices: List<LedgerPieSlice>,
    modifier: Modifier = Modifier
) {
    // 语义色是 @Composable getter，需在 Canvas 的绘制作用域外取好。
    val emptyColor = DividerSoft
    Canvas(modifier = modifier) {
        val stroke = 34.dp.toPx()
        val inset = stroke / 2 + 2.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        if (slices.isEmpty()) {
            drawArc(
                color = emptyColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke)
            )
            return@Canvas
        }
        val total = slices.sumOf { it.value }.coerceAtLeast(1L)
        var startAngle = -90f
        slices.forEach { slice ->
            val sweep = slice.value.toFloat() / total * 360f
            drawArc(
                color = slice.color,
                startAngle = startAngle,
                sweepAngle = sweep.coerceAtLeast(0.5f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke)
            )
            startAngle += sweep
        }
    }
}

data class LedgerPieSlice(
    val value: Long,
    val color: Color
)

/** 分类排行条：图标 + 名称 + 金额 + 占比进度；onClick 非空时可点进明细。 */
@Composable
fun LedgerRankRow(
    rank: Int,
    name: String,
    amountText: String,
    ratio: Float,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(OrangeTint),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = OrangeStart,
                modifier = Modifier.size(17.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$rank. $name",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(text = amountText, color = TextPrimary, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(DividerSoft)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio.coerceIn(0.03f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(OrangeStart)
                )
            }
        }
    }
}

/**
 * 记账金额键盘：数字 + 小数点 + 四则运算符 + 退格，支持直接输入算式
 * （买多件相乘、返现相减、补运费相加等）。输入文本的拼装与求值由调用方负责
 * （配合 [com.goodyaoshi.lemonbox.util.LedgerMath.parseCentsInput]）。
 */
@Composable
fun AmountKeyboard(
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = listOf(
        listOf("1", "2", "3", "⌫"),
        listOf("4", "5", "6", "÷"),
        listOf("7", "8", "9", "×"),
        listOf(".", "0", "-", "+")
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { key ->
                    val interactionSource = remember { MutableInteractionSource() }
                    val isOperator = key in OPERATOR_KEYS
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(SurfaceWarmDeep)
                            .clickable(
                                interactionSource = interactionSource,
                                // 金额键盘按键恢复默认涟漪（I3）：此前无任何按压反馈。
                                indication = LocalIndication.current
                            ) {
                                if (key == "⌫") onBackspace() else onKey(key)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (key == "⌫") {
                            Icon(
                                imageVector = Icons.Filled.Backspace,
                                contentDescription = "退格",
                                tint = TextPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Text(
                                text = key,
                                color = if (isOperator) OrangeStart else TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = if (isOperator) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 金额键盘里的运算符键，采用主色高亮区分。 */
private val OPERATOR_KEYS = setOf("+", "-", "×", "÷")

/** 账单类型配色：支出红 / 收入绿 / 转账青。 */
fun ledgerTypeColor(type: Int): Color = when (type) {
    1 -> StatusNormal
    2 -> StatusInfo
    else -> StatusExpired
}

/** 金额展示的统一前缀。 */
const val LEDGER_CURRENCY_SYMBOL = "¥"

/** 分 → 带符号金额文本：-2500 → "-25"，流水行展示用。 */
fun ledgerSignedAmountText(type: Int, cents: Long): String {
    val yuan = cents / 100.0
    val text = if (yuan % 1.0 == 0.0) {
        yuan.toLong().toString()
    } else {
        String.format(java.util.Locale.CHINA, "%.2f", yuan)
    }
    return when (type) {
        1 -> "+$text"
        else -> "-$text"
    }
}
