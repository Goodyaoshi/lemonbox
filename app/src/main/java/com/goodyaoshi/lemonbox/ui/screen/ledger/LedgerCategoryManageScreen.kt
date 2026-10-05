package com.goodyaoshi.lemonbox.ui.screen.ledger

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.EditorInputBox
import com.goodyaoshi.lemonbox.ui.components.EditorSectionLabel
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.components.LedgerIconOption
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SegmentedTabs
import com.goodyaoshi.lemonbox.ui.components.SwipeActionSpec
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.ui.components.ledgerIconFor
import com.goodyaoshi.lemonbox.ui.components.ledgerIconOptions
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TagBlue
import com.goodyaoshi.lemonbox.ui.theme.TagBlueText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.LedgerCategoryManageViewModel

/**
 * 记账分类管理：支出 / 收入两套分类的增删改。
 * 内置保护分类（「其他」）不可编辑与删除。
 */
@Composable
fun LedgerCategoryManageScreen(
    onBack: () -> Unit,
    viewModel: LedgerCategoryManageViewModel = hiltViewModel()
) {
    val kind by viewModel.kind.collectAsState()
    val categories by viewModel.categories.collectAsState()
    var editingCategory by remember { mutableStateOf<LedgerCategory?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var openedCategoryId by remember { mutableStateOf<Long?>(null) }
    var pendingDelete by remember { mutableStateOf<LedgerCategory?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
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
                Text(
                    text = "记账分类",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SegmentedTabs(
                    labels = listOf("支出分类", "收入分类"),
                    selectedIndex = if (kind == LedgerCategory.KIND_INCOME) 1 else 0,
                    onSelect = { index ->
                        viewModel.setKind(
                            if (index == 1) LedgerCategory.KIND_INCOME else LedgerCategory.KIND_EXPENSE
                        )
                    }
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(categories, key = { it.id }) { category ->
                    if (category.isProtected) {
                        LedgerCategoryRow(
                            category = category,
                            onClick = null
                        )
                    } else {
                        SwipeRevealItem(
                            itemKey = category.id,
                            openedItemKey = openedCategoryId,
                            onOpenedItemChange = { openedCategoryId = it as Long? },
                            actions = listOf(
                                SwipeActionSpec(
                                    label = "删除",
                                    icon = Icons.Filled.Delete,
                                    backgroundColor = StatusExpired,
                                    onClick = { pendingDelete = category }
                                )
                            )
                        ) { _, _ ->
                            LedgerCategoryRow(
                                category = category,
                                onClick = { editingCategory = category }
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
            ) {
                GradientButton(
                    text = "添加分类",
                    onClick = { showAddDialog = true }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showAddDialog) {
        LedgerCategoryEditDialog(
            existing = null,
            onDismissRequest = { showAddDialog = false },
            onConfirm = { name, iconKey ->
                viewModel.saveCategory(null, name, iconKey) { showAddDialog = false }
            }
        )
    }

    editingCategory?.let { category ->
        LedgerCategoryEditDialog(
            existing = category,
            onDismissRequest = { editingCategory = null },
            onConfirm = { name, iconKey ->
                viewModel.saveCategory(category.id, name, iconKey) { editingCategory = null }
            }
        )
    }

    pendingDelete?.let { category ->
        AppDialog(
            title = "删除分类",
            subtitle = "历史账单会保留，只是不再显示这个分类。",
            onDismissRequest = { pendingDelete = null },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                viewModel.deleteCategory(category.id) { }
                pendingDelete = null
            }
        ) {
            Text(
                text = "确定要删除「${category.name}」吗？",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

/** 分类行：图标 + 名称；保护分类带「内置」标签且不可点击。 */
@Composable
private fun LedgerCategoryRow(
    category: LedgerCategory,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardWhite)
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(OrangeTint),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = ledgerIconFor(category.icon),
                contentDescription = null,
                tint = OrangeStart,
                modifier = Modifier.size(19.dp)
            )
        }
        Text(
            text = category.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        )
        if (category.isProtected) {
            PillTag(
                text = "内置",
                backgroundColor = TagBlue,
                contentColor = TagBlueText
            )
        } else {
            Text(
                text = "点击编辑",
                fontSize = 11.sp,
                color = TextHint
            )
        }
    }
}

/** 分类编辑弹窗：名称 + 图标九宫格。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LedgerCategoryEditDialog(
    existing: LedgerCategory?,
    onDismissRequest: () -> Unit,
    onConfirm: (name: String, iconKey: String) -> Unit
) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var iconKey by remember(existing) {
        mutableStateOf(existing?.icon?.takeIf { it.isNotBlank() } ?: "other")
    }
    AppDialog(
        title = if (existing == null) "添加分类" else "编辑分类",
        onDismissRequest = onDismissRequest,
        confirmText = "保存",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name, iconKey) }
    ) {
        EditorInputBox(
            value = name,
            onValueChange = { name = it },
            placeholder = "分类名称，比如 宠物、人情"
        )
        Spacer(modifier = Modifier.height(12.dp))
        EditorSectionLabel(label = "图标")
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ledgerIconOptions.forEach { option ->
                IconPickCell(
                    option = option,
                    selected = iconKey == option.key,
                    onClick = { iconKey = option.key }
                )
            }
        }
    }
}

/** 分类图标九宫格单元（分类管理页与资产负债登记页共用）。 */
@Composable
internal fun IconPickCell(
    option: LedgerIconOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) OrangeStart.copy(alpha = 0.16f) else SurfaceWarmDeep)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = option.icon,
            contentDescription = option.key,
            tint = if (selected) OrangeStart else TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}
