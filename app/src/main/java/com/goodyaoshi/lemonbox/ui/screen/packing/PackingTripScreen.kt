package com.goodyaoshi.lemonbox.ui.screen.packing

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.PackingItem
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.LemonFab
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SwipeActionSpec
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.PackingTripViewModel

/**
 * 单个行程的打包清单（F16）：逐条勾选「已装好」，可增删条目。
 */
@Composable
fun PackingTripScreen(
    onBack: () -> Unit,
    viewModel: PackingTripViewModel = hiltViewModel()
) {
    val trip by viewModel.trip.collectAsState()
    val items by viewModel.items.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var openedKey by remember { mutableStateOf<Any?>(null) }
    // 删除前先确认、删完可撤销（GAP-13）：与待买/家当的删除范式保持一致，不再手滑就没了。
    var pendingDelete by remember { mutableStateOf<PackingItem?>(null) }
    val appSnackbar = LocalAppSnackbar.current

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
                Text(
                    text = trip?.name ?: "打包清单",
                    // 字号走主题字阶（F6）：titleLarge 为 18sp。
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                TripItemContent(
                    items = items,
                    openedKey = openedKey,
                    onOpenedChange = { openedKey = it },
                    // 勾选「已装好」补撤销（GAP-13）：与删除同一套 Snackbar 撤销范式，
                    // 手滑打勾也能一键退回去（未勾选则直接落库，无需撤销）。
                    onToggle = { item, checked ->
                        viewModel.setChecked(item, checked)
                        if (checked) {
                            appSnackbar?.showUndo(
                                "「${item.name}」已装好",
                                DeleteCopy.UNDO_ACTION
                            ) {
                                viewModel.setChecked(item, false)
                            }
                        }
                    },
                    onDelete = { pendingDelete = it },
                    onAdd = { showAdd = true }
                )
                Spacer(modifier = Modifier.height(120.dp))
            }
        }

        LemonFab(
            icon = Icons.Default.Add,
            contentDescription = "添加条目",
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }

    if (showAdd) {
        AddItemDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, quantity, category ->
                viewModel.addItem(name, quantity, category) { showAdd = false }
            }
        )
    }

    pendingDelete?.let { target ->
        AppDialog(
            title = "删除这一条",
            subtitle = "从清单里移除「${target.name}」，删错了可以撤销。",
            onDismissRequest = { pendingDelete = null },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                viewModel.deleteItem(target)
                appSnackbar?.showUndo(
                    "已删除「${target.name}」",
                    DeleteCopy.UNDO_ACTION
                ) {
                    viewModel.restoreItem(target)
                }
                pendingDelete = null
            }
        )
    }
}

@Composable
private fun TripItemContent(
    items: List<PackingItem>,
    openedKey: Any?,
    onOpenedChange: (Any?) -> Unit,
    onToggle: (PackingItem, Boolean) -> Unit,
    onDelete: (PackingItem) -> Unit,
    onAdd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (items.isEmpty()) {
            EmptyState(
                title = "清单还是空的",
                message = "把证件、衣物、充电器这些一件件加进来，出发前照着勾。",
                actionLabel = "添加条目",
                onAction = onAdd
            )
        } else {
            val checkedCount = items.count { it.checked }
            AppSurfaceCard(
                // 圆角走形状令牌（F6）：20dp 卡片收敛到 large。
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                shadowElevation = 8.dp
            ) {
                Text(
                    text = if (checkedCount == items.size) {
                        "全部 ${items.size} 件都装好了，可以出发啦"
                    } else {
                        "已装 $checkedCount / ${items.size} 件"
                    },
                    // 字号走主题字阶（F6）：14sp 进度文字用 titleSmall 承载强调。
                    style = MaterialTheme.typography.titleSmall,
                    color = if (checkedCount == items.size) LemonEnd else OrangeStart
                )
            }
            items.forEach { item ->
                SwipeRevealItem(
                    itemKey = item.id,
                    openedItemKey = openedKey,
                    onOpenedItemChange = onOpenedChange,
                    actions = listOf(
                        SwipeActionSpec(
                            label = "删除",
                            icon = Icons.Default.Delete,
                            backgroundColor = StatusExpired
                        ) { onDelete(item) }
                    )
                ) { closeActions, _ ->
                    PackingItemRow(
                        item = item,
                        onToggle = { checked ->
                            closeActions()
                            onToggle(item, checked)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PackingItemRow(item: PackingItem, onToggle: (Boolean) -> Unit) {
    AppSurfaceCard(
        // 圆角走形状令牌（F6）：20dp 卡片收敛到 large。
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = item.checked,
                onCheckedChange = onToggle,
                colors = CheckboxDefaults.colors(
                    checkedColor = LemonEnd,
                    uncheckedColor = TextHint
                )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (item.quantity > 1) "${item.name} ×${item.quantity}" else item.name,
                    // 字号走主题字阶（F6）：titleMedium 为 16sp。
                    style = MaterialTheme.typography.titleMedium,
                    color = if (item.checked) TextHint else TextPrimary,
                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.category.isNotBlank()) {
                    PillTag(
                        text = item.category,
                        backgroundColor = SurfaceWarmDeep,
                        contentColor = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AddItemDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, quantity: Int, category: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var quantity by remember { mutableIntStateOf(1) }

    AppDialog(
        title = "添加要带的东西",
        onDismissRequest = onDismiss,
        confirmText = "添加",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name, quantity.coerceAtLeast(1), category) }
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(MAX_ITEM_NAME) },
            singleLine = true,
            label = { Text("名称") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = category,
            onValueChange = { category = it.take(MAX_CATEGORY) },
            singleLine = true,
            label = { Text("分组（可选，如「证件」）") },
            modifier = Modifier.fillMaxWidth()
        )
        // 数量改步进器（P2）：原先用 Slider 拖，一粒一粒对不准、也无从点按；
        // 换成 −/数字/+ 三件套，每次整步增减，按钮保证 48dp 最小可点区。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "数量",
                // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { quantity = (quantity - 1).coerceAtLeast(1) },
                enabled = quantity > 1,
                // 触达面积达标（F7）：步进按钮补足 48dp 最小可点区。
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "减少数量",
                    tint = OrangeStart
                )
            }
            Text(
                text = quantity.toString(),
                // 字号走主题字阶（F6）：titleMedium 为 16sp。
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 36.dp)
            )
            IconButton(
                onClick = { quantity = (quantity + 1).coerceAtMost(MAX_QUANTITY) },
                enabled = quantity < MAX_QUANTITY,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "增加数量",
                    tint = OrangeStart
                )
            }
        }
    }
}

private const val MAX_ITEM_NAME = 30
private const val MAX_CATEGORY = 12
private const val MAX_QUANTITY = 20
