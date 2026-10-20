package com.goodyaoshi.lemonbox.ui.screen.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath
import kotlin.math.roundToLong
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.LemonFab
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.rememberBottomBarClearance
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.HierarchicalPickerDialog
import com.goodyaoshi.lemonbox.ui.components.ItemActionHandlers
import com.goodyaoshi.lemonbox.ui.components.ItemCard
import com.goodyaoshi.lemonbox.ui.components.ItemMoreActionsDialog
import com.goodyaoshi.lemonbox.ui.components.SearchBar
import com.goodyaoshi.lemonbox.ui.components.SelectionActionButton
import com.goodyaoshi.lemonbox.ui.components.SelectionTopBar
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.ui.components.TabPageHeader
import com.goodyaoshi.lemonbox.ui.components.TreeNode
import com.goodyaoshi.lemonbox.ui.components.buildItemSwipeActions
import com.goodyaoshi.lemonbox.ui.components.buildTreePathLabel
import com.goodyaoshi.lemonbox.ui.components.itemStatusColors
import com.goodyaoshi.lemonbox.ui.components.statusDimensionTitle
import com.goodyaoshi.lemonbox.ui.components.statusOptionsFor
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TagRed
import com.goodyaoshi.lemonbox.ui.theme.TagRedText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.LibraryFilter
import com.goodyaoshi.lemonbox.ui.viewmodel.LibraryGrouping
import com.goodyaoshi.lemonbox.ui.viewmodel.LibraryPreset
import com.goodyaoshi.lemonbox.ui.viewmodel.HouseholdViewModel
import com.goodyaoshi.lemonbox.ui.viewmodel.UserFilterPreset

/** 面板里需要弹出树形选择器的归属维度。 */
private enum class PickerTarget { CATEGORY, LOCATION }

/**
 * 快捷筛选胶囊的展示顺序：默认「在库」排第一，「全部」放最后；
 * 「已离手」不进胶囊（低频），走筛选面板的「物品去向」。
 */
private val presetChipOrder = listOf(
    LibraryPreset.IN_STOCK,
    LibraryPreset.TO_BUY,
    LibraryPreset.USED_UP,
    LibraryPreset.ALL
)

/**
 * 家当 Tab 根页（IA-5）：原 SearchScreen 按承载职责更名，
 * 物品浏览、筛选、编辑、回收站、待买、到期管理都在这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdScreen(
    // 首页搜索回车带词跳转（IA-2）：进入时把关键词接进本页搜索框，复用同一套筛选。
    initialQuery: String = "",
    // 带词跳入时提供返回动线（返回上一页）；作为 Tab 根进入时不显示返回箭头。
    onBack: (() -> Unit)? = null,
    // 是否显示返回箭头由导航层显式传入：原先用「onBack 非空 + 词非空」就地推断，
    // 判定条件散落在页面内，导航侧一改就失配（首页带空词跳入时不显示返回箭头即源于此）。
    showBackArrow: Boolean = false,
    onNavigateToDetail: (Long, LibraryFilter) -> Unit,
    onNavigateToEdit: (Long) -> Unit,
    onNavigateToSave: () -> Unit = {},
    onNavigateToCategory: () -> Unit = {},
    onNavigateToToBuy: () -> Unit = {},
    onNavigateToExpiry: () -> Unit = {},
    onNavigateToTrash: () -> Unit = {},
    viewModel: HouseholdViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsState()
    // 仅在带词进入时灌一次：之后用户在本页改词不再受首页关键词影响。
    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) viewModel.updateQuery(initialQuery)
    }
    val results by viewModel.results.collectAsState()
    val allItems by viewModel.allItems.collectAsState()
    // 家当总览（对齐极简记物的资产统计）：只算真正在库、未删除的物品。
    // needRestock 的待买项在数据层也是 Item（usageStatus=USED_UP），不能算进「在库」，
    // 否则页头数字会和下方「在库」筛选胶囊对不上。
    val inStockItems = remember(allItems) {
        allItems.filter {
            it.item.deletedAt == null &&
                it.item.disposition == Item.DISPOSITION_IN_STOCK &&
                it.item.usageStatus != Item.USAGE_USED_UP
        }
    }
    val totalValueCents = remember(inStockItems) {
        inStockItems.sumOf { detail ->
            val price = detail.item.price ?: return@sumOf 0L
            (price * 100.0).roundToLong() * detail.item.quantity.coerceAtLeast(0)
        }
    }
    // 极简记物的核心指标是「日均成本」；这里只用现有字段（价格、购买日、使用天数）算，
    // 不为它新增字段。按件消耗品没有「持有天数」概念，不参与日均。
    val dailyCost = remember(inStockItems) {
        inStockItems.sumOf { detail ->
            val item = detail.item
            if (item.trackMode != Item.TRACK_DURABLE) return@sumOf 0.0
            val days = Item.usageDays(
                startUseTime = item.startUseTime,
                purchaseDate = item.purchaseDate,
                createdAt = item.createdAt,
                usageEndedAt = item.usageEndedAt,
                usageStatus = item.usageStatus,
                disposition = item.disposition
            )
            if (days <= 0) 0.0 else Item.averageDailyCost(item.price, item.quantity, days) ?: 0.0
        }
    }
    val filter by viewModel.filter.collectAsState()
    val presetCounts by viewModel.presetCounts.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val locations by viewModel.locations.collectAsState()
    // 家当域的计数（临期 / 回收站）与入口同源，均在本页 ViewModel（I9）。
    // 待买数量已经由筛选胶囊承载，不再在页头重复一枚入口。
    val expiringCount by viewModel.expiringCount.collectAsState()
    // 批量模式（GAP-7）：整理大扫除时逐个滑动太碎，接入选中即批量处理的既有范式。
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    var openedItemId by remember { mutableStateOf<Long?>(null) }
    var moreItemId by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteItem by remember { mutableStateOf<Item?>(null) }
    val appSnackbar = LocalAppSnackbar.current
    var showFilterSheet by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var pickerTarget by remember { mutableStateOf<PickerTarget?>(null) }
    // 用户自定义预设（SE1）：点击应用、长按删除；删除前确认，删完可撤销。
    val userPresets by viewModel.userFilterPresets.collectAsState()
    var pendingDeletePreset by remember { mutableStateOf<UserFilterPreset?>(null) }

    // 一件物品的完整操作集：滑动一级动作与「更多」面板共用同一份回调。
    // G-3：凡改变数据的动作，落库后都给一条可撤销的回执——
    // 撤销不重放反向逻辑，而是把动作前的整份快照写回，行为可预期。
    val handlersFor: (Item) -> ItemActionHandlers = { item ->
        ItemActionHandlers(
            onEdit = { onNavigateToEdit(item.id) },
            onMarkUsed = {
                viewModel.markAsUsed(item.id)
                appSnackbar?.showUndo("已记「${item.name}」用完，并加入待买", DeleteCopy.UNDO_ACTION) {
                    viewModel.undoQuickAction(item)
                }
            },
            onConsumeOne = {
                viewModel.consumeOne(item.id)
                // 减到 0 时仓库会自动记「用完」并入待买，这里统一按「用掉 1 件」播报。
                appSnackbar?.showUndo("已用掉 1 件「${item.name}」", DeleteCopy.UNDO_ACTION) {
                    viewModel.undoQuickAction(item)
                }
            },
            onSetUsageStatus = { status ->
                // 目标状态与当前相同就不落库、也不发空回执（G-3 守卫）。
                if (status != item.usageStatus) {
                    viewModel.setUsageStatus(item.id, status)
                    val message = if (status == Item.USAGE_IN_USE) {
                        "已开始使用「${item.name}」"
                    } else {
                        "已更新「${item.name}」的使用状态"
                    }
                    appSnackbar?.showUndo(message, DeleteCopy.UNDO_ACTION) {
                        viewModel.undoQuickAction(item)
                    }
                }
            },
            onSetDisposition = { disposition ->
                if (disposition != item.disposition) {
                    viewModel.setDisposition(item.id, disposition)
                    appSnackbar?.showUndo("已更新「${item.name}」的去向", DeleteCopy.UNDO_ACTION) {
                        viewModel.undoQuickAction(item)
                    }
                }
            },
            onToggleRestock = {
                viewModel.setNeedRestock(item.id, !item.needRestock)
                val message = if (item.needRestock) {
                    "已把「${item.name}」移出待买"
                } else {
                    "已把「${item.name}」加入待买"
                }
                appSnackbar?.showUndo(message, DeleteCopy.UNDO_ACTION) {
                    viewModel.undoQuickAction(item)
                }
            },
            onDelete = { pendingDeleteItem = item }
        )
    }

    // 「更多」面板展示的始终是家当里的最新状态，改完能立刻反映到高亮上。
    val moreItem = moreItemId?.let { id -> allItems.firstOrNull { it.item.id == id }?.item }

    val currentPreset = remember(filter) { LibraryPreset.of(filter) }
    val categoryPath = remember(categories, filter.categoryId) {
        buildTreePathLabel(
            filter.categoryId,
            categories.map { TreeNode(id = it.id, parentId = it.parentId, name = it.name) }
        )
    }
    val locationPath = remember(locations, filter.locationId) {
        buildTreePathLabel(
            filter.locationId,
            locations.map { TreeNode(id = it.id, parentId = it.parentId, name = it.name) }
        )
    }
    /** 有关键词时分组没有意义，强制平铺。 */
    val effectiveGrouping = if (query.isBlank()) filter.grouping else LibraryGrouping.NONE
    val sections = remember(results, effectiveGrouping) {
        buildSections(results, effectiveGrouping)
    }
    // 已选条件与筛选胶囊放在同一行：页头不再出现第二条横向滚动区。
    val activeChips = buildActiveChips(
        filter = filter,
        preset = currentPreset,
        categoryPath = categoryPath,
        locationPath = locationPath,
        onClearUsage = { viewModel.clearUsageStatuses() },
        onClearDisposition = { viewModel.clearDispositions() },
        onClearRestock = { viewModel.setNeedRestockOnly(false) },
        onClearCategory = { viewModel.selectCategory(null) },
        onClearLocation = { viewModel.selectLocation(null) },
        onClearGrouping = { viewModel.setGrouping(LibraryGrouping.NONE) }
    )

    val itemRow: @Composable (ItemDetail) -> Unit = { itemDetail ->
        if (isSelectionMode) {
            // 批量模式（GAP-7）：整卡点击即切换勾选，侧滑与「更多」暂时收起（在 ItemCard 内处理），
            // 避免与勾选手势打架；退出批量模式后恢复逐条交互。
            ItemCard(
                itemDetail = itemDetail,
                onClick = { viewModel.toggleSelection(itemDetail.item.id) },
                selectionSelected = itemDetail.item.id in selectedIds
            )
        } else {
            SwipeRevealItem(
                itemKey = itemDetail.item.id,
                openedItemKey = openedItemId,
                onOpenedItemChange = { openedItemId = it as Long? },
                actions = buildItemSwipeActions(
                    item = itemDetail.item,
                    handlers = handlersFor(itemDetail.item),
                    onMore = { moreItemId = itemDetail.item.id }
                )
            ) { closeActions, _ ->
                ItemCard(
                    itemDetail = itemDetail,
                    onClick = {
                        closeActions()
                        onNavigateToDetail(itemDetail.item.id, filter)
                    },
                    // I8：行尾「更多」按钮同样打开完整操作面板，作为滑动之外的可见入口。
                    onMore = { moreItemId = itemDetail.item.id }
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 点击空白处收起侧滑行的背景热区：此处刻意保留无反馈（I3 白名单例外）。
            .clickable(indication = null, interactionSource = null) {
                openedItemId = null
            }
    ) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 6.dp)
        ) {
            HouseholdTopBar(
                showBackArrow = showBackArrow,
                onBack = onBack,
                expiringCount = expiringCount,
                showOverflow = showOverflow,
                onShowOverflow = { showOverflow = it },
                onOpenExpiry = onNavigateToExpiry,
                onOpenCategory = onNavigateToCategory,
                onOpenTrash = onNavigateToTrash
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = rememberBottomBarClearance(extra = 38.dp)
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "search") {
                    SearchBar(
                        value = query,
                        onValueChange = viewModel::updateQuery,
                        placeholder = "搜索物品、分类、位置或备注",
                        showMagicIconWhenEmpty = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item(key = "filters") {
                    HouseholdFilterRow(
                        filter = filter,
                        currentPreset = currentPreset,
                        presetCounts = presetCounts,
                        userPresets = userPresets,
                        activeChips = activeChips,
                        onOpenFilter = { showFilterSheet = true },
                        onSelectPreset = viewModel::selectPreset,
                        onApplyUserPreset = viewModel::applyUserPreset,
                        onDeleteUserPreset = { pendingDeletePreset = it },
                        onSavePreset = {
                            viewModel.saveCurrentFilterAsPreset()
                            appSnackbar?.postMessage("筛选已存成预设")
                        },
                        onResetFilter = viewModel::resetFilter
                    )
                }
                if (inStockItems.isNotEmpty()) {
                    item(key = "summary") {
                        HouseholdSummaryLine(
                            totalValueCents = totalValueCents,
                            dailyCost = dailyCost
                        )
                    }
                }

                if (results.isEmpty()) {
                    item(key = "empty") {
                        HouseholdEmptyState(
                            allItemsEmpty = allItems.isEmpty(),
                            query = query,
                            currentPreset = currentPreset,
                            presetCounts = presetCounts,
                            onNavigateToSave = onNavigateToSave,
                            onNavigateToToBuy = onNavigateToToBuy,
                            onSelectPreset = viewModel::selectPreset,
                            onReset = {
                                viewModel.updateQuery("")
                                viewModel.resetFilter()
                            }
                        )
                    }
                } else {
                    if (isSelectionMode) {
                        // 批量模式（GAP-7）：页头换成选择操作条；全选口径跟随当前筛选结果，
                        // 与待买 / 回收站的批量范式保持一致。
                        item(key = "selection") {
                            SelectionTopBar(
                                selectedCount = selectedIds.size,
                                allSelected = results.isNotEmpty() &&
                                    results.all { it.item.id in selectedIds },
                                onToggleSelectAll = {
                                    if (results.all { it.item.id in selectedIds }) {
                                        viewModel.clearSelection()
                                    } else {
                                        viewModel.selectAll(results.map { it.item.id }.toSet())
                                    }
                                },
                                hint = "勾选物品，一次批量处理"
                            ) {
                                SelectionActionButton(
                                    text = "加入待买",
                                    modifier = Modifier.weight(1f),
                                    enabled = selectedIds.isNotEmpty(),
                                    highlight = true,
                                    onClick = {
                                        viewModel.addSelectedToBuy { snapshots ->
                                            appSnackbar?.showUndo(
                                                "已把 ${snapshots.size} 件加入待买",
                                                DeleteCopy.UNDO_ACTION
                                            ) {
                                                viewModel.undoBatchAction(snapshots)
                                            }
                                        }
                                    }
                                )
                                SelectionActionButton(
                                    text = "移入回收站",
                                    modifier = Modifier.weight(1f),
                                    enabled = selectedIds.isNotEmpty(),
                                    destructive = true,
                                    onClick = {
                                        viewModel.moveSelectedToTrash { snapshots ->
                                            appSnackbar?.showUndo(
                                                DeleteCopy.UNDO_MESSAGE,
                                                DeleteCopy.UNDO_ACTION
                                            ) {
                                                viewModel.undoBatchAction(snapshots)
                                            }
                                        }
                                    }
                                )
                                SelectionActionButton(
                                    text = "取消",
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.exitSelectionMode() }
                                )
                            }
                        }
                    } else {
                        item(key = "results-header") {
                            HouseholdResultsHeader(
                                title = if (query.isNotBlank()) {
                                    "搜索结果"
                                } else {
                                    currentPreset?.label ?: "自定义筛选"
                                },
                                count = results.size,
                                onBatch = {
                                    openedItemId = null
                                    viewModel.enterSelectionMode()
                                }
                            )
                        }
                    }
                    if (sections.isEmpty()) {
                        items(results, key = { it.item.id }) { itemDetail ->
                            itemRow(itemDetail)
                        }
                    } else {
                        sections.forEach { section ->
                            item(key = "section_${section.title}") {
                                HouseholdResultsHeader(
                                    title = section.title,
                                    count = section.items.size,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            items(section.items, key = { it.item.id }) { itemDetail ->
                                itemRow(itemDetail)
                            }
                        }
                    }
                }
            }
        }

        // 录入家当入口：原底栏中央按钮移到这里，与记账页 FAB 同款。
        // 批量模式下隐藏：此刻的交互是「处理已有物品」，录入会打断批量动线。
        if (!isSelectionMode) {
            LemonFab(
                icon = Icons.Default.Add,
                // 文案与目的页标题对齐（P3-5）：落地页叫「快速录入」，入口就用同一个词。
                contentDescription = "快速录入",
                onClick = {
                    openedItemId = null
                    onNavigateToSave()
                },
                modifier = Modifier.align(Alignment.BottomEnd),
                hasBottomBar = true
            )
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
                    // 软删除后给一次反悔机会（I10）：底部提示带「撤销」，点了就恢复。
                    appSnackbar?.showUndo(DeleteCopy.UNDO_MESSAGE, DeleteCopy.UNDO_ACTION) {
                        viewModel.restoreFromTrash(item.id)
                    }
                    pendingDeleteItem = null
                }
            ) {
                Text(
                    text = "确定要删除「${item.name}」吗？",
                    color = TextSecondary,
                    // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        pendingDeletePreset?.let { preset ->
            // SE1：长按删预设也先确认，删完还能从提示里撤销；只动预设不动家当数据。
            AppDialog(
                title = "删除预设",
                subtitle = "「${preset.label}」将从预设胶囊里移除。",
                onDismissRequest = { pendingDeletePreset = null },
                confirmText = "删除",
                destructiveConfirm = true,
                onConfirm = {
                    viewModel.deleteUserPreset(preset.id)
                    appSnackbar?.showUndo("已删除预设「${preset.label}」", DeleteCopy.UNDO_ACTION) {
                        viewModel.restoreUserPreset(preset)
                    }
                    pendingDeletePreset = null
                }
            ) {
                Text(
                    text = "只删除这个筛选预设，不影响任何家当数据。",
                    color = TextSecondary,
                    // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    if (showFilterSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState,
            containerColor = Color.Transparent,
            dragHandle = null
        ) {
            LibraryFilterSheetContent(
                filter = filter,
                categoryPath = categoryPath,
                locationPath = locationPath,
                onToggleUsageStatus = viewModel::toggleUsageStatus,
                onToggleDisposition = viewModel::toggleDisposition,
                onSetNeedRestockOnly = viewModel::setNeedRestockOnly,
                onPickCategory = { pickerTarget = PickerTarget.CATEGORY },
                onPickLocation = { pickerTarget = PickerTarget.LOCATION },
                onSetGrouping = viewModel::setGrouping,
                onReset = viewModel::resetFilter,
                onDone = { showFilterSheet = false }
            )
        }
    }

    pickerTarget?.let { target ->
        val isCategory = target == PickerTarget.CATEGORY
        HierarchicalPickerDialog(
            title = if (isCategory) "筛选分类" else "筛选存放位置",
            nodes = if (isCategory) {
                categories.map { TreeNode(id = it.id, parentId = it.parentId, name = it.name) }
            } else {
                locations.map { TreeNode(id = it.id, parentId = it.parentId, name = it.name) }
            },
            selectedId = if (isCategory) filter.categoryId else filter.locationId,
            onSelect = { id ->
                if (isCategory) viewModel.selectCategory(id) else viewModel.selectLocation(id)
            },
            onDismissRequest = { pickerTarget = null },
            emptyHint = if (isCategory) "还没有可筛选的分类。" else "还没有可筛选的位置。"
        )
    }
}

/** 一个可移除的已选条件。 */
private data class ActiveCondition(
    val label: String,
    val onRemove: () -> Unit
)

/**
 * 已选条件行：预设胶囊已经表达清楚的状态维度不重复展示，
 * 只补上自定义状态组合、分类、位置与分组这些预设解释不了的条件。
 */
private fun buildActiveChips(
    filter: LibraryFilter,
    preset: LibraryPreset?,
    categoryPath: String?,
    locationPath: String?,
    onClearUsage: () -> Unit,
    onClearDisposition: () -> Unit,
    onClearRestock: () -> Unit,
    onClearCategory: () -> Unit,
    onClearLocation: () -> Unit,
    onClearGrouping: () -> Unit
): List<ActiveCondition> {
    val chips = mutableListOf<ActiveCondition>()
    if (preset == null) {
        if (filter.usageStatuses.isNotEmpty()) {
            chips += ActiveCondition("进度 ${filter.usageStatuses.size} 项", onClearUsage)
        }
        if (filter.dispositions.isNotEmpty()) {
            chips += ActiveCondition("去向 ${filter.dispositions.size} 项", onClearDisposition)
        }
        if (filter.needRestockOnly) {
            chips += ActiveCondition("只看待买", onClearRestock)
        }
    }
    categoryPath?.let { chips += ActiveCondition("分类：$it", onClearCategory) }
    locationPath?.let { chips += ActiveCondition("位置：$it", onClearLocation) }
    if (filter.grouping != LibraryGrouping.NONE) {
        chips += ActiveCondition(filter.grouping.label, onClearGrouping)
    }
    return chips
}

@Composable
private fun ActiveConditionChip(label: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            // 触达面积达标（F7/P2-6）：热区对齐 48dp（视觉尺寸不变）。
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .background(OrangeStart.copy(alpha = 0.12f))
            // 整枚胶囊就是「移除该条件」，声明 Button 角色（F8）。
            .clickable(role = Role.Button, onClick = onRemove)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            // 字号走主题字阶（F6）：labelMedium 为 12sp。
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = OrangeStart
        )
        Spacer(modifier = Modifier.size(4.dp))
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "移除该条件",
            tint = OrangeStart,
            modifier = Modifier.size(13.dp)
        )
    }
}

@Composable
private fun FilterEntryChip(
    activeCount: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box {
        AppSurfaceCard(
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
            containerColor = if (selected) OrangeStart.copy(alpha = 0.12f) else CardWhite,
            shadowElevation = 8.dp,
            onClick = onClick
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = if (selected) OrangeStart else TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "筛选",
                    // 字号走主题字阶（F6）：labelLarge 为 14sp。
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) OrangeStart else TextPrimary
                )
            }
        }
        if (activeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 5.dp, y = (-5).dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    // 用语义标签红（随主题切换）替代原先写死的 0xFFE65B5B：
                    // 深色模式下自动转为提亮的红底 + 深色数字，避免浅底刺眼。
                    .background(TagRedText),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = activeCount.toString(),
                    // 数量角标走主题字阶（F6）：labelSmall 为 11sp，是标签字号下限（F7）。
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TagRed
                )
            }
        }
    }
}

/** 预设胶囊：内置预设显示计数；用户自定义预设（SE1）不显示计数、支持长按删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetChip(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    // P3-8：为带删除手势的胶囊补一段说明（如「X，长按可删除」），
    // 让隐藏手势可被读屏发现；内置预设不传，保持原样。
    contentDescription: String? = null
) {
    AppSurfaceCard(
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        containerColor = if (selected) OrangeStart.copy(alpha = 0.12f) else CardWhite,
        shadowElevation = 8.dp,
        // 需要长按手势时接管到内容层 combinedClickable，整卡点击与长按才不冲突。
        onClick = if (onLongClick == null) onClick else null
    ) {
        val rowModifier = if (onLongClick != null) {
            Modifier.combinedClickable(
                onClickLabel = "应用该预设",
                role = Role.Button,
                onLongClickLabel = "长按可删除",
                onLongClick = onLongClick,
                onClick = onClick
            )
        } else {
            Modifier
        }
        // P3-8：带删除手势的胶囊额外声明说明，读屏能读出「长按可删除」。
        val describedModifier = contentDescription?.let { text ->
            Modifier.semantics { this.contentDescription = text }
        } ?: Modifier
        Row(
            modifier = rowModifier.then(describedModifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                // 字号走主题字阶（F6）：labelLarge 为 14sp。
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) OrangeStart else TextPrimary
            )
            if (count != null) {
                Spacer(modifier = Modifier.size(5.dp))
                Text(
                    text = count.toString(),
                    // 字号走主题字阶（F6）：labelMedium 为 12sp。
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) OrangeStart else TextHint
                )
            }
        }
    }
}

/** 统一的筛选面板：状态维度可多选，归属维度走树形选择，分组方式也作为其中一项。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryFilterSheetContent(
    filter: LibraryFilter,
    categoryPath: String?,
    locationPath: String?,
    onToggleUsageStatus: (Int) -> Unit,
    onToggleDisposition: (Int) -> Unit,
    onSetNeedRestockOnly: (Boolean) -> Unit,
    onPickCategory: () -> Unit,
    onPickLocation: () -> Unit,
    onSetGrouping: (LibraryGrouping) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(CardWhite)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "筛选条件",
                    // 字号走主题字阶（F6）：titleLarge 为 18sp。
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Text(
                    text = "同一个维度可以多选，不同维度之间是「同时满足」。",
                    // 字号走主题字阶（F6）：bodySmall 为 12sp。
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭",
                tint = TextHint,
                modifier = Modifier
                    // 触达面积达标（F7）：热区对齐 48dp（原 32dp）。
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDone)
                    .padding(6.dp)
            )
        }

        SheetSection(title = statusDimensionTitle(StatusDimension.USAGE)) {
            statusOptionsFor(StatusDimension.USAGE).forEach { option ->
                val colors = itemStatusColors(StatusDimension.USAGE, option.code)
                SelectableChip(
                    label = option.label,
                    selected = option.code in filter.usageStatuses,
                    accent = colors.second,
                    onClick = { onToggleUsageStatus(option.code) }
                )
            }
        }

        SheetSection(title = statusDimensionTitle(StatusDimension.DISPOSITION)) {
            statusOptionsFor(StatusDimension.DISPOSITION).forEach { option ->
                val colors = itemStatusColors(StatusDimension.DISPOSITION, option.code)
                SelectableChip(
                    label = option.label,
                    selected = option.code in filter.dispositions,
                    accent = colors.second,
                    onClick = { onToggleDisposition(option.code) }
                )
            }
        }

        SheetSection(title = "补货") {
            SelectableChip(
                label = "只看待买",
                selected = filter.needRestockOnly,
                accent = OrangeStart,
                onClick = { onSetNeedRestockOnly(!filter.needRestockOnly) }
            )
        }

        SheetSection(title = "归属") {
            PickerField(
                label = "分类",
                value = categoryPath,
                placeholder = "全部分类",
                onClick = onPickCategory
            )
            Spacer(modifier = Modifier.height(8.dp))
            PickerField(
                label = "存放位置",
                value = locationPath,
                placeholder = "全部位置",
                onClick = onPickLocation
            )
        }

        SheetSection(title = "结果分组") {
            LibraryGrouping.entries.forEach { grouping ->
                SelectableChip(
                    label = grouping.label,
                    selected = filter.grouping == grouping,
                    accent = OrangeStart,
                    onClick = { onSetGrouping(grouping) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SheetButton(
                text = "重置",
                modifier = Modifier.weight(1f),
                onClick = onReset
            )
            SheetButton(
                text = "完成",
                modifier = Modifier.weight(1f),
                highlight = true,
                onClick = onDone
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = 18.dp)) {
        Text(
            text = title,
            // 字号走主题字阶（F6）：titleSmall 为 14sp。
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            // 筛选面板里的选项也走 48dp 最小触控热区；视觉高度仍由内容决定。
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .background(if (selected) accent.copy(alpha = 0.14f) else SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            // 字号走主题字阶（F6）：labelLarge 为 14sp。
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else TextSecondary
        )
    }
}

@Composable
private fun PickerField(
    label: String,
    value: String?,
    placeholder: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 形状走形状令牌（F6）：medium 为 16dp。
            .clip(MaterialTheme.shapes.medium)
            .background(SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            // 字号走主题字阶（F6）：bodyMedium 为 14sp。
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value ?: placeholder,
            // 字号走主题字阶（F6）：bodyMedium 为 14sp。
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (value != null) OrangeStart else TextHint
        )
    }
}

@Composable
private fun SheetButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Box(
        modifier = modifier
            // 形状走形状令牌（F6）：large 为 20dp。
            .clip(MaterialTheme.shapes.large)
            .background(if (highlight) OrangeStart.copy(alpha = 0.12f) else SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            // 字号走主题字阶（F6）：labelLarge 为 14sp。
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) OrangeStart else TextSecondary
        )
    }
}

private data class LibrarySection(
    val title: String,
    val items: List<ItemDetail>
)

@Composable
private fun HouseholdTopBar(
    showBackArrow: Boolean,
    onBack: (() -> Unit)?,
    expiringCount: Int,
    showOverflow: Boolean,
    onShowOverflow: (Boolean) -> Unit,
    onOpenExpiry: () -> Unit,
    onOpenCategory: () -> Unit,
    onOpenTrash: () -> Unit
) {
    val back = onBack
    TabPageHeader(
        title = "家当",
        modifier = Modifier.padding(horizontal = 20.dp),
        leading = if (showBackArrow && back != null) {
            {
                IconButton(onClick = back) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = TextPrimary
                    )
                }
            }
        } else {
            null
        },
        actions = {
            IconButton(onClick = onOpenExpiry) {
                BadgedBox(
                    badge = {
                        if (expiringCount > 0) {
                            Badge { Text(expiringCount.toString()) }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = if (expiringCount > 0) {
                            "到期提醒，$expiringCount 项"
                        } else {
                            "到期提醒"
                        },
                        tint = TextSecondary
                    )
                }
            }
            Box {
                IconButton(onClick = { onShowOverflow(true) }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = TextSecondary
                    )
                }
                DropdownMenu(
                    expanded = showOverflow,
                    onDismissRequest = { onShowOverflow(false) }
                ) {
                    DropdownMenuItem(
                        text = { Text("家当设置") },
                        leadingIcon = {
                            Icon(Icons.Default.Widgets, contentDescription = null)
                        },
                        onClick = {
                            onShowOverflow(false)
                            onOpenCategory()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("回收站") },
                        leadingIcon = {
                            Icon(Icons.Default.History, contentDescription = null)
                        },
                        onClick = {
                            onShowOverflow(false)
                            onOpenTrash()
                        }
                    )
                }
            }
        }
    )
}

@Composable
private fun HouseholdFilterRow(
    filter: LibraryFilter,
    currentPreset: LibraryPreset?,
    presetCounts: Map<LibraryPreset, Int>,
    userPresets: List<UserFilterPreset>,
    activeChips: List<ActiveCondition>,
    onOpenFilter: () -> Unit,
    onSelectPreset: (LibraryPreset) -> Unit,
    onApplyUserPreset: (UserFilterPreset) -> Unit,
    onDeleteUserPreset: (UserFilterPreset) -> Unit,
    onSavePreset: () -> Unit,
    onResetFilter: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterEntryChip(
            activeCount = filter.activeConditionCount,
            selected = filter.activeConditionCount > 0,
            onClick = onOpenFilter
        )
        presetChipOrder.forEach { preset ->
            PresetChip(
                label = preset.label,
                count = presetCounts[preset] ?: 0,
                selected = currentPreset == preset,
                onClick = { onSelectPreset(preset) }
            )
        }
        // 用户自定义预设（SE1）：点击应用、长按删除。
        // P3-8：长按是隐藏手势，补一条可被读屏读出的说明，让「能删」可发现。
        userPresets.forEach { preset ->
            PresetChip(
                label = preset.label,
                count = null,
                selected = filter == preset.filter,
                onClick = { onApplyUserPreset(preset) },
                onLongClick = { onDeleteUserPreset(preset) },
                contentDescription = "${preset.label}，长按可删除"
            )
        }
        // 已选条件与预设共用一条横向滚动区，不再额外占一行。
        activeChips.forEach { chip ->
            ActiveConditionChip(label = chip.label, onRemove = chip.onRemove)
        }
        if (filter != LibraryFilter()) {
            PresetChip(
                label = "＋ 存为预设",
                count = null,
                selected = false,
                onClick = onSavePreset
            )
        }
        if (activeChips.isNotEmpty()) {
            Text(
                text = "清空",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onResetFilter)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun HouseholdSummaryLine(
    totalValueCents: Long,
    dailyCost: Double
) {
    val valueText = if (totalValueCents > 0) {
        DateUtil.formatCurrency(LedgerMath.centsToYuan(totalValueCents))
    } else {
        "未记价格"
    }
    val dailyText = if (dailyCost > 0) DateUtil.formatCurrency(dailyCost) else "—"
    Text(
        text = "估值 $valueText · 日均 $dailyText",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun HouseholdResultsHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    onBatch: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "共 $count 件",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp)
        )
        if (onBatch != null) {
            Text(
                text = "批量",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = OrangeStart,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onBatch)
                    .padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun HouseholdEmptyState(
    allItemsEmpty: Boolean,
    query: String,
    currentPreset: LibraryPreset?,
    presetCounts: Map<LibraryPreset, Int>,
    onNavigateToSave: () -> Unit,
    onNavigateToToBuy: () -> Unit,
    onSelectPreset: (LibraryPreset) -> Unit,
    onReset: () -> Unit
) {
    val topPadding = Modifier.padding(top = 8.dp)
    if (allItemsEmpty) {
        EmptyState(
            title = "还没有家当",
            message = "把家里的东西录进来，就能在这里搜索、筛选和管理了。",
            actionLabel = "快速录入",
            actionIcon = Icons.Default.Add,
            onAction = onNavigateToSave,
            compact = true,
            modifier = topPadding
        )
        return
    }

    val toBuyInLibrary = presetCounts[LibraryPreset.TO_BUY] ?: 0
    val usedUpInLibrary = presetCounts[LibraryPreset.USED_UP] ?: 0
    when {
        // 默认落在「在库」而库里只有待买/已用完时，别再说「放宽筛选」——
        // 她需要知道东西在哪个分区，以及下一步点哪里。
        query.isBlank() && currentPreset == LibraryPreset.IN_STOCK && toBuyInLibrary > 0 -> {
            EmptyState(
                title = "现在没有在库物品",
                message = "有 $toBuyInLibrary 件在待买清单；买到后会回到这里。",
                actionLabel = "去看待买",
                actionIcon = Icons.Default.ShoppingCart,
                onAction = onNavigateToToBuy,
                compact = true,
                modifier = topPadding
            )
        }

        query.isBlank() && currentPreset == LibraryPreset.IN_STOCK && usedUpInLibrary > 0 -> {
            EmptyState(
                title = "现在没有在库物品",
                message = "有 $usedUpInLibrary 件已用完；补货或归位后会回到这里。",
                actionLabel = "查看已用完",
                actionIcon = Icons.Default.History,
                onAction = { onSelectPreset(LibraryPreset.USED_UP) },
                compact = true,
                modifier = topPadding
            )
        }

        else -> {
            EmptyState(
                title = "没有找到匹配物品",
                message = if (query.isBlank()) {
                    "当前筛选条件下还没有物品，可以放宽一些条件。"
                } else {
                    "可以更换关键词，或者去掉部分筛选条件。"
                },
                // 有家当却筛不出来，往往是自己把筛选收窄了（I5）：给一键「重置」，清关键词 + 恢复默认筛选。
                actionLabel = "重置筛选条件",
                actionIcon = Icons.Default.Close,
                onAction = onReset,
                compact = true,
                modifier = topPadding
            )
        }
    }
}

private fun buildSections(
    items: List<ItemDetail>,
    grouping: LibraryGrouping
): List<LibrarySection> {
    if (grouping == LibraryGrouping.NONE) return emptyList()
    return items
        .groupBy { detail ->
            val name = when (grouping) {
                LibraryGrouping.CATEGORY -> detail.categoryName
                LibraryGrouping.LOCATION -> detail.locationName
                LibraryGrouping.NONE -> null
            }
            name?.takeIf { it.isNotBlank() }
                ?: if (grouping == LibraryGrouping.CATEGORY) "未分类" else "未设置位置"
        }
        .map { (title, grouped) -> LibrarySection(title, grouped) }
        .sortedWith(compareByDescending<LibrarySection> { it.items.size }.thenBy { it.title })
}
