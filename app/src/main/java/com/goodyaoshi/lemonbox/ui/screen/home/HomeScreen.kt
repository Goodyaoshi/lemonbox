package com.goodyaoshi.lemonbox.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.meal.BASE_SOUP_LABEL
import com.goodyaoshi.lemonbox.data.meal.BASE_STAPLES
import com.goodyaoshi.lemonbox.data.meal.DishRole
import com.goodyaoshi.lemonbox.data.meal.MealDish
import com.goodyaoshi.lemonbox.data.meal.MealDishSpec
import com.goodyaoshi.lemonbox.data.meal.MealSpec
import com.goodyaoshi.lemonbox.data.meal.Recipe
import com.goodyaoshi.lemonbox.ui.viewmodel.WeeklyMealDay
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorSectionLabel
import com.goodyaoshi.lemonbox.ui.components.EditorSelectionChip
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.ItemActionHandlers
import com.goodyaoshi.lemonbox.ui.components.ItemCard
import com.goodyaoshi.lemonbox.ui.components.ItemMoreActionsDialog
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SearchBar
import com.goodyaoshi.lemonbox.ui.components.SectionHeader
import com.goodyaoshi.lemonbox.ui.components.SegmentedTabs
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.ui.components.buildItemSwipeActions
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OnLemonSoft
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TagBlue
import com.goodyaoshi.lemonbox.ui.theme.TagBlueText
import com.goodyaoshi.lemonbox.ui.theme.TagGreen
import com.goodyaoshi.lemonbox.ui.theme.TagGreenText
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TagRed
import com.goodyaoshi.lemonbox.ui.theme.TagRedText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.HomeViewModel
import java.time.LocalDate
import java.util.Calendar

/**
 * 首页只回答三件事：有什么需要我现在处理、今天吃什么、以及去哪里看全部物品。
 * 物品清单与筛选都交给家当，避免同一份内容在多处重复出现。
 */
@Composable
fun HomeScreen(
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToEdit: (Long) -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToExpiry: () -> Unit = {},
    onNavigateToScan: () -> Unit = {},
    onNavigateToToBuy: () -> Unit = {},
    onNavigateToReminders: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val allItems by viewModel.allItems.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val expiringCount by viewModel.expiringCount.collectAsState()
    val toBuyCount by viewModel.toBuyCount.collectAsState()
    val todosTodayCount by viewModel.todosTodayCount.collectAsState()
    val mealPrepDays by viewModel.mealPrepDays.collectAsState()
    val weekPlan by viewModel.weekPlan.collectAsState()
    val recipeLibrary by viewModel.recipeLibrary.collectAsState()
    val planDays by viewModel.planDays.collectAsState()
    var openedItemId by remember { mutableStateOf<Long?>(null) }
    var moreItemId by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteItem by remember { mutableStateOf<Item?>(null) }
    var editingMealDateKey by remember { mutableStateOf<String?>(null) }
    var showPlanDaysDialog by remember { mutableStateOf(false) }
    var prepReminderDateKey by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

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

    Box(
        modifier = Modifier.clickable(indication = null, interactionSource = null) {
            openedItemId = null
        }
    ) {
        AppDecorativeBackground()

        LazyColumn(
            contentPadding = PaddingValues(bottom = 110.dp)
        ) {
            item {
                HeroHeader(
                    searchQuery = searchQuery,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onOpenScan = onNavigateToScan,
                    // 与家当页默认的「在库」视角保持同一口径。
                    libraryItemCount = allItems.count {
                        it.item.disposition == Item.DISPOSITION_IN_STOCK &&
                            it.item.usageStatus != Item.USAGE_USED_UP
                    },
                    onOpenLibrary = onNavigateToLibrary
                )
            }

            if (searching) {
                item {
                    SectionHeader(
                        title = "搜索结果",
                        subtitle = "在全部物品里按名称、分类、位置与状态查找",
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
                    val showExpiring = expiringCount > 0
                    val showToBuy = toBuyCount > 0
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp)
                    ) {
                        if (todosTodayCount > 0) {
                            ReminderEntryCard(
                                count = todosTodayCount,
                                onClick = onNavigateToReminders
                            )
                            if (showExpiring || showToBuy) {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                        if (showExpiring || showToBuy) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (showExpiring) {
                                    AttentionCard(
                                        title = "即将过期",
                                        count = expiringCount,
                                        unit = "件",
                                        description = "趁新鲜先安排",
                                        icon = Icons.Default.Notifications,
                                        accent = StatusExpired,
                                        modifier = Modifier.weight(1f),
                                        onClick = onNavigateToExpiry
                                    )
                                }
                                if (showToBuy) {
                                    AttentionCard(
                                        title = "待买清单",
                                        count = toBuyCount,
                                        unit = "件",
                                        description = "该补货了",
                                        icon = Icons.Default.ShoppingCart,
                                        accent = LemonEnd,
                                        modifier = Modifier.weight(1f),
                                        onClick = onNavigateToToBuy
                                    )
                                }
                            }
                        } else if (todosTodayCount == 0) {
                            AllClearRow()
                        }
                    }
                }

                if (weekPlan.isNotEmpty()) {
                    item {
                        WeeklyMenuSection(
                            days = weekPlan,
                            planDays = planDays,
                            onReroll = viewModel::rerollDay,
                            onCooked = viewModel::markDayCooked,
                            onEdit = { dateKey -> editingMealDateKey = dateKey },
                            onChangeDays = { showPlanDaysDialog = true },
                            onPrepReminder = { dateKey -> prepReminderDateKey = dateKey },
                            mealPrepDays = mealPrepDays,
                            onItemClick = { onNavigateToDetail(it.item.id) }
                        )
                    }
                }

                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        AppSurfaceCard(
                            shape = RoundedCornerShape(22.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                            shadowElevation = 12.dp,
                            onClick = onNavigateToLibrary
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
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
                                        imageVector = Icons.Default.Inventory2,
                                        contentDescription = null,
                                        tint = OrangeStart,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "浏览家当",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "全部物品都在这里，可以按状态、分类、位置自由筛选",
                                        fontSize = 12.sp,
                                        color = TextSecondary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = TextHint,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showPlanDaysDialog) {
            MealPlanDaysDialog(
                current = planDays,
                onDismiss = { showPlanDaysDialog = false },
                onConfirm = { days ->
                    viewModel.setPlanDays(days)
                    showPlanDaysDialog = false
                }
            )
        }

        editingMealDateKey?.let { dateKey ->
            weekPlan.firstOrNull { it.dateKey == dateKey }?.let { day ->
                MealEditorSheet(
                    day = day,
                    recipes = recipeLibrary,
                    onDismiss = { editingMealDateKey = null },
                    onSave = { spec ->
                        viewModel.saveDayMeal(dateKey, spec)
                        editingMealDateKey = null
                    }
                )
            }
        }

        prepReminderDateKey?.let { dateKey ->
            viewModel.mealPrepTitle(dateKey)?.let { suggestedTitle ->
                MealPrepDialog(
                    dateKey = dateKey,
                    suggestedTitle = suggestedTitle,
                    onDismiss = { prepReminderDateKey = null },
                    onConfirm = { dayShift, fireTime ->
                        viewModel.createMealPrepReminder(dateKey, dayShift, fireTime) { saved ->
                            if (!saved) {
                                Toast.makeText(
                                    context,
                                    "这个时间已经过了，换个时间吧",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        prepReminderDateKey = null
                    }
                )
            } ?: run { prepReminderDateKey = null }
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
                title = "确认删除",
                subtitle = "删除后会先移入回收站，30 天内仍可恢复。",
                onDismissRequest = { pendingDeleteItem = null },
                confirmText = "移入回收站",
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
private fun HeroHeader(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenScan: () -> Unit,
    libraryItemCount: Int,
    onOpenLibrary: () -> Unit
) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$greeting，柠檬",
                    color = OnLemonSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                PillTag(
                    text = "家当 $libraryItemCount 件 ›",
                    backgroundColor = OnLemon.copy(alpha = 0.16f),
                    contentColor = OnLemon,
                    onClick = onOpenLibrary
                )
            }
            Text(
                text = "柠檬百宝箱",
                color = OnLemon,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "家里的每件东西，都有它的位置",
                color = OnLemonSoft,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SearchBar(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = "搜索物品、位置或备注",
                    modifier = Modifier.weight(1f),
                    showMagicIconWhenEmpty = false
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(OnLemon.copy(alpha = 0.16f))
                        .clickable(onClick = onOpenScan),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = "扫码",
                        tint = OnLemon,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** 未来 N 天菜谱：顶部横向日期 Tabs，下面用双列网格展示选中那天的一餐；点「做了」自动扣当天食材。 */
@Composable
private fun WeeklyMenuSection(
    days: List<WeeklyMealDay>,
    planDays: Int,
    onReroll: (String) -> Unit,
    onCooked: (String) -> Unit,
    onEdit: (String) -> Unit,
    onChangeDays: () -> Unit,
    onPrepReminder: (String) -> Unit,
    mealPrepDays: Set<String>,
    onItemClick: (ItemDetail) -> Unit
) {
    if (days.isEmpty()) return
    var selectedIndex by remember { mutableIntStateOf(0) }
    val index = selectedIndex.coerceIn(0, days.lastIndex)
    val day = days[index]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (planDays == 7) "未来一周菜谱" else "未来 $planDays 天菜谱",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "默认搭配：米饭 + 白粥 + 清炒时蔬 + 一道蛋白菜，默认不配汤饮；点日期可自行编辑，点「做了」扣掉当天食材（调料不扣）",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            PillTag(
                text = "$planDays 天",
                backgroundColor = SurfaceWarmDeep,
                contentColor = OrangeStart,
                onClick = onChangeDays
            )
        }

        // 横向滚动的日期 Tabs：点哪一天，下面就切到哪一天的菜品。
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(days, key = { _, item -> item.dateKey }) { itemIndex, item ->
                MealDayTab(
                    day = item,
                    selected = itemIndex == index,
                    onClick = { selectedIndex = itemIndex }
                )
            }
        }

        // 内容区：双列网格渲染该日期对应的菜品列表。
        DayMealGrid(day = day, onItemClick = onItemClick)

        DayMealActionRow(
            day = day,
            hasPrepReminder = day.dateKey in mealPrepDays,
            onCooked = { onCooked(day.dateKey) },
            onReroll = { onReroll(day.dateKey) },
            onEdit = { onEdit(day.dateKey) },
            onPrepReminder = { onPrepReminder(day.dateKey) }
        )
    }
}

/** 日期 Tab：上排「今天/周三」，下排「10/04」。 */
@Composable
private fun MealDayTab(
    day: WeeklyMealDay,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) OrangeStart else SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = day.dayLabel,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else TextSecondary
        )
        Text(
            text = day.shortDate,
            fontSize = 11.sp,
            color = if (selected) Color.White.copy(alpha = 0.85f) else TextHint,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

/** 一餐菜品：双列网格，每行两道，落单的补一个空位保持左右对齐。 */
@Composable
private fun DayMealGrid(
    day: WeeklyMealDay,
    onItemClick: (ItemDetail) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        day.combo.dishes.chunked(2).forEach { rowDishes ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowDishes.forEach { dish ->
                    MealDishCard(
                        dish = dish,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onItemClick = onItemClick
                    )
                }
                if (rowDishes.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 网格里的一道菜：角色标签 + 菜名 + 食材齐否；有现成食材时点标签可跳到物品。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealDishCard(
    dish: MealDish,
    modifier: Modifier = Modifier,
    onItemClick: (ItemDetail) -> Unit
) {
    val available = dish.suggestion?.available.orEmpty()
    val missing = dish.suggestion?.missing.orEmpty()
    val expiring = dish.suggestion?.expiring == true

    AppSurfaceCard(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(12.dp),
        containerColor = CardWhite.copy(alpha = 0.8f),
        shadowElevation = 6.dp
    ) {
        Text(
            text = dish.displayRoles.joinToString("·") { it.label },
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = dish.label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 2
        )
        Spacer(modifier = Modifier.height(6.dp))
        when {
            // 基础项（米饭/白粥/清汤）：家里默认就有，不参与食材匹配，也不扣食材。
            dish.recipeId == null -> PillTag(
                text = "基础",
                backgroundColor = TagBlue,
                contentColor = TagBlueText
            )
            expiring -> PillTag(
                text = "有临期食材",
                backgroundColor = TagRed,
                contentColor = TagRedText
            )
            missing.isEmpty() -> PillTag(
                text = "食材齐",
                backgroundColor = TagGreen,
                contentColor = TagGreenText
            )
            else -> Text(
                text = "缺：${missing.joinToString("、")}",
                fontSize = 11.sp,
                color = TagOrangeText,
                maxLines = 2
            )
        }
        if (available.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                available.take(3).forEach { (label, detail) ->
                    PillTag(
                        text = label,
                        backgroundColor = TagGreen,
                        contentColor = TagGreenText,
                        onClick = { onItemClick(detail) }
                    )
                }
            }
        }
    }
}

/** 选中那天的操作行：做了 / 提醒准备 / 编辑一餐 / 换一道，并提示还缺几样主料。 */
@Composable
private fun DayMealActionRow(
    day: WeeklyMealDay,
    hasPrepReminder: Boolean,
    onCooked: () -> Unit,
    onReroll: () -> Unit,
    onEdit: () -> Unit,
    onPrepReminder: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (day.missing.isEmpty()) "现有食材够做这一餐" else "缺 ${day.missing.size} 样主料",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        if (day.cooked) {
            PillTag(text = "已做", backgroundColor = TagGreen, contentColor = TagGreenText)
        } else {
            // 备菜提醒（如提前一晚解冻肉）：一天一条，生成后显示「已提醒」。
            PillTag(
                text = if (hasPrepReminder) "已提醒" else "提醒准备",
                backgroundColor = if (hasPrepReminder) TagGreen else SurfaceWarmDeep,
                contentColor = if (hasPrepReminder) TagGreenText else TextSecondary,
                onClick = if (hasPrepReminder) null else onPrepReminder
            )
            Spacer(modifier = Modifier.width(6.dp))
            PillTag(
                text = "做了",
                backgroundColor = OrangeStart,
                contentColor = Color.White,
                onClick = onCooked
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        PillTag(
            text = "编辑一餐",
            backgroundColor = SurfaceWarmDeep,
            contentColor = TextSecondary,
            onClick = onEdit
        )
        if (!day.cooked) {
            Spacer(modifier = Modifier.width(6.dp))
            PillTag(
                text = "换一道",
                backgroundColor = SurfaceWarmDeep,
                contentColor = TextSecondary,
                onClick = onReroll
            )
        }
    }
}

/** 自定义计划天数：常用档位一键切换，也可用加减微调（1..30 天）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealPlanDaysDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var days by remember(current) { mutableIntStateOf(current) }
    AppDialog(
        title = "计划天数",
        subtitle = "从今天起排几天的菜（1-30 天）；改完会自动补齐新的一天或裁剪多余的天。",
        onDismissRequest = onDismiss,
        confirmText = "保存",
        onConfirm = { onConfirm(days) }
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PLAN_DAY_PRESETS.forEach { preset ->
                EditorSelectionChip(
                    text = "$preset 天",
                    selected = days == preset,
                    onClick = { days = preset }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "自定义天数", fontSize = 13.sp, color = TextSecondary)
            Spacer(modifier = Modifier.weight(1f))
            PlanDayStepButton(text = "−") {
                days = (days - 1).coerceAtLeast(MIN_PLAN_DAYS)
            }
            Text(
                text = "$days",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            PlanDayStepButton(text = "+") {
                days = (days + 1).coerceAtMost(MAX_PLAN_DAYS)
            }
        }
    }
}

@Composable
private fun PlanDayStepButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceWarmDeep)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = OrangeStart)
    }
}

/** 计划天数的常用档位。 */
private val PLAN_DAY_PRESETS = listOf(3, 5, 7, 10, 14)

/** 计划天数的边界。 */
private const val MIN_PLAN_DAYS = 1
private const val MAX_PLAN_DAYS = 30

/** 手动编辑某天的一餐：底部弹层里按角色 Tabs 挑菜，避免一屏平铺太多菜谱选不到。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MealEditorSheet(
    day: WeeklyMealDay,
    recipes: List<Recipe>,
    onDismiss: () -> Unit,
    onSave: (MealSpec) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selection by remember(day.dateKey) {
        mutableStateOf(
            day.combo.dishes.map { dish ->
                MealDishSpec(dish.role.name, dish.recipeId, dish.label)
            }
        )
    }
    var tabIndex by remember { mutableIntStateOf(0) }

    // 一餐覆盖到的角色取并集：多角色菜（饺子等）能一次补齐多个角色。
    val covered = selection.flatMap { spec ->
        spec.recipeId?.let { id -> recipes.firstOrNull { it.id == id }?.dishRoles }
            ?: setOf(spec.dishRole)
    }.toSet()
    val complete = DishRole.STAPLE in covered && DishRole.PROTEIN in covered
    val options = remember(recipes, tabIndex) {
        dishOptions(MealPickTab.entries[tabIndex].role, recipes)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(CardWhite)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp, bottom = 20.dp)
        ) {
            Text(
                text = "${day.dayLabel}吃什么",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "主食 + 蛋白必配，蔬菜、汤饮可选；一道菜可顶多个角色（如饺子既是主食也是蛋白和蔬菜）。",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))
            EditorSectionLabel(
                label = "已选（点一下移除）",
                tag = if (complete) "搭配完整" else "还缺主食/蛋白"
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (selection.isEmpty()) {
                Text(text = "还没选菜，到下面按分类挑一道吧。", fontSize = 12.sp, color = TextHint)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selection.forEach { spec ->
                        EditorSelectionChip(
                            text = "${spec.label} ✕",
                            selected = true,
                            onClick = { selection = selection - spec }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DishRole.entries.forEach { role ->
                    PillTag(
                        text = role.label,
                        backgroundColor = if (role in covered) TagGreen else SurfaceWarmDeep,
                        contentColor = if (role in covered) TagGreenText else TextHint
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            SegmentedTabs(
                labels = MealPickTab.entries.map { it.label },
                selectedIndex = tabIndex,
                onSelect = { tabIndex = it }
            )

            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (options.isEmpty()) {
                    Text(
                        text = "菜谱库里还没有「${MealPickTab.entries[tabIndex].label}」菜谱，可到菜谱页添加。",
                        fontSize = 12.sp,
                        color = TextHint
                    )
                } else {
                    options.forEach { option ->
                        val selected = selection.any { it.matches(option) }
                        EditorSelectionChip(
                            text = option.label,
                            selected = selected,
                            onClick = {
                                selection = if (selected) {
                                    selection.filterNot { it.matches(option) }
                                } else {
                                    selection + MealDishSpec(option.role.name, option.recipeId, option.label)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SheetActionButton(
                    text = "取消",
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                SheetActionButton(
                    text = if (complete) "保存" else "还缺主食/蛋白",
                    modifier = Modifier.weight(1f),
                    enabled = complete,
                    highlight = complete,
                    onClick = { onSave(MealSpec(selection)) }
                )
            }
        }
    }
}

/** 编辑弹层里的角色 Tabs：全部 + 四个配餐角色。 */
private enum class MealPickTab(val label: String, val role: DishRole?) {
    ALL("全部", null),
    STAPLE("主食", DishRole.STAPLE),
    PROTEIN("蛋白", DishRole.PROTEIN),
    VEGETABLE("蔬菜", DishRole.VEGETABLE),
    SOUP("汤饮", DishRole.SOUP)
}

/** 可挑选的一道菜：菜谱（[recipeId] 非空）或基础主食/汤饮（[recipeId] 为空）。 */
private data class DishOption(
    val role: DishRole,
    val recipeId: Long?,
    val label: String
)

/** 某角色可挑的菜品：菜谱库里覆盖该角色的菜 + 主食/汤饮的基础兜底。 */
private fun dishOptions(role: DishRole?, recipes: List<Recipe>): List<DishOption> {
    val fromRecipes = recipes
        .filter { role == null || role in it.dishRoles }
        .map { DishOption(it.dishRoles.first(), it.id, it.name) }
    // 基础主食若与用户自建菜谱重名，只保留菜谱那份，避免选项重复。
    val recipeNames = recipes.map { it.name }.toSet()
    val bases = buildList {
        if (role == null || role == DishRole.STAPLE) {
            BASE_STAPLES.forEach { name ->
                if (name !in recipeNames) add(DishOption(DishRole.STAPLE, null, name))
            }
        }
        if (role == null || role == DishRole.SOUP) {
            add(DishOption(DishRole.SOUP, null, BASE_SOUP_LABEL))
        }
    }
    return fromRecipes + bases
}

/** 已选菜与候选菜是否是同一道：菜谱按 id 比，基础项按名称比。 */
private fun MealDishSpec.matches(option: DishOption): Boolean =
    recipeId == option.recipeId && label == option.label

/** 弹层底部按钮：高亮为主操作，禁用时置灰。 */
@Composable
private fun SheetActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    !enabled -> SurfaceWarmDeep
                    highlight -> OrangeStart
                    else -> SurfaceWarmDeep
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                !enabled -> TextHint
                highlight -> Color.White
                else -> TextSecondary
            }
        )
    }
}

/** 需要行动的概览卡：只在真的有临期 / 待买时出现，避免用 0 值占据首屏。 */
@Composable
private fun AttentionCard(
    title: String,
    count: Int,
    unit: String,
    description: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    AppSurfaceCard(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        shadowElevation = 12.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = "$count",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    Text(
                        text = unit,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
                    )
                }
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
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
        }
    }
}

/** 没有临期也没有待买时的轻量提示，不占点击目标。 */
@Composable
private fun AllClearRow() {
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
                    text = "一切正常",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = "暂无临期或待买，安心囤着吧",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** 首页的家务提醒入口：今天（含错过未触发）要做几件事，点开进提醒页逐件完成。 */
@Composable
private fun ReminderEntryCard(
    count: Int,
    onClick: () -> Unit
) {
    AppSurfaceCard(
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        shadowElevation = 12.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    imageVector = Icons.Default.Alarm,
                    contentDescription = null,
                    tint = OrangeStart,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "家务提醒 · 今天 $count 件",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = "解冻肉、洗衣服这些要紧事，点开逐件完成",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextHint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 备菜提醒弹窗：为某天的一餐挑提醒时机（前一天晚上 / 当天早上），
 * 文案按蛋白菜自动预填（带冻肉的提示解冻），可手改。
 * 解冻要么提前一晚、要么当天早上，当天晚上再提醒就来不及了，所以不提供该选项。
 * 当天的一餐不再提供「前一天晚上」。
 */
@Composable
private fun MealPrepDialog(
    dateKey: String,
    suggestedTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (dayShift: Int, fireTime: String) -> Unit
) {
    var title by remember(dateKey) { mutableStateOf(suggestedTitle) }
    val isToday = remember(dateKey) { dateKey == LocalDate.now().toString() }
    var dayShift by remember(dateKey) { mutableIntStateOf(if (isToday) 0 else -1) }
    var fireTime by remember(dateKey) { mutableStateOf(if (isToday) "08:00" else "19:00") }

    AppDialog(
        title = "提醒准备",
        subtitle = "到点会发一条通知，提前把菜准备好。",
        onDismissRequest = onDismiss,
        confirmText = "好的",
        confirmEnabled = title.isNotBlank(),
        onConfirm = { onConfirm(dayShift, fireTime) }
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("提醒内容") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!isToday) {
                EditorSelectionChip(
                    text = "前一天晚上 19:00",
                    selected = dayShift == -1,
                    onClick = {
                        dayShift = -1
                        fireTime = "19:00"
                    }
                )
            }
            EditorSelectionChip(
                text = "当天早上 08:00",
                selected = dayShift == 0 && fireTime == "08:00",
                onClick = {
                    dayShift = 0
                    fireTime = "08:00"
                }
            )
        }
    }
}