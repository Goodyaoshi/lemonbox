package com.goodyaoshi.lemonbox.ui.screen.trash

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.SectionHeader
import com.goodyaoshi.lemonbox.ui.components.SkeletonList
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.OnScrim
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarm
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.TrashViewModel
import com.goodyaoshi.lemonbox.util.DateUtil

@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel()
) {
    val deletedItems by viewModel.deletedItems.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    var pendingDeleteIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    val appSnackbar = LocalAppSnackbar.current

    LaunchedEffect(deletedItems) {
        viewModel.pruneSelection(deletedItems.map { it.item.id }.toSet())
    }

    val allItemIds = remember(deletedItems) { deletedItems.map { it.item.id }.toSet() }
    val allSelected = allItemIds.isNotEmpty() && selectedIds.containsAll(allItemIds)
    val selectedCount = selectedIds.size

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "回收站",
                        // 页面主标题统一（F6）：走 headlineLarge（28sp）。
                        style = MaterialTheme.typography.headlineLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "删除后 30 天内你都能恢复，过期会自动清理",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            if (isLoading) {
                // 首帧：列表还没读到，先画骨架，别让「还没加载」看起来像「回收站是空的」。
                SkeletonList(itemCount = 3, showHeader = false)
            } else if (deletedItems.isEmpty()) {
                // 空回收站刻意不给操作按钮（I5 的例外）：这里没有「主操作」可直达，
                // 且文案本身没有引用屏幕外的位置，因此保留纯说明态即可，避免硬塞一个「返回」按钮。
                EmptyState(
                    title = "回收站是空的",
                    message = "最近删除的物品会先放在这里，30 天内可以恢复。",
                    modifier = Modifier.padding(top = 24.dp)
                )
            } else {
                AppSurfaceCard(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    shadowElevation = 10.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (selectedCount == 0) "未选择项目" else "已选择 $selectedCount 项",
                                // 字号走主题字阶（F6）：titleMedium 为 16sp。
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "可以批量恢复，也能永久删除",
                                // 字号走主题字阶（F6）：bodySmall 为 12sp。
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                        TrashActionButton(
                            // 这里只是切换选择，不是清空回收站；写「取消全选」避免被误读成删除。
                            text = if (allSelected) "取消全选" else "全选",
                            onClick = {
                                if (allSelected) {
                                    viewModel.clearSelection()
                                } else {
                                    viewModel.selectAll(allItemIds)
                                }
                            }
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TrashActionButton(
                            text = "批量恢复",
                            modifier = Modifier.weight(1f),
                            enabled = selectedCount > 0,
                            highlight = true,
                            // 批量恢复同样给回执与撤销（G-12）：不再是无回执的单向操作。
                            onClick = {
                                viewModel.restoreSelected { snapshots ->
                                    appSnackbar?.showUndo(
                                        "已恢复 ${snapshots.size} 项",
                                        DeleteCopy.UNDO_ACTION
                                    ) {
                                        viewModel.moveToTrashAgain(snapshots)
                                    }
                                }
                            }
                        )
                        TrashActionButton(
                            text = "永久删除",
                            modifier = Modifier.weight(1f),
                            enabled = selectedCount > 0,
                            destructive = true,
                            onClick = { pendingDeleteIds = selectedIds.toList() }
                        )
                    }
                }

                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        SectionHeader(
                            title = "最近删除",
                            subtitle = "共 ${deletedItems.size} 项可在 30 天内恢复"
                        )
                    }
                    items(deletedItems, key = { it.item.id }) { itemDetail ->
                        TrashItemCard(
                            itemDetail = itemDetail,
                            selected = itemDetail.item.id in selectedIds,
                            onToggleSelection = { viewModel.toggleSelection(itemDetail.item.id) },
                            onRestore = {
                                viewModel.restore(itemDetail.item.id)
                                // 单条恢复补回执与撤销（G-12）：快照直接取自当前列表项。
                                appSnackbar?.showUndo(
                                    "已恢复「${itemDetail.item.name}」",
                                    DeleteCopy.UNDO_ACTION
                                ) {
                                    viewModel.moveToTrashAgain(listOf(itemDetail.item))
                                }
                            },
                            onDelete = { pendingDeleteIds = listOf(itemDetail.item.id) }
                        )
                    }
                }
            }
        }
    }

    if (pendingDeleteIds.isNotEmpty()) {
        val isBatchDelete = pendingDeleteIds.size > 1
        // 批量永久删除（GAP-11）：比单条多一道「我已确认」勾选，确认按钮未勾选时不可点，
        // 把「一次性清掉一大批、彻底找不回」的摩擦提高到与风险相称。
        var acknowledged by remember(pendingDeleteIds) { mutableStateOf(false) }
        AppDialog(
            title = if (isBatchDelete) "永久删除选中的 ${pendingDeleteIds.size} 项？" else "永久删除这个物品？",
            subtitle = "永久删除后，回收站记录和对应图片会立即清空，就找不回来了。",
            onDismissRequest = { pendingDeleteIds = emptyList() },
            confirmText = "永久删除",
            dismissText = "取消",
            destructiveConfirm = true,
            confirmEnabled = !isBatchDelete || acknowledged,
            onConfirm = {
                viewModel.permanentlyDelete(pendingDeleteIds)
                pendingDeleteIds = emptyList()
            }
        ) {
            if (isBatchDelete) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(StatusExpired.copy(alpha = 0.10f))
                        .clickable { acknowledged = !acknowledged }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                        // 硬编码白改走语义色令牌（F5）。
                        .background(if (acknowledged) StatusExpired else CardWhite),
                        contentAlignment = Alignment.Center
                    ) {
                        if (acknowledged) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = OnScrim,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "我已确认这 ${pendingDeleteIds.size} 项无法恢复",
                        // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashItemCard(
    itemDetail: ItemDetail,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val item = itemDetail.item
    val deletedAt = item.deletedAt ?: System.currentTimeMillis()
    val restoreDeadline = deletedAt + 30L * 24 * 60 * 60 * 1000
    val daysLeft = DateUtil.daysUntil(restoreDeadline).coerceAtLeast(0)
    val shape = MaterialTheme.shapes.extraLarge

    AppSurfaceCard(
        modifier = Modifier.border(
            width = 1.dp,
            color = if (selected) OrangeStart.copy(alpha = 0.35f) else DividerSoft,
            shape = shape
        ),
        shape = shape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(StatusExpired.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = StatusExpired
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    // 字号走主题字阶（F6）：titleMedium 为 16sp。
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = listOfNotNull(itemDetail.categoryName, itemDetail.locationName)
                        .joinToString(" · ")
                        .ifBlank { "未分类 / 未设置位置" },
                    // 字号走主题字阶（F6）：bodySmall 为 12sp。
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint,
                    modifier = Modifier.padding(top = 3.dp)
                )
                Text(
                    text = "删除于 ${DateUtil.formatDateTime(deletedAt)}",
                    // 字号走主题字阶（F6）：bodySmall 为 12sp。
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = if (daysLeft == 0L) "今天内仍可恢复" else "剩余 $daysLeft 天可恢复",
                    // 字号走主题字阶（F6）：bodySmall 为 12sp。
                    style = MaterialTheme.typography.bodySmall,
                    color = OrangeStart,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Box(
                modifier = Modifier
                    // 热区对齐 48dp（视觉勾选框仍 28dp），避免细小的勾选框难以点中。
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        // 勾选控件恢复默认涟漪（I3）：它本身是承载动作的按钮，而非背景热区。
                        indication = LocalIndication.current,
                        // 声明为复选框，读屏会播报「复选框」角色与选中态（F16）。
                        role = Role.Checkbox,
                        onClick = onToggleSelection
                    )
                    // 纯图形勾选框没有文字兜底，补名称与选中状态（F16）。
                    .semantics {
                        contentDescription = "选择该家当"
                        stateDescription = if (selected) "已选中" else "未选中"
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (selected) OrangeStart else CardWhite),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            // 选中态已由上方 stateDescription 播报，图标此处留空避免重复播报。
                            contentDescription = null,
                            tint = OnScrim,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TrashActionButton(
                text = "恢复",
                icon = Icons.Default.RestoreFromTrash,
                modifier = Modifier.weight(1f),
                highlight = true,
                onClick = onRestore
            )
            TrashActionButton(
                text = "永久删除",
                icon = Icons.Default.DeleteForever,
                modifier = Modifier.weight(1f),
                destructive = true,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun TrashActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    highlight: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val background = when {
        !enabled -> SurfaceWarmDeep
        destructive -> StatusExpired.copy(alpha = 0.12f)
        highlight -> OrangeStart.copy(alpha = 0.12f)
        else -> SurfaceWarm
    }
    val contentColor = when {
        !enabled -> TextHint
        destructive -> StatusExpired
        highlight -> OrangeStart
        else -> TextSecondary
    }

    Row(
        modifier = modifier
            // 触达面积达标（F7）：先撑出 48dp 最小可点区（按钮高度不足 48dp）。
            .minimumInteractiveComponentSize()
            .clip(MaterialTheme.shapes.large)
            .background(background)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                // 「恢复 / 永久删除」按钮恢复默认涟漪（I3）：永久删除为主流程按钮，更需要按压确认。
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.size(6.dp))
        }
        Text(
            text = text,
            // 字号走主题字阶（F6）：labelLarge 为 14sp。
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}
