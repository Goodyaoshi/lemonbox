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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorInputBox
import com.goodyaoshi.lemonbox.ui.components.EditorSelectionChip
import com.goodyaoshi.lemonbox.ui.components.LedgerBudgetBar
import com.goodyaoshi.lemonbox.ui.components.SectionHeader
import com.goodyaoshi.lemonbox.ui.components.ledgerIconFor
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.LedgerBudgetViewModel
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath

/** 正在编辑的预算目标：categoryId 为 null 表示总预算。 */
private data class BudgetEditTarget(
    val categoryId: Long?,
    val title: String,
    val existingCents: Long
)

/** 预算页：月度总预算 + 分类预算 + 添加分类预算。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LedgerBudgetScreen(
    onBack: () -> Unit,
    viewModel: LedgerBudgetViewModel = hiltViewModel()
) {
    val budgetItems by viewModel.budgetItems.collectAsState()
    val categoriesWithoutBudget by viewModel.categoriesWithoutBudget.collectAsState()
    var editTarget by remember { mutableStateOf<BudgetEditTarget?>(null) }

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
                    text = "预算",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "total-header") {
                    SectionHeader(title = "月度预算", subtitle = "按当前账期统计，花超会变红")
                }
                item(key = "total") {
                    val total = budgetItems.firstOrNull { it.categoryId == null }
                    if (total == null) {
                        AppSurfaceCard(
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(18.dp),
                            shadowElevation = 12.dp
                        ) {
                            Text(
                                text = "还没有总预算",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "设一个每月总预算，超支时记账页会提醒你。",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(OrangeStart.copy(alpha = 0.12f))
                                    .clickable {
                                        editTarget = BudgetEditTarget(null, "月度总预算", 0L)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    text = "设置预算",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OrangeStart
                                )
                            }
                        }
                    } else {
                        LedgerBudgetBar(
                            title = "月度总预算",
                            subtitle = "已花 ${formatCents(total.spentCents)} / ${formatCents(total.budgetCents)}",
                            ratio = total.ratio,
                            over = total.over,
                            onClick = {
                                editTarget = BudgetEditTarget(null, "月度总预算", total.budgetCents)
                            }
                        )
                    }
                }
                val categoryBudgets = budgetItems.filter { it.categoryId != null }
                if (categoryBudgets.isNotEmpty()) {
                    item(key = "cat-header") {
                        SectionHeader(title = "分类预算", subtitle = "想精细点就给常花的分类单独设")
                    }
                    items(categoryBudgets, key = { "cat-${it.categoryId}" }) { item ->
                        LedgerBudgetBar(
                            title = item.categoryName.orEmpty(),
                            subtitle = "已花 ${formatCents(item.spentCents)} / ${formatCents(item.budgetCents)}",
                            ratio = item.ratio,
                            over = item.over,
                            onClick = {
                                editTarget = BudgetEditTarget(
                                    categoryId = item.categoryId,
                                    title = item.categoryName.orEmpty(),
                                    existingCents = item.budgetCents
                                )
                            }
                        )
                    }
                }
                if (categoriesWithoutBudget.isNotEmpty()) {
                    item(key = "add-header") {
                        SectionHeader(title = "添加分类预算", subtitle = "点一个分类，为它单独设预算")
                    }
                    item(key = "add-chips") {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            categoriesWithoutBudget.forEach { category ->
                                EditorSelectionChip(
                                    text = "+ ${category.name}",
                                    icon = ledgerIconFor(category.icon),
                                    selected = false,
                                    onClick = {
                                        editTarget =
                                            BudgetEditTarget(category.id, category.name, 0L)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editTarget?.let { target ->
        BudgetEditDialog(
            target = target,
            onDismissRequest = { editTarget = null },
            onConfirm = { text ->
                viewModel.setBudget(target.categoryId, text)
                editTarget = null
            }
        )
    }
}

/** 预算编辑弹窗：输入元金额；已有预算可一键删除（存 0 即取消）。 */
@Composable
private fun BudgetEditDialog(
    target: BudgetEditTarget,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var amountText by remember(target) {
        mutableStateOf(LedgerMath.centsToInputText(target.existingCents))
    }
    val hasExisting = target.existingCents > 0
    AppDialog(
        title = "设置${target.title}",
        subtitle = "输入每月预算金额（元），留空表示取消该预算。",
        onDismissRequest = onDismissRequest,
        confirmText = "保存",
        onConfirm = { onConfirm(amountText) },
        secondaryText = if (hasExisting) "删除预算" else null,
        onSecondary = if (hasExisting) {
            { onConfirm("") }
        } else {
            null
        }
    ) {
        EditorInputBox(
            value = amountText,
            onValueChange = { amountText = it },
            placeholder = "例如 2000"
        )
    }
}

/** 分 → 金额文本（带 ¥）。 */
private fun formatCents(cents: Long): String =
    DateUtil.formatCurrency(LedgerMath.centsToYuan(cents))
