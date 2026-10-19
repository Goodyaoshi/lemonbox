package com.goodyaoshi.lemonbox.ui.screen.ledger

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_INCOME
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord.Companion.TYPE_TRANSFER
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecurringRule
import com.goodyaoshi.lemonbox.data.local.entity.LedgerTemplate
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.SegmentedTabs
import com.goodyaoshi.lemonbox.ui.components.SwipeActionSpec
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.ui.components.ledgerSignedAmountText
import com.goodyaoshi.lemonbox.ui.components.ledgerTypeColor
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.LedgerPlanViewModel
import com.goodyaoshi.lemonbox.util.RecurringClock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记账计划（F05）：记账模板与周期记账规则的管理页。
 * 模板可删除；周期规则可启停、删除，并能手动补生成到点账单。
 */
@Composable
fun LedgerPlanScreen(
    onBack: () -> Unit,
    // 模板由「记一笔」时保存产生，空态需要一条直达通道（P2）；带默认值不破坏既有调用方。
    onNavigateToRecordEdit: (Long?) -> Unit = {},
    viewModel: LedgerPlanViewModel = hiltViewModel()
) {
    val templates by viewModel.templates.collectAsState()
    val rules by viewModel.rules.collectAsState()
    val appSnackbar = LocalAppSnackbar.current

    var tabIndex by remember { mutableStateOf(0) }
    var openedKey by remember { mutableStateOf<Any?>(null) }
    var pendingDeleteTemplate by remember { mutableStateOf<LedgerTemplate?>(null) }
    var pendingDeleteRule by remember { mutableStateOf<LedgerRecurringRule?>(null) }

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
                    text = "记账计划",
                    // 字号走主题字阶（F6）：titleLarge 为 18sp。
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SegmentedTabs(
                    labels = listOf("记账模板", "周期记账"),
                    selectedIndex = tabIndex,
                    onSelect = { tabIndex = it }
                )
            }

            if (tabIndex == 0) {
                LazyColumn(
                    // 底部避开系统导航栏（P2-11）。
                    modifier = Modifier
                        .weight(1f)
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (templates.isEmpty()) {
                        item(key = "empty-template") {
                            EmptyState(
                                title = "还没有记账模板",
                                message = "记一笔时点「存为模板」，常用账单就能一键套用。",
                                // 空态给直达按钮（P2）：此前只有一段文字，用户走到末步无处可去。
                                actionLabel = "去记一笔",
                                onAction = { onNavigateToRecordEdit(null) },
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    } else {
                        item(key = "template-swipe-hint") { SwipeDeleteHint() }
                        items(templates, key = { it.id }) { template ->
                            SwipeRevealItem(
                                itemKey = template.id,
                                openedItemKey = openedKey,
                                onOpenedItemChange = { openedKey = it },
                                actions = listOf(
                                    SwipeActionSpec(
                                        label = "删除",
                                        icon = Icons.Filled.Delete,
                                        backgroundColor = StatusExpired,
                                        onClick = { pendingDeleteTemplate = template }
                                    )
                                )
                            ) { _, _ ->
                                PlanRow(
                                    title = template.name,
                                    subtitle = buildString {
                                        append(planTypeLabel(template.type))
                                        if (template.amount > 0) {
                                            append(" · ")
                                            append(ledgerSignedAmountText(template.type, template.amount))
                                        }
                                    },
                                    // 可见删除入口（P2-9）：补行内垃圾桶，不再只靠左滑发现。
                                    onDelete = { pendingDeleteTemplate = template }
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    // 底部避开系统导航栏（P2-11）。
                    modifier = Modifier
                        .weight(1f)
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "rule-tips") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "到点自动记账，长期不用也不会漏。",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "立即补生成",
                                style = MaterialTheme.typography.labelLarge,
                                color = ledgerTypeColor(TYPE_INCOME),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    // 触达面积达标（F7）：文案按钮补 48dp 最小热区。
                                    .minimumInteractiveComponentSize()
                                    // 圆角走形状令牌（F6）：small 为 8dp。
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        viewModel.fireNow { created ->
                                            appSnackbar?.postMessage(
                                                if (created > 0) "已补生成 $created 笔" else "没有到点的账单"
                                            )
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                    if (rules.isEmpty()) {
                        item(key = "empty-rule") {
                            EmptyState(
                                title = "还没有周期记账",
                                message = "记一笔时点「设为周期」，房租、水电这类账单就能自动生成。",
                                // 空态补直达按钮（P1-3）：「设为周期」藏在记一笔的「更多」里，这里给出口。
                                actionLabel = "去记一笔",
                                onAction = { onNavigateToRecordEdit(null) },
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    } else {
                        item(key = "rule-swipe-hint") { SwipeDeleteHint() }
                        items(rules, key = { it.id }) { rule ->
                            SwipeRevealItem(
                                itemKey = rule.id,
                                openedItemKey = openedKey,
                                onOpenedItemChange = { openedKey = it },
                                actions = listOf(
                                    SwipeActionSpec(
                                        label = "删除",
                                        icon = Icons.Filled.Delete,
                                        backgroundColor = StatusExpired,
                                        onClick = { pendingDeleteRule = rule }
                                    )
                                )
                            ) { _, _ ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // 圆角走形状令牌（F6）：large 为 20dp。
                                        .clip(MaterialTheme.shapes.large)
                                        .background(CardWhite)
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = rule.name,
                                            // 字号走主题字阶（F6）：titleSmall 为 14sp。
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = buildString {
                                                append("每 ${rule.repeatInterval}${RecurringClock.unitLabel(rule.unit)}")
                                                append(" · ")
                                                append(
                                                    if (rule.enabled) {
                                                        "下次 ${formatFireTime(rule.nextFireAt)}"
                                                    } else {
                                                        "已停用"
                                                    }
                                                )
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextHint,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = ledgerSignedAmountText(rule.type, rule.amount),
                                        // 字号走主题字阶（F6）：titleSmall 为 14sp。
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ledgerTypeColor(rule.type)
                                    )
                                    Switch(
                                        checked = rule.enabled,
                                        onCheckedChange = { viewModel.setRuleEnabled(rule.id, it) },
                                        modifier = Modifier
                                            .padding(start = 4.dp)
                                            // 读屏可达（P3-13）：开关关联规则名，不再只播报「开 / 关」。
                                            .semantics { contentDescription = rule.name }
                                    )
                                    // 可见删除入口（P2-9）：补行内垃圾桶，不再只靠左滑发现。
                                    IconButton(onClick = { pendingDeleteRule = rule }) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "删除${rule.name}",
                                            tint = TextHint,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDeleteTemplate?.let { template ->
        AppDialog(
            title = "删除模板",
            subtitle = "删除后模板不再出现在列表里，删错了可以撤销。",
            onDismissRequest = { pendingDeleteTemplate = null },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                viewModel.deleteTemplate(template.id)
                // 撤销删除（P3-11）：模板删除补可撤销提示条。
                appSnackbar?.showUndo("已删除「${template.name}」", DeleteCopy.UNDO_ACTION) {
                    viewModel.restoreTemplate(template.id)
                }
                pendingDeleteTemplate = null
            }
        ) {
            Text(
                text = "确定要删除模板「${template.name}」吗？",
                color = TextSecondary,
                // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    pendingDeleteRule?.let { rule ->
        AppDialog(
            title = "删除周期记账",
            subtitle = "删除后不再自动生成，已生成的账单会保留，删错了可以撤销。",
            onDismissRequest = { pendingDeleteRule = null },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                viewModel.deleteRule(rule.id)
                // 撤销删除（P3-11）：周期规则删除补可撤销提示条。
                appSnackbar?.showUndo("已删除「${rule.name}」", DeleteCopy.UNDO_ACTION) {
                    viewModel.restoreRule(rule.id)
                }
                pendingDeleteRule = null
            }
        ) {
            Text(
                text = "确定要删除「${rule.name}」吗？",
                color = TextSecondary,
                // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** 计划行：名称 + 说明副标题（模板列表使用），可选行内删除入口。 */
@Composable
private fun PlanRow(
    title: String,
    subtitle: String,
    onDelete: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 圆角走形状令牌（F6）：large 为 20dp。
            .clip(MaterialTheme.shapes.large)
            .background(CardWhite)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                // 字号走主题字阶（F6）：titleSmall 为 14sp。
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        // 可见删除入口（P2-9）：与分类页同款，点垃圾桶即可删除。
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除$title",
                    tint = TextHint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 删除方式提示（P2-9）：模板 / 周期规则此前删除完全不可发现，这里给一行「左滑可删除」。 */
@Composable
private fun SwipeDeleteHint() {
    Text(
        text = "左滑可删除",
        style = MaterialTheme.typography.bodySmall,
        color = TextHint
    )
}

private fun planTypeLabel(type: Int): String = when (type) {
    TYPE_INCOME -> "收入"
    TYPE_TRANSFER -> "转账"
    else -> "支出"
}

private val planFireTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 HH:mm")

private fun formatFireTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(planFireTimeFormatter)
