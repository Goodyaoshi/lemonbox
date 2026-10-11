package com.goodyaoshi.lemonbox.ui.screen.tobuy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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
    viewModel: ToBuyViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val filteredItems by viewModel.filteredItems.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val filterCategoryId by viewModel.filterCategoryId.collectAsState()
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
                        SectionHeader(
                            title = "要买的东西",
                            subtitle = "共 ${filteredItems.size} 件，买到后点左侧圆圈归位"
                        )
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
                                        viewModel.markBought(itemDetail.item.id)
                                        // 家当联动：跳到记一笔预填金额与名称，不需要时直接返回即可。
                                        onRecordPurchase(
                                            itemDetail.item.id,
                                            LedgerMath.itemPriceToCents(
                                                itemDetail.item.price ?: 0.0,
                                                itemDetail.item.quantity
                                            ),
                                            itemDetail.item.name
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

@Composable
private fun ToBuyItemCard(
    name: String,
    quantity: Int,
    unit: String,
    forWhomLabel: String?,
    subtitle: String,
    onBought: () -> Unit,
    onEdit: () -> Unit
) {
    AppSurfaceCard(
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
        shadowElevation = 12.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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