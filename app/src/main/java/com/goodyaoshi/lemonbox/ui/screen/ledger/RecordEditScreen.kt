package com.goodyaoshi.lemonbox.ui.screen.ledger

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_INCOME
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_TRANSFER
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecurringRule
import com.goodyaoshi.lemonbox.ui.components.AmountKeyboard
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorInputBox
import com.goodyaoshi.lemonbox.ui.components.EditorSectionLabel
import com.goodyaoshi.lemonbox.ui.components.EditorSelectionChip
import com.goodyaoshi.lemonbox.ui.components.ExpiryPickerDialog
import com.goodyaoshi.lemonbox.ui.components.FieldDiffLine
import com.goodyaoshi.lemonbox.ui.components.HeaderActionPill
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.PrimaryActionBar
import com.goodyaoshi.lemonbox.ui.components.SegmentedTabs
import com.goodyaoshi.lemonbox.ui.components.ledgerIconFor
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.RecordEditState
import com.goodyaoshi.lemonbox.ui.viewmodel.RecordEditViewModel
import com.goodyaoshi.lemonbox.util.LedgerMath
import com.goodyaoshi.lemonbox.util.RecurringClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 记一笔 / 编辑账单：类型切换 + 数字键盘 + 分类账户选择。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordEditScreen(
    onBack: () -> Unit,
    onOpenOcr: () -> Unit = {},
    viewModel: RecordEditViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val expenseCategories by viewModel.expenseCategories.collectAsState()
    val incomeCategories by viewModel.incomeCategories.collectAsState()
    val assets by viewModel.assets.collectAsState()
    val continuousEntry by viewModel.continuousEntry.collectAsState()
    val templates by viewModel.templates.collectAsState()
    // 全局提示通道（I10）：保存成功改走统一 Snackbar，替代易被忽略的系统 Toast。
    val appSnackbar = LocalAppSnackbar.current
    var showDatePicker by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showSaveTemplateDialog by remember { mutableStateOf(false) }
    var showRecurringDialog by remember { mutableStateOf(false) }
    // 次要项折叠区（IA-4）：默认收起，让主路径（类型/金额/分类/账户/日期/保存）先浮出来。
    var moreExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(expenseCategories, incomeCategories, assets) {
        viewModel.ensureDefaults(expenseCategories, incomeCategories)
    }

    LaunchedEffect(state.isSaved) {
        if (!state.isSaved) return@LaunchedEffect
        if (state.recordId > 0) {
            // 编辑保存（GAP-5）：有字段变更时先弹「改了什么」回执（下方对话框），确认后再返回；
            // 没有实际变更就直接退回，不打扰。
            if (state.savedDiff.isEmpty()) {
                appSnackbar?.postMessage("已记好这笔账")
                onBack()
            }
            return@LaunchedEffect
        }
        if (continuousEntry) {
            // 连续录入：清空表单留在本页，提示后可以接着记下一笔。
            appSnackbar?.showMessage("已记账，继续记下一笔")
            viewModel.resetForNext()
        } else {
            // 普通保存：本页随即返回，提示交给应用级作用域，避免随退栈被取消（I10）。
            appSnackbar?.postMessage("已记好这笔账")
            onBack()
        }
    }

    // I6：默认分类/账户带出后拍一张快照当「未改动」基准；账单保存（含连续录入清空）时基准失效重采。
    var loadedBaseline by remember(state.recordId, state.isSaved) { mutableStateOf<RecordEditState?>(null) }
    LaunchedEffect(state.initialized, state.recordId, state.isSaved) {
        if (state.initialized && !state.isSaved && loadedBaseline == null) {
            loadedBaseline = state
        }
    }
    val hasUnsavedInput = loadedBaseline?.let { base ->
        state.copy(isSaved = false) != base
    } ?: false
    val requestBack: () -> Unit = {
        if (hasUnsavedInput) showDiscardDialog = true else onBack()
    }
    // 系统返回手势/按键与左上角返回走同一套确认逻辑（I6）。
    BackHandler { requestBack() }

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
                IconButton(onClick = requestBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = TextPrimary
                    )
                }
                Text(
                    text = if (state.recordId > 0) "编辑账单" else "记一笔",
                    // 字号走主题字阶（F6）：titleLarge 为 18sp。
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.weight(1f))
                // 次要项收进「更多」折叠区（IA-4）：默认收起，主路径只留类型/金额/分类/账户/日期/保存。
                Text(
                    text = if (moreExpanded) "收起" else "更多",
                    // 字号走主题字阶（F6）：labelMedium 为 12sp。
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    modifier = Modifier
                        // 触达面积达标（F7）：文案胶囊补 48dp 最小热区。
                        .minimumInteractiveComponentSize()
                        // 胶囊走 CircleShape（F6）。
                        .clip(CircleShape)
                        .background(SurfaceWarmDeep)
                        .clickable { moreExpanded = !moreExpanded }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
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
                    // 圆角走形状令牌（F6）：extraLarge 为 24dp。
                    shape = MaterialTheme.shapes.extraLarge,
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
                                    // 字号走主题字阶（F6）：titleLarge 为 18sp。
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = TextSecondary
                                )
                                Text(
                                    text = state.amountText.ifEmpty { "0" },
                                    // 字号走主题字阶（F6）：headlineLarge 为 28sp。
                                    style = MaterialTheme.typography.headlineLarge,
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
                                    // 字号走主题字阶（F6）：titleSmall 为 14sp。
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    modifier = Modifier.padding(start = 2.dp, top = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                // 触达面积达标（F7）：日期胶囊补 48dp 最小热区。
                                .minimumInteractiveComponentSize()
                                // 胶囊走 CircleShape（F6）。
                                .clip(CircleShape)
                                .background(SurfaceWarmDeep)
                                .clickable { showDatePicker = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = formatDateLabel(state.recordDate),
                                // 字号走主题字阶（F6）：titleSmall 为 14sp。
                                style = MaterialTheme.typography.titleSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }

                // 次要项收进折叠区（IA-4）：拍照/连续/模板/存模板/周期/备注默认收起。
                if (moreExpanded) {
                    if (state.recordId == 0L) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // OCR 前置入口（IA-3）：拍照识别发票金额直接预填。
                            Text(
                                text = "拍照识别",
                                // 字号走主题字阶（F6）：labelMedium 为 12sp。
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary,
                                modifier = Modifier
                                    // 触达面积达标（F7）：文案胶囊补 48dp 最小热区。
                                    .minimumInteractiveComponentSize()
                                    // 胶囊走 CircleShape（F6）。
                                    .clip(CircleShape)
                                    .background(SurfaceWarmDeep)
                                    .clickable(onClick = onOpenOcr)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                            Text(
                                text = if (continuousEntry) "连续录入·开" else "连续录入",
                                // 字号走主题字阶（F6）：labelMedium 为 12sp。
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (continuousEntry) TagOrangeText else TextSecondary,
                                modifier = Modifier
                                    // 触达面积达标（F7）：文案胶囊补 48dp 最小热区。
                                    .minimumInteractiveComponentSize()
                                    // 胶囊走 CircleShape（F6）。
                                    .clip(CircleShape)
                                    .background(if (continuousEntry) TagOrange else SurfaceWarmDeep)
                                    .clickable { viewModel.toggleContinuousEntry(!continuousEntry) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    // F05：记账模板一键套用（有模板时才出现）。
                    if (templates.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            templates.forEach { template ->
                                EditorSelectionChip(
                                    text = template.name,
                                    selected = false,
                                    onClick = { viewModel.applyTemplate(template) }
                                )
                            }
                        }
                    }

                    // F05：把当前填写存为模板 / 设为周期记账（仅新增记账时提供）。
                    if (state.recordId == 0L) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HeaderActionPill(
                                icon = Icons.Filled.BookmarkAdd,
                                label = "存为模板",
                                onClick = { showSaveTemplateDialog = true }
                            )
                            HeaderActionPill(
                                icon = Icons.Filled.Repeat,
                                label = "设为周期",
                                onClick = { showRecurringDialog = true }
                            )
                        }
                    }

                    // 备注挪到折叠区（IA-4）：主路径只留类型/金额/分类/账户/日期/保存。
                    Box(modifier = Modifier.padding(top = 14.dp)) {
                        EditorSectionLabel(label = "备注")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    EditorInputBox(
                        value = state.remark,
                        onValueChange = viewModel::updateRemark,
                        placeholder = "记点什么…"
                    )
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
                            // 字号走主题字阶（F6）：titleSmall 为 14sp。
                            style = MaterialTheme.typography.titleSmall,
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
                PrimaryActionBar(
                    text = "保存",
                    enabled = state.canSave,
                    onClick = viewModel::save,
                    horizontalPadding = 0.dp,
                    verticalPadding = 0.dp
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    if (state.conflict) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConflict,
            title = { Text("这笔账单在别处被修改过") },
            text = {
                Text("另一台设备（或本机其它操作）在你编辑期间改动了它。继续保存会用你当前的内容覆盖对方；也可以放弃你的改动、载入最新版本。")
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmOverwrite) { Text("仍然覆盖") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::discardConflictAndReload) { Text("载入最新") }
            }
        )
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

    // GAP-5：编辑保存后的字段级变更回执（改前 → 改后），逐行展示，确认后再返回。
    if (state.isSaved && state.recordId > 0 && state.savedDiff.isNotEmpty()) {
        AppDialog(
            title = "这笔账改好了",
            subtitle = "以下是这次改动的字段：",
            onDismissRequest = onBack,
            confirmText = "好",
            dismissText = null,
            onConfirm = onBack
        ) {
            state.savedDiff.forEach { diff ->
                FieldDiffLine(diff)
            }
        }
    }

    // I6：有未保存内容时返回先确认，避免误触返回键丢掉这笔账。
    if (showDiscardDialog) {
        AppDialog(
            title = "放弃修改？",
            subtitle = "这笔账单还没保存，返回就会丢失。",
            onDismissRequest = { showDiscardDialog = false },
            confirmText = "放弃",
            destructiveConfirm = true,
            onConfirm = {
                showDiscardDialog = false
                onBack()
            },
            dismissText = "继续填"
        ) {
            Text(
                text = "确定要离开并丢弃当前填写的内容吗？",
                // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }

    // F05：把当前填写存为模板。
    if (showSaveTemplateDialog) {
        var templateName by remember { mutableStateOf(state.remark.trim()) }
        AppDialog(
            title = "存为模板",
            subtitle = "把当前填写的参数保存下来，下次记一笔时一键套用。",
            onDismissRequest = { showSaveTemplateDialog = false },
            confirmText = "保存",
            confirmEnabled = templateName.isNotBlank(),
            onConfirm = {
                if (viewModel.saveAsTemplate(templateName)) {
                    showSaveTemplateDialog = false
                    appSnackbar?.postMessage("已存为模板")
                }
            }
        ) {
            EditorInputBox(
                value = templateName,
                onValueChange = { templateName = it },
                placeholder = "模板名称，比如 早餐"
            )
        }
    }

    // F05：把当前填写设为周期记账规则。
    if (showRecurringDialog) {
        val units = LedgerRecurringRule.RepeatUnit.entries
        var ruleName by remember { mutableStateOf(state.remark.trim()) }
        var unitIndex by remember { mutableStateOf(units.indexOf(LedgerRecurringRule.RepeatUnit.MONTH)) }
        var interval by remember { mutableStateOf(1) }
        AppDialog(
            title = "设为周期记账",
            subtitle = "到点会自动生成一笔账单，可在记账计划的入口里随时停用。",
            onDismissRequest = { showRecurringDialog = false },
            confirmText = "开启",
            confirmEnabled = ruleName.isNotBlank() && state.canSave,
            onConfirm = {
                if (viewModel.createRecurringRule(ruleName, units[unitIndex], interval)) {
                    showRecurringDialog = false
                    appSnackbar?.postMessage("已开启周期记账")
                }
            }
        ) {
            EditorInputBox(
                value = ruleName,
                onValueChange = { ruleName = it },
                placeholder = "规则名称，比如 房租"
            )
            Spacer(modifier = Modifier.height(14.dp))
            EditorSectionLabel(label = "周期")
            Spacer(modifier = Modifier.height(8.dp))
            SegmentedTabs(
                labels = listOf("每天", "每周", "每月", "每年"),
                selectedIndex = unitIndex,
                onSelect = { unitIndex = it }
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3).forEach { n ->
                    EditorSelectionChip(
                        text = "每 $n ${RecurringClock.unitLabel(units[unitIndex])}",
                        selected = interval == n,
                        onClick = { interval = n }
                    )
                }
            }
            if (!state.canSave) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "先填好金额和账户，才能开启周期记账。",
                    // 字号走主题字阶（F6）：titleSmall 为 14sp。
                    style = MaterialTheme.typography.titleSmall,
                    color = TextSecondary
                )
            }
        }
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
