package com.goodyaoshi.lemonbox.ui.screen.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.HierarchicalPickerDialog
import com.goodyaoshi.lemonbox.ui.components.ItemActionHandlers
import com.goodyaoshi.lemonbox.ui.components.ItemCard
import com.goodyaoshi.lemonbox.ui.components.ItemMoreActionsDialog
import com.goodyaoshi.lemonbox.ui.components.SearchBar
import com.goodyaoshi.lemonbox.ui.components.SectionHeader
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.ui.components.TreeNode
import com.goodyaoshi.lemonbox.ui.components.buildItemSwipeActions
import com.goodyaoshi.lemonbox.ui.components.buildTreePathLabel
import com.goodyaoshi.lemonbox.ui.components.itemStatusColors
import com.goodyaoshi.lemonbox.ui.components.statusDimensionTitle
import com.goodyaoshi.lemonbox.ui.components.statusOptionsFor
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
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
import com.goodyaoshi.lemonbox.ui.viewmodel.SearchViewModel

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigateToDetail: (Long, LibraryFilter) -> Unit,
    onNavigateToEdit: (Long) -> Unit,
    onNavigateToSave: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val allItems by viewModel.allItems.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val presetCounts by viewModel.presetCounts.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val locations by viewModel.locations.collectAsState()
    var openedItemId by remember { mutableStateOf<Long?>(null) }
    var moreItemId by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteItem by remember { mutableStateOf<Item?>(null) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var pickerTarget by remember { mutableStateOf<PickerTarget?>(null) }

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

    val itemRow: @Composable (ItemDetail) -> Unit = { itemDetail ->
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
                .padding(top = 10.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "家当",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "搜索与筛选都在这里，条件可以自由叠加。",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                )
                SearchBar(
                    value = query,
                    onValueChange = viewModel::updateQuery,
                    placeholder = "搜索物品、分类、位置或备注",
                    showMagicIconWhenEmpty = false
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterEntryChip(
                    activeCount = filter.activeConditionCount,
                    selected = filter.activeConditionCount > 0,
                    onClick = { showFilterSheet = true }
                )
                presetChipOrder.forEach { preset ->
                    PresetChip(
                        label = preset.label,
                        count = presetCounts[preset] ?: 0,
                        selected = currentPreset == preset,
                        onClick = { viewModel.selectPreset(preset) }
                    )
                }
            }

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

            if (activeChips.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    activeChips.forEach { chip ->
                        ActiveConditionChip(label = chip.label, onRemove = chip.onRemove)
                    }
                    Text(
                        text = "清空",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { viewModel.resetFilter() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }

            if (results.isEmpty()) {
                EmptyState(
                    title = "没有找到匹配物品",
                    message = if (query.isBlank()) {
                        "当前筛选条件下还没有物品，可以放宽一些条件。"
                    } else {
                        "可以更换关键词，或者去掉部分筛选条件。"
                    },
                    // 家当页空结果往往是自己把筛选收窄了（I5）：给一键「重置」，清关键词 + 恢复默认筛选。
                    actionLabel = "重置筛选条件",
                    actionIcon = Icons.Default.Close,
                    onAction = {
                        viewModel.updateQuery("")
                        viewModel.resetFilter()
                    },
                    modifier = Modifier.padding(top = 24.dp)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 132.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        SectionHeader(
                            title = if (query.isNotBlank()) {
                                "搜索结果"
                            } else {
                                currentPreset?.label ?: "自定义筛选"
                            },
                            subtitle = "共 ${results.size} 件物品",
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    if (sections.isEmpty()) {
                        items(results, key = { it.item.id }) { itemDetail ->
                            itemRow(itemDetail)
                        }
                    } else {
                        sections.forEach { section ->
                            item(key = "section_${section.title}") {
                                SectionHeader(
                                    title = section.title,
                                    subtitle = "共 ${section.items.size} 件",
                                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
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
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 108.dp)
                .size(58.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(colors = listOf(LemonStart, LemonEnd))
                )
                .clickable {
                    openedItemId = null
                    onNavigateToSave()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "录入家当",
                tint = OnLemon,
                modifier = Modifier.size(28.dp)
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
            .clip(RoundedCornerShape(999.dp))
            .background(OrangeStart.copy(alpha = 0.12f))
            .clickable(onClick = onRemove)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
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
            shape = RoundedCornerShape(999.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
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
                    fontSize = 13.sp,
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

@Composable
private fun PresetChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    AppSurfaceCard(
        shape = RoundedCornerShape(999.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
        containerColor = if (selected) OrangeStart.copy(alpha = 0.12f) else CardWhite,
        shadowElevation = 8.dp,
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) OrangeStart else TextPrimary
            )
            Spacer(modifier = Modifier.size(5.dp))
            Text(
                text = count.toString(),
                fontSize = 12.sp,
                color = if (selected) OrangeStart else TextHint
            )
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
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "同一个维度可以多选，不同维度之间是「同时满足」。",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭",
                tint = TextHint,
                modifier = Modifier
                    .size(32.dp)
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
            fontSize = 14.sp,
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
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) accent.copy(alpha = 0.14f) else SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
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
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value ?: placeholder,
            fontSize = 13.sp,
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
            .clip(RoundedCornerShape(18.dp))
            .background(if (highlight) OrangeStart.copy(alpha = 0.12f) else SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) OrangeStart else TextSecondary
        )
    }
}

private data class LibrarySection(
    val title: String,
    val items: List<ItemDetail>
)

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