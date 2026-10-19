package com.goodyaoshi.lemonbox.ui.screen.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.ui.components.AMOUNT_TNUM
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.LedgerMonthBar
import com.goodyaoshi.lemonbox.ui.components.LedgerPieChart
import com.goodyaoshi.lemonbox.ui.components.LedgerPieSlice
import com.goodyaoshi.lemonbox.ui.components.LedgerRankRow
import com.goodyaoshi.lemonbox.ui.components.SegmentedTabs
import com.goodyaoshi.lemonbox.ui.components.categorySlot
import com.goodyaoshi.lemonbox.ui.components.categorySolidColors
import com.goodyaoshi.lemonbox.ui.components.ledgerIconFor
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.StatusNormal
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.LedgerStatSlice
import com.goodyaoshi.lemonbox.ui.viewmodel.LedgerStatsViewModel
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath

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
    // 分类配色统一走共享色槽（P2）：扇区与图例都按分类身份取色，排序变化不再换色。
    // 饼图避开红绿语义对（P2）：红绿色盲不友好，剔除 StatusExpired(红)/StatusNormal(绿)。
    val palette = categorySolidColors().filterNot { it == StatusExpired || it == StatusNormal }
    // 饼图与图例只展示占比前几名 + 「其它」（P2-17）：此前图例把全部分类再列一遍，
    // 与紧接着的「分类排行」卡重复，分类一多页面就被撑长。完整金额仍在排行卡里保留。
    val displaySlices = remember(slices) { chartSlices(slices) }
    // 百分比取整后由最大的分类吸收余数，保证各项相加恰为 100%。
    val percents = remember(displaySlices) { percentLabels(displaySlices) }

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                // 宽屏限宽（P3-14）：600dp 以上平板不再把内容拉成整屏宽，居中留出阅读边距。
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = 600.dp)
                .fillMaxWidth()
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
                    text = "记账统计",
                    // 字号走主题字阶（F6）：titleLarge 为 18sp。
                    style = MaterialTheme.typography.titleLarge,
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
                        // 圆角走形状令牌（F6）：extraLarge 为 24dp。
                        shape = MaterialTheme.shapes.extraLarge,
                        contentPadding = PaddingValues(18.dp),
                        shadowElevation = 12.dp
                    ) {
                        // 顶部对齐（P2-10）：图例条数多时不再把饼图竖直居中，避免饼图上下留白。
                        Row(verticalAlignment = Alignment.Top) {
                            Box(contentAlignment = Alignment.Center) {
                                LedgerPieChart(
                                    slices = displaySlices.map { slice ->
                                        LedgerPieSlice(
                                            value = slice.amountCents,
                                            color = palette[categorySlot(slice.categoryId, slice.name, palette.size)]
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
                                        // 字号走主题字阶（F6）：titleSmall 为 14sp。
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        textAlign = TextAlign.Center,
                                        // 金额启用等宽数字（P2）：多行/切换月份时个位对齐不跳动。
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontFeatureSettings = AMOUNT_TNUM
                                        )
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    // 图例限高可滚动（P2-10）：此前图例每条 48dp 最小热区，
                                    // 分类一多就把 Row 撑得远高于饼图、居中后饼图上下出现大片空白，
                                    // 卡片也被拉很高。这里把图例高度收敛到与饼图相当并允许滚动，
                                    // 每条仍是 48dp 可点热区（下方 minimumInteractiveComponentSize）。
                                    .heightIn(max = 150.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                displaySlices.forEachIndexed { index, slice ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            // 触达面积达标（P2）：图例行高补足 48dp 最小热区。
                                            .minimumInteractiveComponentSize()
                                            .clickable {
                                                onOpenCategory(kind, slice.categoryId, monthKey)
                                            }
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(
                                                    palette[categorySlot(slice.categoryId, slice.name, palette.size)],
                                                    CircleShape
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = slice.name,
                                            // 字号走主题字阶（F6）：labelMedium 为 12sp。
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TextSecondary,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${percents.getOrElse(index) { 0 }}%",
                                            // 标签文字走主题字阶（F6）并提到 12sp（F7）。
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TextHint
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    AppSurfaceCard(
                        // 圆角走形状令牌（F6）：extraLarge 为 24dp。
                        shape = MaterialTheme.shapes.extraLarge,
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

/**
 * 饼图 / 图例的展示切片（P2-17）：只取占比前 [limit] 名，其余合并成一条「其它」，
 * 使图例不再与下方「分类排行」重复列全量，分类一多时页面也不被撑长。
 * 「其它」的 categoryId 置空，取色 key 单一，不会散成多条同名不同色。
 */
private fun chartSlices(slices: List<LedgerStatSlice>, limit: Int = 5): List<LedgerStatSlice> {
    if (slices.size <= limit) return slices
    val top = slices.take(limit)
    val rest = slices.drop(limit)
    return top + LedgerStatSlice(
        categoryId = null,
        name = "其它",
        icon = null,
        amountCents = rest.sumOf { it.amountCents },
        ratio = rest.sumOf { it.ratio.toDouble() }.toFloat()
    )
}

/**
 * 各分类占比的整数百分比：先截断取整，再把余数补给排在最前（占比最大）的分类，
 * 使所有百分比相加恰为 100%，避免出现「合计 99% / 101%」的对不上账。
 */
private fun percentLabels(slices: List<LedgerStatSlice>): List<Int> {
    if (slices.isEmpty()) return emptyList()
    val labels = slices.map { (it.ratio * 100).toInt() }.toMutableList()
    val remainder = 100 - labels.sum()
    labels[0] = (labels[0] + remainder).coerceAtLeast(0)
    return labels
}
