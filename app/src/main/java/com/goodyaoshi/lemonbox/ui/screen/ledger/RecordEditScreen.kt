package com.goodyaoshi.lemonbox.ui.screen.ledger

import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_INCOME
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_TRANSFER
import com.goodyaoshi.lemonbox.ui.components.AmountKeyboard
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorInputBox
import com.goodyaoshi.lemonbox.ui.components.EditorSectionLabel
import com.goodyaoshi.lemonbox.ui.components.EditorSelectionChip
import com.goodyaoshi.lemonbox.ui.components.ExpiryPickerDialog
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SegmentedTabs
import com.goodyaoshi.lemonbox.ui.components.ledgerIconFor
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.RecordEditViewModel
import com.goodyaoshi.lemonbox.util.LedgerMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 记一笔 / 编辑账单：类型切换 + 数字键盘 + 分类账户选择。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordEditScreen(
    onBack: () -> Unit,
    viewModel: RecordEditViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val expenseCategories by viewModel.expenseCategories.collectAsState()
    val incomeCategories by viewModel.incomeCategories.collectAsState()
    val assets by viewModel.assets.collectAsState()
    val continuousEntry by viewModel.continuousEntry.collectAsState()
    val context = LocalContext.current
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(expenseCategories, incomeCategories, assets) {
        viewModel.ensureDefaults(expenseCategories, incomeCategories)
    }

    LaunchedEffect(state.isSaved) {
        if (!state.isSaved) return@LaunchedEffect
        if (continuousEntry) {
            // 连续录入：清空表单留在本页，提示后可以接着记下一笔。
            Toast.makeText(context, "已记账，继续记下一笔", Toast.LENGTH_SHORT).show()
            viewModel.resetForNext()
        } else {
            onBack()
        }
    }

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
                    text = if (state.recordId > 0) "编辑账单" else "记一笔",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.weight(1f))
                if (state.recordId == 0L) {
                    Text(
                        text = if (continuousEntry) "连续录入·开" else "连续录入",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (continuousEntry) TagOrangeText else TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (continuousEntry) TagOrange else SurfaceWarmDeep)
                            .clickable { viewModel.toggleContinuousEntry(!continuousEntry) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // 固定切换：账单类型常驻页头下方，表单再长滚动后也能切。
            SegmentedTabs(
                labels = listOf("支出", "收入", "转账"),
                selectedIndex = state.type,
                onSelect = { index ->
                    val type = when (index) {
                        1 -> TYPE_INCOME
                        2 -> TYPE_TRANSFER
                        else -> 0
                    }
                    viewModel.updateType(type)
                },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 2.dp, bottom = 12.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                AppSurfaceCard(
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(0.dp),
                    shadowElevation = 12.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "¥",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextSecondary
                                )
                                Text(
                                    text = state.amountText.ifEmpty { "0" },
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(start = 6.dp)
                                        .weight(1f, fill = false)
                                )
                            }
                            val previewCents = state.amountCents
                            if (state.isExpression && previewCents != null) {
                                Text(
                                    text = "= ¥" + LedgerMath.centsToInputText(previewCents),
                                    fontSize = 13.sp,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    modifier = Modifier.padding(start = 2.dp, top = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(SurfaceWarmDeep)
                                .clickable { showDatePicker = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = formatDateLabel(state.recordDate),
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }

                if (state.linkedItemId != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        PillTag(
                            text = "家当",
                            backgroundColor = TagOrange,
                            contentColor = TagOrangeText
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = state.linkedItemName ?: "关联物品",
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                    }
                }

                if (state.type != TYPE_TRANSFER) {
                    Box(modifier = Modifier.padding(top = 14.dp)) {
                        EditorSectionLabel(label = "分类")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val categories =
                        if (state.type == TYPE_INCOME) incomeCategories else expenseCategories
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.forEach { category ->
                            EditorSelectionChip(
                                text = category.name,
                                icon = ledgerIconFor(category.icon),
                                selected = state.categoryId == category.id,
                                onClick = { viewModel.selectCategory(category.id) }
                            )
                        }
                    }
                }

                if (state.type == TYPE_TRANSFER) {
                    Box(modifier = Modifier.padding(top = 14.dp)) {
                        EditorSectionLabel(label = "转出账户")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        assets.forEach { asset ->
                            EditorSelectionChip(
                                text = asset.name,
                                icon = ledgerIconFor(asset.icon),
                                selected = state.assetId == asset.id,
                                onClick = { viewModel.selectAsset(asset.id) }
                            )
                        }
                    }
                    Box(modifier = Modifier.padding(top = 12.dp)) {
                        EditorSectionLabel(label = "转入账户")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        assets.forEach { asset ->
                            EditorSelectionChip(
                                text = asset.name,
                                icon = ledgerIconFor(asset.icon),
                                selected = state.targetAssetId == asset.id,
                                onClick = { viewModel.selectTargetAsset(asset.id) }
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.padding(top = 14.dp)) {
                        EditorSectionLabel(label = "账户")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        assets.forEach { asset ->
                            EditorSelectionChip(
                                text = asset.name,
                                icon = ledgerIconFor(asset.icon),
                                selected = state.assetId == asset.id,
                                onClick = { viewModel.selectAsset(asset.id) }
                            )
                        }
                    }
                }

                Box(modifier = Modifier.padding(top = 14.dp)) {
                    EditorSectionLabel(label = "备注")
                }
                Spacer(modifier = Modifier.height(8.dp))
                EditorInputBox(
                    value = state.remark,
                    onValueChange = viewModel::updateRemark,
                    placeholder = "记点什么…"
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
            ) {
                AmountKeyboard(
                    onKey = viewModel::onAmountKey,
                    onBackspace = { viewModel.onAmountKey("⌫") }
                )
                Spacer(modifier = Modifier.height(12.dp))
                GradientButton(
                    text = "保存",
                    enabled = state.canSave,
                    onClick = viewModel::save
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    if (showDatePicker) {
        ExpiryPickerDialog(
            selectedDateMillis = state.recordDate
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
            onDismissRequest = { showDatePicker = false },
            onClear = { viewModel.selectDate(LocalDate.now()) },
            onConfirm = { millis ->
                millis?.let {
                    viewModel.selectDate(
                        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    )
                }
                showDatePicker = false
            },
            title = "选择记账日期",
            yearRange = (LocalDate.now().year - 8)..(LocalDate.now().year + 1)
        )
    }
}

/** 日期胶囊文案：今年省略年份。 */
private fun formatDateLabel(date: LocalDate): String {
    return if (date.year == LocalDate.now().year) {
        "${date.monthValue}月${date.dayOfMonth}日"
    } else {
        "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }
}
