package com.goodyaoshi.lemonbox.ui.screen.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.LemonFab
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.rememberBottomBarClearance
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.AMOUNT_TNUM
import com.goodyaoshi.lemonbox.ui.components.AmountTnumStyle
import com.goodyaoshi.lemonbox.ui.components.LedgerAssetChip
import com.goodyaoshi.lemonbox.ui.components.HeaderActionPill
import com.goodyaoshi.lemonbox.ui.components.LedgerBudgetBar
import com.goodyaoshi.lemonbox.ui.components.LedgerMonthBar
import com.goodyaoshi.lemonbox.ui.components.LedgerSummaryCard
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SectionHeader
import com.goodyaoshi.lemonbox.ui.components.SelectionActionButton
import com.goodyaoshi.lemonbox.ui.components.SelectionTopBar
import com.goodyaoshi.lemonbox.ui.components.SkeletonList
import com.goodyaoshi.lemonbox.ui.components.SwipeActionSpec
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.ui.components.ledgerIconFor
import com.goodyaoshi.lemonbox.ui.components.ledgerSignedAmountText
import com.goodyaoshi.lemonbox.ui.components.ledgerTypeColor
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
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
    onNavigateToCategories: () -> Unit,
    onNavigateToPlan: () -> Unit,
    onNavigateToTrend: () -> Unit,
    onNavigateToAnnualReview: () -> Unit,
    onNavigateToRecordEdit: (Long?) -> Unit,
    onNavigateToItemDetail: (Long) -> Unit,
    viewModel: LedgerViewModel = hiltViewModel()
) {
    val overview by viewModel.overview.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val yearMonth by viewModel.yearMonth.collectAsState()
    val assets by viewModel.assetsWithBalance.collectAsState()
    val selectedRecordIds by viewModel.selectedRecordIds.collectAsState()
    val monthStartDay by viewModel.monthStartDay.collectAsState()
    var openedRecordId by remember { mutableStateOf<Long?>(null) }
    // 待删除流水：与资产 / 分类等页面保持一致，先弹确认再落库（I1）。
    var pendingDeleteRecord by remember { mutableStateOf<LedgerRecordUi?>(null) }
    // 批量管理（B1）：开启后点流水为勾选，配合 SelectionTopBar 批量删除。
    var selectionMode by remember { mutableStateOf(false) }
    var pendingBatchDelete by remember { mutableStateOf(false) }
    // 「更多」菜单展开态（IA-4）：低频入口收进菜单后的弹出开关。
    var moreMenuExpanded by remember { mutableStateOf(false) }
    // 账期起算日设置（ST1）：此前只有读取侧、没有写入入口，值恒为 1。
    var showMonthStartDialog by remember { mutableStateOf(false) }
    val appSnackbar = LocalAppSnackbar.current

    val dayGroups = remember(overview.records) {
        overview.records.groupBy { recordUi ->
            Instant.ofEpochMilli(recordUi.record.recordTime)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        }
    }
    val allRecordIds = remember(overview.records) {
        overview.records.map { it.record.id }.toSet()
    }
    val allSelected = allRecordIds.isNotEmpty() && selectedRecordIds.containsAll(allRecordIds)

    // 换月后选择集已不成立：清掉并退出批量模式，避免把上月勾选带到本月误删。
    LaunchedEffect(yearMonth) {
        viewModel.clearRecordSelection()
        selectionMode = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 10.dp)
            ) {
                Text(
                    text = "记账",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = "每一笔都算数",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            // 页头胶囊行（IA-4）：高频入口「预算 / 统计 / 分类」保持外露直达，低频入口（资产负债 /
            // 计划 / 趋势 / 年度）收进「更多」菜单——七枚平铺时低频功能事实不可发现，视觉噪音也
            // 挤压流水主体。收纳后常规屏四枚放得下即不滚动，极窄屏兜底仍可横滚。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderActionPill(icon = Icons.Filled.Savings, label = "预算", onClick = onNavigateToBudget)
                HeaderActionPill(icon = Icons.Filled.PieChart, label = "统计", onClick = onNavigateToStats)
                HeaderActionPill(icon = Icons.Filled.Category, label = "分类", onClick = onNavigateToCategories)
                // 「更多」胶囊：锚点 Box 同时包住胶囊与 DropdownMenu，菜单展开时贴着胶囊下方弹出；
                // 四项文案与图标沿用原七枚胶囊的定义，点选即执行对应跳转并收起菜单。
                Box {
                    HeaderActionPill(
                        icon = Icons.Filled.MoreHoriz,
                        label = "更多 ▾",
                        onClick = { moreMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("资产负债") },
                            leadingIcon = { Icon(Icons.Filled.Wallet, contentDescription = null) },
                            onClick = {
                                moreMenuExpanded = false
                                onNavigateToAssets()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("计划") },
                            leadingIcon = { Icon(Icons.Filled.Repeat, contentDescription = null) },
                            onClick = {
                                moreMenuExpanded = false
                                onNavigateToPlan()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("趋势") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null) },
                            onClick = {
                                moreMenuExpanded = false
                                onNavigateToTrend()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("年度") },
                            leadingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
                            onClick = {
                                moreMenuExpanded = false
                                onNavigateToAnnualReview()
                            }
                        )
                    }
                }
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
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = rememberBottomBarClearance(extra = 38.dp)
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "summary") {
                    AppSurfaceCard(
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                        shadowElevation = 12.dp,
                        // ST1：点金额卡下钻统计页，明细就在那边。
                        onClick = onNavigateToStats
                    ) {
                        LedgerSummaryCard(
                            expenseText = DateUtil.formatCurrency(LedgerMath.centsToYuan(overview.expenseCents)),
                            incomeText = DateUtil.formatCurrency(LedgerMath.centsToYuan(overview.incomeCents)),
                            balanceText = DateUtil.formatCurrency(
                                LedgerMath.centsToYuan(overview.incomeCents - overview.expenseCents)
                            )
                        )
                        // ST1：口径外显——「这个月」从每月几号起算，不再让人猜。
                        // 这一行同时是设置入口：点开可改起算日（此前只有读取侧、值恒为 1）。
                        Text(
                            text = "账期：每月${monthStartDay}日起 · 点击调整",
                            fontSize = 11.sp,
                            color = TextHint,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clickable { showMonthStartDialog = true }
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
                if (isLoading) {
                    item(key = "loading") {
                        // 首帧：流水还没读到，先画骨架，避免把「还没加载」误读成「本月还没有账单」。
                        SkeletonList(itemCount = 3, showHeader = false)
                    }
                } else {
                    item(key = "records-header") {
                        SectionHeader(
                            title = "本月流水",
                            subtitle = if (selectionMode) {
                                "点流水勾选，选完批量删除"
                            } else {
                                "共 ${overview.records.size} 笔，左滑可编辑或删除"
                            },
                            action = if (selectionMode) "取消" else "批量管理",
                            onActionClick = {
                                selectionMode = !selectionMode
                                if (!selectionMode) viewModel.clearRecordSelection()
                            }
                        )
                        if (selectionMode) {
                            Spacer(modifier = Modifier.height(10.dp))
                            SelectionTopBar(
                                selectedCount = selectedRecordIds.size,
                                allSelected = allSelected,
                                onToggleSelectAll = {
                                    if (allSelected) {
                                        viewModel.clearRecordSelection()
                                    } else {
                                        viewModel.selectAllRecords(allRecordIds)
                                    }
                                },
                                hint = "勾选要删除的流水，删除后仍可撤销"
                            ) {
                                SelectionActionButton(
                                    text = "删除所选",
                                    enabled = selectedRecordIds.isNotEmpty(),
                                    destructive = true,
                                    onClick = { pendingBatchDelete = true }
                                )
                            }
                        }
                    }
                    if (overview.records.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = "本月还没有账单",
                            // 去掉「点右下角」这种屏外指路（I5），直接把「记一笔」放到空态里。
                            message = "记下第一笔，收支就有了起点。",
                            actionLabel = "记一笔",
                            onAction = { onNavigateToRecordEdit(null) },
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else {
                    dayGroups.forEach { (date, records) ->
                        item(key = "day-$date") {
                            LedgerDayHeader(date = date, records = records)
                        }
                        items(records, key = { "rec-${it.record.id}" }) { recordUi ->
                            if (selectionMode) {
                                // 批量模式下点击即勾选，划滑手势让位给多选交互。
                                LedgerRecordRow(
                                    recordUi = recordUi,
                                    onOpenItem = null,
                                    onClick = { viewModel.toggleRecordSelection(recordUi.record.id) },
                                    selected = recordUi.record.id in selectedRecordIds
                                )
                            } else {
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
            }
        }

        LemonFab(
            icon = Icons.Default.Add,
            contentDescription = "记一笔",
            onClick = { onNavigateToRecordEdit(null) },
            modifier = Modifier.align(Alignment.BottomEnd),
            hasBottomBar = true
        )

        // 删除流水前的二次确认（I1）：与资产 / 分类 / 纪念日等页面的删除体验保持一致。
        pendingDeleteRecord?.let { target ->
            AppDialog(
                title = "删除流水",
                onDismissRequest = { pendingDeleteRecord = null },
                confirmText = "删除",
                destructiveConfirm = true,
                onConfirm = {
                    viewModel.deleteRecord(target.record.id)
                    // 软删除后给一次反悔机会（I10）：底部提示带「撤销」，点了就恢复。
                    appSnackbar?.showUndo(DeleteCopy.UNDO_MESSAGE, DeleteCopy.UNDO_ACTION) {
                        viewModel.restoreRecord(target.record.id)
                    }
                    pendingDeleteRecord = null
                }
            ) {
                Text(
                    text = target.categoryName
                        ?.let { "确定要删除「$it」这一笔吗？删除后仍可撤销。" }
                        ?: "确定要删除这一笔吗？删除后仍可撤销。",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        }

        // 批量删除确认（B1）：与单条删除同一闸门，撤销一次恢复全部。
        if (pendingBatchDelete) {
            val batchIds = selectedRecordIds.toList()
            AppDialog(
                title = "批量删除流水",
                onDismissRequest = { pendingBatchDelete = false },
                confirmText = "删除",
                destructiveConfirm = true,
                onConfirm = {
                    viewModel.deleteRecords(batchIds)
                    appSnackbar?.showUndo("已删除 ${batchIds.size} 笔流水", DeleteCopy.UNDO_ACTION) {
                        batchIds.forEach { viewModel.restoreRecord(it) }
                    }
                    pendingBatchDelete = false
                    selectionMode = false
                }
            ) {
                Text(
                    text = "确定要删除所选的 ${batchIds.size} 笔流水吗？删除后仍可撤销。",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        }

        // 账期起算日设置（ST1）：此前该偏好只有读取侧（7 处账期窗口计算）、没有写入入口，
        // 于是记账页一直显示「每月 1 日起」而用户无从修改。这里补上唯一的设置入口。
        if (showMonthStartDialog) {
            AppDialog(
                title = "账期从每月几号起算",
                subtitle = "影响统计、预算、趋势、年度回顾等所有按「月」聚合的口径。",
                onDismissRequest = { showMonthStartDialog = false },
                confirmText = "完成"
            ) {
                MonthStartDayPicker(
                    selected = monthStartDay,
                    onSelect = viewModel::setMonthStartDay
                )
            }
        }
    }
}

/**
 * 账期起算日选择（ST1）：1-28 的网格，点选即生效。
 *
 * 上限 28 是刻意的：29-31 在二月不存在。若允许选 31，二月就没有对应的同一天，
 * 「这个月从 31 号算起」会在个别月份失去意义。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthStartDayPicker(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        (1..28).forEach { day ->
            val isSelected = day == selected
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSelected) OrangeStart.copy(alpha = 0.14f) else OrangeTint)
                    .clickable { onSelect(day) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$day",
                    color = if (isSelected) OrangeStart else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center
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
            // 说明文字走主题字阶（F6）并提到 12sp（F7），同时启用等宽数字（P2）。
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = AMOUNT_TNUM),
            color = TextHint
        )
    }
}

/** 单条流水行：图标 + 分类/备注 + 账户 + 带符号金额（分类明细页复用）。 */
@Composable
internal fun LedgerRecordRow(
    recordUi: LedgerRecordUi,
    onOpenItem: (() -> Unit)?,
    onClick: () -> Unit,
    selected: Boolean = false
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
    val rowShape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(CardWhite)
            // 批量选中态（B1）：品牌色描边，与回收站的选中视觉同源。
            .then(if (selected) Modifier.border(2.dp, OrangeStart, rowShape) else Modifier)
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
                    // 说明文字走主题字阶（F6）并提到 12sp（F7）。
                    style = MaterialTheme.typography.bodySmall,
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
            textAlign = TextAlign.End,
            style = AmountTnumStyle
        )
    }
}
