package com.goodyaoshi.lemonbox.ui.screen.tobuy

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorInputBox
import com.goodyaoshi.lemonbox.ui.components.EditorPickerField
import com.goodyaoshi.lemonbox.ui.components.EditorSectionLabel
import com.goodyaoshi.lemonbox.ui.components.EditorSelectionChip
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.HierarchicalPickerDialog
import com.goodyaoshi.lemonbox.ui.components.QuantityStepper
import com.goodyaoshi.lemonbox.ui.components.SectionHeader
import com.goodyaoshi.lemonbox.ui.components.SelectionActionButton
import com.goodyaoshi.lemonbox.ui.components.SelectionTopBar
import com.goodyaoshi.lemonbox.ui.components.SkeletonList
import com.goodyaoshi.lemonbox.ui.components.SwipeActionSpec
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.ui.components.TreeNode
import com.goodyaoshi.lemonbox.ui.components.UnitPickerRow
import com.goodyaoshi.lemonbox.ui.components.buildTreePathLabel
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.ToBuyViewModel
import com.goodyaoshi.lemonbox.util.LedgerMath

@Composable
fun ToBuyScreen(
    onBack: () -> Unit,
    onRecordPurchase: (itemId: Long, amountCents: Long, name: String) -> Unit = { _, _, _ -> },
    onOpenRestockForecast: () -> Unit = {},
    viewModel: ToBuyViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val filteredItems by viewModel.filteredItems.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val filterCategoryId by viewModel.filterCategoryId.collectAsState()
    // 批量模式（G-4）：与回收站同一套选择范式。
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val nickname by viewModel.nickname.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<Item?>(null) }
    var openedItemId by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteItem by remember { mutableStateOf<Item?>(null) }
    val appSnackbar = LocalAppSnackbar.current

    // 分类筛选项：只列出当前待买项真正用到的分类，避免一整棵分类树挤在顶部。
    val filterOptions = remember(items) {
        items
            .mapNotNull { detail ->
                detail.item.categoryId?.let { id -> id to (detail.categoryName ?: "未命名分类") }
            }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }
    // 分类选项变更后，原选中项可能已不在列表里，回退到「全部」。
    val effectiveFilter = filterCategoryId?.takeIf { id -> filterOptions.any { it.first == id } }

    val categoryNodes = remember(categories) {
        categories.map { TreeNode(id = it.id, parentId = it.parentId, name = it.name) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 点击空白处收起侧滑行的背景热区：用 pointerInput 而非 clickable，
            // 这样只接管触摸、不向无障碍树暴露一个无标签的“整屏可点”节点。
            .pointerInput(Unit) {
                detectTapGestures(onTap = { openedItemId = null })
            }
    ) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "待买清单",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "用完的、想补的，都记在这",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                // 智能补货入口（IA-4）：算完就能回到待买闭环，不用绕去「我的」。
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(SurfaceWarmDeep)
                        .clickable(onClick = onOpenRestockForecast)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoGraph,
                        contentDescription = null,
                        tint = OrangeStart,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = "智能补货",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OrangeStart
                    )
                }
                // 批量入口（G-4）：超市一次买回好几样时逐条点圆圈太碎，先进批量模式一次归位；
                // 仅在列表非空时出现，批量中隐藏（操作条里已有「取消」退出）。
                if (items.isNotEmpty() && !isSelectionMode) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(SurfaceWarmDeep)
                            .clickable {
                                // 收起可能滑开的侧滑行，避免带着旧手势状态进入批量模式。
                                openedItemId = null
                                viewModel.enterSelectionMode()
                            }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "批量",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(OrangeStart.copy(alpha = 0.12f))
                        .clickable { showAddDialog = true }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = OrangeStart,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = "添加",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OrangeStart
                    )
                }
            }

            // 固定分类筛选：常驻页头下方，翻长清单也能随时换分类。
            if (filterOptions.isNotEmpty()) {
                CategoryFilterRow(
                    options = filterOptions,
                    selectedId = effectiveFilter,
                    onSelect = viewModel::setFilterCategory,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .padding(top = 2.dp, bottom = 10.dp)
                )
            }

            if (isLoading) {
                // 首帧：待买列表还没读到，先画骨架，别让「还没加载」看起来像「清单被清空了」。
                SkeletonList(itemCount = 3, showHeader = false)
            } else if (items.isEmpty()) {
                EmptyState(
                    title = "还没有要买的",
                    // 原文案让用户去点「右上角」（I5），改为空态里直接给「手动添加」按钮。
                    message = "物品用完会自动记到这里，也可以手动添加一条。",
                    actionLabel = "手动添加",
                    onAction = { showAddDialog = true },
                    modifier = Modifier.padding(top = 24.dp)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 4.dp,
                        bottom = 28.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        if (isSelectionMode) {
                            // 批量模式（G-4）：页头换成选择操作条；全选口径跟随当前分类筛选结果，
                            // 与回收站的批量范式保持一致。
                            SelectionTopBar(
                                selectedCount = selectedIds.size,
                                allSelected = filteredItems.isNotEmpty() &&
                                    filteredItems.all { it.item.id in selectedIds },
                                onToggleSelectAll = {
                                    if (filteredItems.all { it.item.id in selectedIds }) {
                                        viewModel.clearSelection()
                                    } else {
                                        viewModel.selectAll(filteredItems.map { it.item.id }.toSet())
                                    }
                                },
                                hint = "勾选买回的东西，一次全部归位"
                            ) {
                                SelectionActionButton(
                                    text = "归位",
                                    modifier = Modifier.weight(1f),
                                    enabled = selectedIds.isNotEmpty(),
                                    highlight = true,
                                    onClick = {
                                        viewModel.markSelectedBought { snapshots ->
                                            // 批量归位不弹「记一笔」：一次买回多件时记账意图不明确，
                                            // 统一跳过记账联动；撤销按归位前的快照整批写回。
                                            appSnackbar?.showUndo(
                                                "已归位 ${snapshots.size} 件",
                                                DeleteCopy.UNDO_ACTION
                                            ) {
                                                snapshots.forEach { viewModel.undoMarkBought(it) }
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
                        } else {
                            SectionHeader(
                                title = "要买的东西",
                                subtitle = "共 ${filteredItems.size} 件，买到后点左侧圆圈归位"
                            )
                        }
                    }
                    if (filteredItems.isEmpty()) {
                        item {
                            EmptyState(
                                title = "这个分类下暂时没有要买的",
                                message = "换个分类看看，或回到「全部」查看所有待买项。",
                                // 分类筛选筛空时给一键退回「全部」（I5）。
                                actionLabel = "查看全部",
                                actionIcon = Icons.Default.Close,
                                onAction = { viewModel.setFilterCategory(null) },
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    } else {
                        items(filteredItems, key = { it.item.id }) { itemDetail ->
                            if (isSelectionMode) {
                                // 批量模式：整行点击即切换勾选，侧滑与编辑按钮暂时隐藏，
                                // 避免与勾选手势打架；退出批量模式后恢复单条交互。
                                ToBuyItemCard(
                                    name = itemDetail.item.name,
                                    quantity = itemDetail.item.quantity,
                                    unit = itemDetail.item.unit,
                                    forWhomLabel = forWhomLabel(itemDetail.item.forWhom, nickname),
                                    subtitle = listOfNotNull(
                                        itemDetail.categoryName,
                                        itemDetail.locationName
                                    ).joinToString(" · ").ifBlank { "未设置分类与位置" },
                                    selected = itemDetail.item.id in selectedIds,
                                    onToggleSelection = {
                                        viewModel.toggleSelection(itemDetail.item.id)
                                    }
                                )
                            } else {
                                SwipeRevealItem(
                                    itemKey = itemDetail.item.id,
                                    openedItemKey = openedItemId,
                                    onOpenedItemChange = { openedItemId = it as Long? },
                                    actions = listOf(
                                        SwipeActionSpec(
                                            label = "删除",
                                            icon = Icons.Default.Delete,
                                            backgroundColor = StatusExpired,
                                            onClick = { pendingDeleteItem = itemDetail.item }
                                        )
                                    )
                                ) { _, _ ->
                                    ToBuyItemCard(
                                        name = itemDetail.item.name,
                                        quantity = itemDetail.item.quantity,
                                        unit = itemDetail.item.unit,
                                        forWhomLabel = forWhomLabel(itemDetail.item.forWhom, nickname),
                                        subtitle = listOfNotNull(
                                            itemDetail.categoryName,
                                            itemDetail.locationName
                                        ).joinToString(" · ").ifBlank { "未设置分类与位置" },
                                        onBought = {
                                            // 先留一份勾选前的快照，撤销时按它恢复。
                                            val previous = itemDetail.item
                                            viewModel.markBought(previous.id)
                                            // 家当联动改为可选：不再强制跳页，想记账点「记一笔」，点错用「撤销」。
                                            appSnackbar?.showDualAction(
                                                message = "「${previous.name}」已归位",
                                                primaryLabel = "记一笔",
                                                secondaryLabel = "撤销",
                                                onPrimary = {
                                                    onRecordPurchase(
                                                        previous.id,
                                                        LedgerMath.itemPriceToCents(
                                                            previous.price ?: 0.0,
                                                            previous.quantity
                                                        ),
                                                        previous.name
                                                    )
                                                },
                                                onSecondary = { viewModel.undoMarkBought(previous) }
                                            )
                                        },
                                        // 之前待买项只能删不能改，写错名字/数量只能删掉重建；这里补一个编辑入口。
                                        onEdit = { editingItem = itemDetail.item }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        ToBuyEditorDialog(
            categoryNodes = categoryNodes,
            existing = null,
            nickname = nickname,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, quantity, unit, categoryId, forWhom ->
                viewModel.addToBuy(name, quantity, unit, categoryId, forWhom)
                showAddDialog = false
            }
        )
    }

    editingItem?.let { editing ->
        ToBuyEditorDialog(
            categoryNodes = categoryNodes,
            existing = editing,
            nickname = nickname,
            onDismiss = { editingItem = null },
            onConfirm = { name, quantity, unit, categoryId, forWhom ->
                viewModel.updateToBuy(editing, name, quantity, unit, categoryId, forWhom)
                editingItem = null
            }
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
                fontSize = 14.sp
            )
        }
    }
}

/** 顶部按分类筛选的胶囊行：「全部」+ 待买项实际用到的分类。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryFilterRow(
    options: List<Pair<Long, String>>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        EditorSelectionChip(
            text = "全部",
            selected = selectedId == null,
            onClick = { onSelect(null) }
        )
        options.forEach { (id, name) ->
            EditorSelectionChip(
                text = name,
                selected = selectedId == id,
                onClick = { onSelect(id) }
            )
        }
    }
}

/** 归属码值 → 展示文案；null / 未知码值不展示。「给她」随当前昵称变化。 */
private fun forWhomLabel(code: String?, nickname: String): String? = when (code) {
    Item.FOR_WHOM_SELF -> "给自己"
    Item.FOR_WHOM_PARTNER -> "给$nickname"
    Item.FOR_WHOM_SHARED -> "家里共用"
    else -> null
}

/**
 * 待买行卡片：普通模式行首是「已买到」圆圈、行尾有编辑按钮；
 * 批量模式（[onBought] 为空）行首换成勾选框、整行可点切换勾选，编辑按钮隐藏。
 */
@Composable
private fun ToBuyItemCard(
    name: String,
    quantity: Int,
    unit: String,
    forWhomLabel: String?,
    subtitle: String,
    onBought: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    selected: Boolean = false,
    onToggleSelection: () -> Unit = {}
) {
    AppSurfaceCard(
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
        shadowElevation = 12.dp,
        // 批量模式下整行可点即切换勾选，热区远大于 28dp 的勾选框本身；普通模式保持不可点。
        onClick = if (onBought == null) onToggleSelection else null
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBought != null) {
                Box(
                    // 热区对齐 48dp（视觉圆仍 34dp），便于点按。
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBought),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(SurfaceWarmDeep),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已买到",
                            tint = OrangeStart,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                // 批量勾选框：与回收站同一套视觉与读屏处理（48dp 热区 + Checkbox 语义，F16/I3）。
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            role = Role.Checkbox,
                            onClick = onToggleSelection
                        )
                        .semantics {
                            contentDescription = "选择该待买项"
                            stateDescription = if (selected) "已选中" else "未选中"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // 未选中用 SurfaceWarmDeep：本卡片无边框，CardWhite 会在卡片上隐形。
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (selected) OrangeStart else SurfaceWarmDeep),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                // 选中态已由上方 stateDescription 播报，图标此处留空避免重复播报。
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    if (forWhomLabel != null) {
                        Spacer(modifier = Modifier.size(8.dp))
                        PillTag(
                            text = forWhomLabel,
                            backgroundColor = OrangeTint,
                            contentColor = OrangeStart
                        )
                    }
                }
                Text(
                    text = "数量 $quantity$unit · $subtitle",
                    fontSize = 12.sp,
                    color = TextHint,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            // 批量模式下编辑按钮隐藏：编辑入口与勾选并存容易误触，退出批量模式后恢复。
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "编辑",
                        tint = TextHint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToBuyEditorDialog(
    categoryNodes: List<TreeNode>,
    existing: Item?,
    nickname: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, quantity: Int, unit: String, categoryId: Long?, forWhom: String?) -> Unit
) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var unit by remember(existing) { mutableStateOf(existing?.unit ?: "件") }
    var quantity by remember(existing) { mutableIntStateOf(existing?.quantity ?: 1) }
    var categoryId by remember(existing) { mutableStateOf(existing?.categoryId) }
    var forWhom by remember(existing) { mutableStateOf(existing?.forWhom) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    val isEditing = existing != null

    AppDialog(
        title = if (isEditing) "编辑待买" else "添加待买",
        subtitle = if (isEditing) {
            "改完名字、数量或分类，买到后仍会归位到家当。"
        } else {
            "记下名字和数量，还能选单位、关联分类，买到后归位到家当。"
        },
        onDismissRequest = onDismiss,
        confirmText = if (isEditing) "保存" else "添加",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name, quantity, unit, categoryId, forWhom) }
    ) {
        EditorInputBox(
            value = name,
            onValueChange = { name = it },
            placeholder = "要买什么？比如 洗衣液"
        )
        QuantityStepper(
            quantity = quantity,
            unit = unit,
            onDecrease = { if (quantity > 1) quantity-- },
            onIncrease = { quantity++ }
        )
        Column {
            EditorSectionLabel(label = "单位")
            Spacer(modifier = Modifier.size(8.dp))
            UnitPickerRow(
                unit = unit,
                onUnitChange = { unit = it }
            )
        }
        Column {
            EditorSectionLabel(label = "分类")
            Spacer(modifier = Modifier.size(8.dp))
            EditorPickerField(
                value = buildTreePathLabel(categoryId, categoryNodes),
                placeholder = "点击选择分类（可选）",
                onClick = { showCategoryPicker = true }
            )
        }
        Column {
            EditorSectionLabel(label = "给谁的")
            Spacer(modifier = Modifier.size(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditorSelectionChip(
                    text = "不标注",
                    selected = forWhom == null,
                    onClick = { forWhom = null }
                )
                Item.FOR_WHOM_CODES.forEach { code ->
                    EditorSelectionChip(
                        text = forWhomLabel(code, nickname).orEmpty(),
                        selected = forWhom == code,
                        onClick = { forWhom = code }
                    )
                }
            }
        }
    }

    if (showCategoryPicker) {
        HierarchicalPickerDialog(
            title = "选择分类",
            nodes = categoryNodes,
            selectedId = categoryId,
            onSelect = { categoryId = it },
            onDismissRequest = { showCategoryPicker = false },
            emptyHint = "还没有分类，可到「分类」页添加。"
        )
    }
}