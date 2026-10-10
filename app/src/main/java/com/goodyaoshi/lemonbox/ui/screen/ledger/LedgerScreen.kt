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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_EXPENSE
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_INCOME
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_TRANSFER
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.LedgerAssetChip
import com.goodyaoshi.lemonbox.ui.components.HeaderActionPill
import com.goodyaoshi.lemonbox.ui.components.LedgerBudgetBar
import com.goodyaoshi.lemonbox.ui.components.LedgerMonthBar
import com.goodyaoshi.lemonbox.ui.components.LedgerSummaryCard
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SectionHeader
import com.goodyaoshi.lemonbox.ui.components.SwipeActionSpec
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.ui.components.ledgerIconFor
import com.goodyaoshi.lemonbox.ui.components.ledgerSignedAmountText
import com.goodyaoshi.lemonbox.ui.components.ledgerTypeColor
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.SageAccent
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.LedgerRecordUi
import com.goodyaoshi.lemonbox.ui.viewmodel.LedgerViewModel
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 记账主 Tab：月份切换 → 收支汇总 → 预算 → 账户 → 按日分组流水，FAB 记一笔。 */
@Composable
fun LedgerScreen(
    onNavigateToStats: () -> Unit,
    onNavigateToBudget: () -> Unit,
    onNavigateToAssets: () -> Unit,
    onNavigateToRecordEdit: (Long?) -> Unit,
    onNavigateToItemDetail: (Long) -> Unit,
    viewModel: LedgerViewModel = hiltViewModel()
) {
    val overview by viewModel.overview.collectAsState()
    val yearMonth by viewModel.yearMonth.collectAsState()
    val assets by viewModel.assetsWithBalance.collectAsState()
    var openedRecordId by remember { mutableStateOf<Long?>(null) }
    // 待删除流水：与资产 / 分类等页面保持一致，先弹确认再落库（I1）。
    var pendingDeleteRecord by remember { mutableStateOf<LedgerRecordUi?>(null) }

    val dayGroups = remember(overview.records) {
        overview.records.groupBy { recordUi ->
            Instant.ofEpochMilli(recordUi.record.recordTime)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        }
    }

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
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "记账",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "每一笔都算数",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                HeaderActionPill(icon = Icons.Filled.Savings, label = "预算", onClick = onNavigateToBudget)
                Spacer(modifier = Modifier.width(8.dp))
                HeaderActionPill(icon = Icons.Filled.PieChart, label = "统计", onClick = onNavigateToStats)
                Spacer(modifier = Modifier.width(8.dp))
                HeaderActionPill(icon = Icons.Filled.Wallet, label = "资产", onClick = onNavigateToAssets)
            }

            // 固定月份条：翻流水时也能随时换月（与统计页一致，不嵌在滚动列表里）。
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                LedgerMonthBar(
                    year = yearMonth.year,
                    month = yearMonth.monthValue,
                    onPrev = viewModel::prevMonth,
                    onNext = viewModel::nextMonth
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 132.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "summary") {
                    AppSurfaceCard(
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                        shadowElevation = 12.dp
                    ) {
                        LedgerSummaryCard(
                            expenseText = DateUtil.formatCurrency(LedgerMath.centsToYuan(overview.expenseCents)),
                            incomeText = DateUtil.formatCurrency(LedgerMath.centsToYuan(overview.incomeCents)),
                            balanceText = DateUtil.formatCurrency(
                                LedgerMath.centsToYuan(overview.incomeCents - overview.expenseCents)
                            )
                        )
                    }
                }
                item(key = "budget") {
                    val totalBudget = overview.totalBudgetCents
                    val totalSpent = overview.expenseCents
                    LedgerBudgetBar(
                        title = "月度总预算",
                        subtitle = if (totalBudget == null) {
                            "还没设置，点这里去设一个"
                        } else {
                            "已花 ${DateUtil.formatCurrency(LedgerMath.centsToYuan(totalSpent))} / " +
                                DateUtil.formatCurrency(LedgerMath.centsToYuan(totalBudget))
                        },
                        ratio = if (totalBudget == null) 0f else totalSpent.toFloat() / totalBudget,
                        over = totalBudget != null && totalSpent > totalBudget,
                        onClick = onNavigateToBudget
                    )
                }
                if (assets.isNotEmpty()) {
                    item(key = "assets") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            assets.forEach { asset ->
                                LedgerAssetChip(
                                    name = asset.name,
                                    balanceText = DateUtil.formatCurrency(LedgerMath.centsToYuan(asset.balance)),
                                    icon = ledgerIconFor(asset.icon)
                                )
                            }
                        }
                    }
                }
                item(key = "records-header") {
                    SectionHeader(
                        title = "本月流水",
                        subtitle = "共 ${overview.records.size} 笔，左滑可编辑或删除"
                    )
                }
                if (overview.records.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = "本月还没有账单",
                            message = "点右下角的「+」记下第一笔吧。",
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else {
                    dayGroups.forEach { (date, records) ->
                        item(key = "day-$date") {
                            LedgerDayHeader(date = date, records = records)
                        }
                        items(records, key = { "rec-${it.record.id}" }) { recordUi ->
                            SwipeRevealItem(
                                itemKey = recordUi.record.id,
                                openedItemKey = openedRecordId,
                                onOpenedItemChange = { openedRecordId = it as Long? },
                                actions = listOf(
                                    SwipeActionSpec(
                                        label = "编辑",
                                        icon = Icons.Filled.Edit,
                                        backgroundColor = SageAccent,
                                        onClick = { onNavigateToRecordEdit(recordUi.record.id) }
                                    ),
                                    SwipeActionSpec(
                                        label = "删除",
                                        icon = Icons.Filled.Delete,
                                        backgroundColor = StatusExpired,
                                        // 不直接删除，改为记录待删项并弹出确认（I1）。
                                        onClick = { pendingDeleteRecord = recordUi }
                                    )
                                )
                            ) { _, _ ->
                                LedgerRecordRow(
                                    recordUi = recordUi,
                                    onOpenItem = recordUi.record.itemId
                                        ?.let { itemId -> { onNavigateToItemDetail(itemId) } },
                                    onClick = { onNavigateToRecordEdit(recordUi.record.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 118.dp)
                .size(58.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(colors = listOf(LemonStart, LemonEnd))
                )
                .clickable { onNavigateToRecordEdit(null) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "记一笔",
                tint = OnLemon,
                modifier = Modifier.size(28.dp)
            )
        }

        // 删除流水前的二次确认（I1）：与资产 / 分类 / 纪念日等页面的删除体验保持一致。
        pendingDeleteRecord?.let { target ->
            AppDialog(
                title = "删除流水",
                onDismissRequest = { pendingDeleteRecord = null },
                confirmText = "删除",
                destructiveConfirm = true,
                onConfirm = {
                    viewModel.deleteRecord(target.record.id)
                    pendingDeleteRecord = null
                }
            ) {
                Text(
                    text = target.categoryName
                        ?.let { "确定要删除「$it」这一笔吗？删除后不可恢复。" }
                        ?: "确定要删除这一笔吗？删除后不可恢复。",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        }
    }
}

/** 按日分组的日期行：左侧日期，右侧当日收支小计。 */
@Composable
private fun LedgerDayHeader(
    date: LocalDate,
    records: List<LedgerRecordUi>
) {
    val weekNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val expense = records.filter { it.record.type == TYPE_EXPENSE }.sumOf { it.record.amount }
    val income = records.filter { it.record.type == TYPE_INCOME }.sumOf { it.record.amount }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${date.monthValue}月${date.dayOfMonth}日 ${weekNames[date.dayOfWeek.value - 1]}",
            fontSize = 12.sp,
            color = TextHint
        )
        Spacer(modifier = Modifier.weight(1f))
        val parts = buildList {
            if (expense > 0) add("-${DateUtil.formatCurrency(LedgerMath.centsToYuan(expense))}")
            if (income > 0) add("+${DateUtil.formatCurrency(LedgerMath.centsToYuan(income))}")
        }
        Text(
            text = parts.joinToString("  "),
            fontSize = 11.sp,
            color = TextHint
        )
    }
}

/** 单条流水行：图标 + 分类/备注 + 账户 + 带符号金额（分类明细页复用）。 */
@Composable
internal fun LedgerRecordRow(
    recordUi: LedgerRecordUi,
    onOpenItem: (() -> Unit)?,
    onClick: () -> Unit
) {
    val record = recordUi.record
    val title = when (record.type) {
        TYPE_TRANSFER -> "转账"
        else -> recordUi.categoryName ?: "未分类"
    }
    val subtitle = when (record.type) {
        TYPE_TRANSFER -> listOfNotNull(
            recordUi.assetName,
            recordUi.targetAssetName?.let { "→ $it" }
        ).joinToString(" ")
        else -> listOfNotNull(
            record.remark.takeIf { it.isNotBlank() },
            recordUi.assetName
        ).joinToString(" · ")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardWhite)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(OrangeTint),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (record.type == TYPE_TRANSFER) Icons.Filled.SwapHoriz else ledgerIconFor(recordUi.categoryIcon),
                contentDescription = null,
                tint = ledgerTypeColor(record.type),
                modifier = Modifier.size(20.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1
                )
                if (recordUi.linkedItemName != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    PillTag(
                        text = "家当",
                        backgroundColor = TagOrange,
                        contentColor = TagOrangeText,
                        onClick = onOpenItem
                    )
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextHint,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Text(
            text = ledgerSignedAmountText(record.type, record.amount),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = ledgerTypeColor(record.type),
            textAlign = TextAlign.End
        )
    }
}
