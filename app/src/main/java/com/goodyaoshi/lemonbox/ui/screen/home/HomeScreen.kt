package com.goodyaoshi.lemonbox.ui.screen.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.CheckCircle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.repository.WeeklyMealDay
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.ItemActionHandlers
import com.goodyaoshi.lemonbox.ui.components.ItemCard
import com.goodyaoshi.lemonbox.ui.components.ItemMoreActionsDialog
import com.goodyaoshi.lemonbox.ui.components.LedgerBudgetBar
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SearchBar
import com.goodyaoshi.lemonbox.ui.components.SectionHeader
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.ui.components.buildItemSwipeActions
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OnLemonSoft
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TagGreen
import com.goodyaoshi.lemonbox.ui.theme.TagGreenText
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.AnniversaryRow
import com.goodyaoshi.lemonbox.ui.viewmodel.HomeViewModel
import com.goodyaoshi.lemonbox.ui.viewmodel.MonthSpending
import com.goodyaoshi.lemonbox.ui.viewmodel.TodayEvent
import com.goodyaoshi.lemonbox.ui.viewmodel.TodayEventKind
import com.goodyaoshi.lemonbox.ui.viewmodel.TodayEventTarget
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath
import java.time.LocalDate
import java.util.Calendar

/**
 * 今日页只回答三件事：今天有什么要办的、今天吃什么、这个月花了多少。
 * 物品清单交给家当、一周菜单交给吃饭，这里只做跨域的「今天」聚合。
 */
@Composable
fun HomeScreen(
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToEdit: (Long) -> Unit,
    onNavigateToExpiry: () -> Unit = {},
    onNavigateToScan: () -> Unit = {},
    onNavigateToToBuy: () -> Unit = {},
    onNavigateToReminders: () -> Unit = {},
    onNavigateToLedger: () -> Unit = {},
    onNavigateToMeal: () -> Unit = {},
    onNavigateToAnniversaries: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val allItems by viewModel.allItems.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val todayEvents by viewModel.todayEvents.collectAsState()
    val monthSpending by viewModel.monthSpending.collectAsState()
    val weekPlan by viewModel.weekPlan.collectAsState()
    val anniversaryRows by viewModel.anniversaryHighlights.collectAsState()
    var openedItemId by remember { mutableStateOf<Long?>(null) }
    var moreItemId by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteItem by remember { mutableStateOf<Item?>(null) }
    val todayKey = remember { LocalDate.now().toString() }
    val todayMeal = remember(weekPlan, todayKey) {
        weekPlan.firstOrNull { it.dateKey == todayKey }
    }

    // 一件物品的完整操作集：滑动一级动作与「更多」面板共用同一份回调。
    val handlersFor: (Item) -> ItemActionHandlers = { item ->
        ItemActionHandlers(
            onEdit = { onNavigateToEdit(item.id) },
            onMarkUsed = { viewModel.markAsUsed(item.id) },
            onConsumeOne = { viewModel.consumeOne(item.id) },
            onSetUsageStatus = { viewModel.setUsageStatus(item.id, it) },
            onSetDisposition = { viewModel.setDisposition(item.id, it) },
            onToggleRestock = { viewModel.setNeedRestock(item.id, !item.needRestock) },
            onDelete = { pendingDeleteItem = item }
        )
    }

    // 「更多」面板展示的始终是列表里的最新状态，改完能立刻反映到高亮上。
    val moreItem = moreItemId?.let { id -> allItems.firstOrNull { it.item.id == id }?.item }
    val searching = searchQuery.isNotBlank()

    val onEventClick: (TodayEvent) -> Unit = { event ->
        when (val target = event.target) {
            TodayEventTarget.Reminders -> onNavigateToReminders()
            TodayEventTarget.Expiry -> onNavigateToExpiry()
            TodayEventTarget.ToBuy -> onNavigateToToBuy()
            is TodayEventTarget.ItemDetail -> onNavigateToDetail(target.itemId)
        }
    }

    Box(
        // 点击空白处收起侧滑行的背景热区：此处刻意保留无反馈（I3 白名单例外）。
        modifier = Modifier.clickable(indication = null, interactionSource = null) {
            openedItemId = null
        }
    ) {
        AppDecorativeBackground()

        Column(modifier = Modifier.fillMaxSize()) {
            HeroHeader()

            // 固定搜索行：搜索框与扫码常驻，结果再多也不用滚回顶部改关键词。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SearchBar(
                    value = searchQuery,
                    onValueChange = viewModel::updateSearchQuery,
                    placeholder = "搜索家当、位置或备注",
                    modifier = Modifier.weight(1f),
                    showMagicIconWhenEmpty = false
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(OrangeTint)
                        .clickable(onClick = onNavigateToScan),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = "扫码",
                        tint = OrangeStart,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 110.dp)
            ) {
                if (searching) {
                item {
                    SectionHeader(
                        title = "搜索结果",
                        subtitle = "在全部家当里按名称、分类、位置与状态查找",
                        action = "共 ${searchResults.size} 件",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
                    )
                }
                if (searchResults.isEmpty()) {
                    item {
                        EmptyState(
                            title = "没有找到匹配物品",
                            message = "换个关键词试试，或者去家当用筛选条件查找。",
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else {
                    items(searchResults, key = { it.item.id }) { itemDetail ->
                        HomeItemRow(
                            itemDetail = itemDetail,
                            openedItemId = openedItemId,
                            onOpenedItemChange = { openedItemId = it },
                            handlers = handlersFor(itemDetail.item),
                            onMore = { moreItemId = itemDetail.item.id },
                            onClick = { onNavigateToDetail(itemDetail.item.id) }
                        )
                    }
                }
            } else {
                item {
                    TodaySection(
                        events = todayEvents,
                        onEventClick = onEventClick,
                        onComplete = viewModel::completeTodayEvent
                    )
                }
                if (anniversaryRows.isNotEmpty()) {
                    item {
                        AnniversarySection(
                            rows = anniversaryRows,
                            onOpen = onNavigateToAnniversaries
                        )
                    }
                }
                item {
                    TodayMealCard(
                        day = todayMeal,
                        onCooked = { viewModel.markDayCooked(todayKey) },
                        onGoPlan = onNavigateToMeal,
                        onDishItemClick = { onNavigateToDetail(it.item.id) }
                    )
                }
                monthSpending?.let { spending ->
                    item {
                        MonthSpendingBar(
                            spending = spending,
                            onClick = onNavigateToLedger
                        )
                    }
                }
            }
            }
        }

        moreItem?.let { item ->
            ItemMoreActionsDialog(
                item = item,
                handlers = handlersFor(item),
                onDismissRequest = { moreItemId = null }
            )
        }

        pendingDeleteItem?.let { item ->
            AppDialog(
                title = DeleteCopy.CONFIRM_TITLE,
                subtitle = DeleteCopy.SOFT_SUBTITLE,
                onDismissRequest = { pendingDeleteItem = null },
                confirmText = DeleteCopy.SOFT_CONFIRM,
                destructiveConfirm = true,
                onConfirm = {
                    viewModel.moveToTrash(item)
                    pendingDeleteItem = null
                }
            ) {
                Text(
                    text = "确定要删除「${item.name}」吗？",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        }
    }
}

/** 搜索结果行：左滑展示与家当页一致的一级操作。 */
@Composable
private fun HomeItemRow(
    itemDetail: ItemDetail,
    openedItemId: Long?,
    onOpenedItemChange: (Long?) -> Unit,
    handlers: ItemActionHandlers,
    onMore: () -> Unit,
    onClick: () -> Unit
) {
    SwipeRevealItem(
        itemKey = itemDetail.item.id,
        openedItemKey = openedItemId,
        onOpenedItemChange = { onOpenedItemChange(it as Long?) },
        actions = buildItemSwipeActions(
            item = itemDetail.item,
            handlers = handlers,
            onMore = onMore
        ),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
    ) { closeActions, _ ->
        ItemCard(
            itemDetail = itemDetail,
            onClick = {
                closeActions()
                onClick()
            }
        )
    }
}

@Composable
private fun HeroHeader() {
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 0..5 -> "夜深了"
            in 6..10 -> "早上好"
            in 11..13 -> "中午好"
            in 14..17 -> "下午好"
            else -> "晚上好"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(LemonStart, LemonEnd)
                ),
                shape = RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp)
            )
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
                        radius = 620f
                    ),
                    shape = RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp)
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = "$greeting，柠檬",
                color = OnLemonSoft,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "柠檬百宝箱",
                color = OnLemon,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "账、饭、家当，我都替你记着",
                color = OnLemonSoft,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 「今天的事」：提醒待办 + 临期家当 + 待买的统一清单，空态一句话，能办的直接勾掉。 */
@Composable
private fun TodaySection(
    events: List<TodayEvent>,
    onEventClick: (TodayEvent) -> Unit,
    onComplete: (TodayEvent) -> Unit
) {
    // 事项可能很多（临期/待买一次涌进来几十条），首页只展示前几条，其余折叠起来，
    // 避免把首页撑得特别长；需要时展开看全部。
    var expanded by remember { mutableStateOf(false) }
    val collapsible = events.size > TODAY_EVENT_PREVIEW_COUNT
    val visibleEvents = if (collapsible && !expanded) {
        events.take(TODAY_EVENT_PREVIEW_COUNT)
    } else {
        events
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        SectionHeader(
            title = "今天的事",
            action = if (events.isNotEmpty()) "共 ${events.size} 件" else null
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (events.isEmpty()) {
            AppSurfaceCard(
                shape = RoundedCornerShape(22.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                containerColor = CardWhite.copy(alpha = 0.72f),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(LemonEnd.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.TaskAlt,
                            contentDescription = null,
                            tint = LemonEnd,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "今天没有要紧事",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "待办提醒、临期家当和待买都会汇到这里",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        } else {
            AppSurfaceCard(
                shape = RoundedCornerShape(22.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                shadowElevation = 12.dp
            ) {
                Column {
                    visibleEvents.forEach { event ->
                        TodayEventRow(
                            event = event,
                            onClick = { onEventClick(event) },
                            onComplete = { onComplete(event) }
                        )
                    }
                    if (collapsible) {
                        TodayEventExpandRow(
                            hiddenCount = events.size - TODAY_EVENT_PREVIEW_COUNT,
                            expanded = expanded,
                            onToggle = { expanded = !expanded }
                        )
                    }
                }
            }
        }
    }
}

/** 折叠/展开「今天的事」的剩余条目。 */
@Composable
private fun TodayEventExpandRow(
    hiddenCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (expanded) "收起" else "还有 $hiddenCount 件，展开查看",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = OrangeStart
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = OrangeStart,
            modifier = Modifier
                .size(18.dp)
                .padding(start = 2.dp)
        )
    }
}

/** 单条今日事项：来源图标 + 标题/详情，能办的显示勾选钮，点整行跳对应页面。 */
@Composable
private fun TodayEventRow(
    event: TodayEvent,
    onClick: () -> Unit,
    onComplete: () -> Unit
) {
    val accent = when (event.kind) {
        TodayEventKind.TASK -> OrangeStart
        TodayEventKind.EXPIRY -> StatusExpired
        TodayEventKind.TO_BUY -> LemonEnd
    }
    val icon = when (event.kind) {
        TodayEventKind.TASK -> Icons.Default.Alarm
        TodayEventKind.EXPIRY -> Icons.Default.Notifications
        TodayEventKind.TO_BUY -> Icons.Default.ShoppingCart
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            event.detail?.let { detail ->
                Text(
                    text = detail,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        if (event.completable) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onComplete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = "完成",
                    tint = TextHint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextHint,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** 「纪念日」速览：最近的 1-2 条（倒数/生日在前，累计的随后），没有数据整块不占位。 */
@Composable
private fun AnniversarySection(
    rows: List<AnniversaryRow>,
    onOpen: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        SectionHeader(
            title = "纪念日",
            action = "全部 ›",
            onActionClick = onOpen,
            modifier = Modifier.padding(top = 12.dp, bottom = 10.dp)
        )
        AppSurfaceCard(
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
            shadowElevation = 12.dp,
            onClick = onOpen
        ) {
            Column {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(OrangeStart.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = OrangeStart,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = row.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = row.statusText,
                                fontSize = 12.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = TextHint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 「今天吃什么」精简卡：今天的搭配 + 一键做了；细排、换一道去吃饭 Tab。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayMealCard(
    day: WeeklyMealDay?,
    onCooked: () -> Unit,
    onGoPlan: () -> Unit,
    onDishItemClick: (ItemDetail) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        SectionHeader(
            title = "今天吃什么",
            subtitle = "按家里现有食材安排，缺的主料会自动进待买",
            action = "去安排 ›",
            onActionClick = onGoPlan,
            modifier = Modifier.padding(top = 12.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        AppSurfaceCard(
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            shadowElevation = 12.dp,
            onClick = onGoPlan
        ) {
            if (day == null) {
                Text(
                    text = "今天还没安排，去吃饭页看看吧",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = day.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (day.cooked) {
                        PillTag(text = "已做", backgroundColor = TagGreen, contentColor = TagGreenText)
                    } else {
                        PillTag(
                            text = "做了",
                            backgroundColor = OrangeStart,
                            contentColor = Color.White,
                            onClick = onCooked
                        )
                    }
                }
                if (day.missing.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "还缺：${day.missing.joinToString("、")}",
                        fontSize = 12.sp,
                        color = TagOrangeText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val available = day.combo.dishes
                    .flatMap { it.suggestion?.available.orEmpty() }
                if (available.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        available.take(4).forEach { (label, detail) ->
                            PillTag(
                                text = label,
                                backgroundColor = TagGreen,
                                contentColor = TagGreenText,
                                onClick = { onDishItemClick(detail) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 「本月账」速览条：已花/预算一目了然，超支转警示色；未设预算只显示已花。 */
@Composable
private fun MonthSpendingBar(
    spending: MonthSpending,
    onClick: () -> Unit
) {
    val budget = spending.budgetCents
    val over = budget != null && budget > 0 && spending.spentCents >= budget
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        SectionHeader(
            title = "本月账",
            action = "去记账 ›",
            onActionClick = onClick,
            modifier = Modifier.padding(top = 12.dp, bottom = 10.dp)
        )
        LedgerBudgetBar(
            title = when {
                over -> "本月预算已超支"
                budget != null && budget > 0 ->
                    "预算内，还剩 ${DateUtil.formatCurrency(LedgerMath.centsToYuan((budget - spending.spentCents).coerceAtLeast(0)))}"
                else -> "这个月已花 ${DateUtil.formatCurrency(LedgerMath.centsToYuan(spending.spentCents))}"
            },
            subtitle = buildString {
                append("已花 ${DateUtil.formatCurrency(LedgerMath.centsToYuan(spending.spentCents))}")
                if (budget != null && budget > 0) {
                    append(" · 预算 ${DateUtil.formatCurrency(LedgerMath.centsToYuan(budget))}")
                    if (!over) {
                        append(" · 还剩 ${DateUtil.formatCurrency(LedgerMath.centsToYuan((budget - spending.spentCents).coerceAtLeast(0)))}")
                    }
                }
            },
            ratio = if (budget != null && budget > 0) {
                spending.spentCents.toFloat() / budget
            } else {
                0f
            },
            over = over,
            onClick = onClick
        )
    }
}

/** 首页「今天的事」最多直接展示的条数，超出折叠。 */
private const val TODAY_EVENT_PREVIEW_COUNT = 5
