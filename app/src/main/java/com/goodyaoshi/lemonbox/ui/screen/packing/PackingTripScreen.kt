package com.goodyaoshi.lemonbox.ui.screen.packing

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.roundToInt

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
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
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
                    onToggle = viewModel::setChecked,
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
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                shadowElevation = 8.dp
            ) {
                Text(
                    text = if (checkedCount == items.size) {
                        "全部 ${items.size} 件都装好了，可以出发啦"
                    } else {
                        "已装 $checkedCount / ${items.size} 件"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
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
        shape = RoundedCornerShape(20.dp),
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
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
    var quantity by remember { mutableStateOf(1f) }

    AppDialog(
        title = "添加要带的东西",
        onDismissRequest = onDismiss,
        confirmText = "添加",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name, quantity.roundToInt().coerceAtLeast(1), category) }
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
        Text(
            text = "数量：${quantity.roundToInt().coerceAtLeast(1)}",
            fontSize = 14.sp,
            color = TextSecondary
        )
        Slider(
            value = quantity,
            onValueChange = { quantity = it },
            valueRange = 1f..20f,
            steps = 18
        )
    }
}

private const val MAX_ITEM_NAME = 30
private const val MAX_CATEGORY = 12
