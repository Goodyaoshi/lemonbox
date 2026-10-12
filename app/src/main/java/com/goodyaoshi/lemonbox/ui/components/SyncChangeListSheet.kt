package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodyaoshi.lemonbox.data.backup.BackupChanges
import com.goodyaoshi.lemonbox.data.backup.FieldDiff
import com.goodyaoshi.lemonbox.data.backup.SyncChange
import com.goodyaoshi.lemonbox.data.backup.SyncChangeKind
import com.goodyaoshi.lemonbox.data.backup.SyncEntityKind
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.StatusNormal
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

/**
 * 逐条变更列表（S1/S2/S4）：把一次合并将带来的每条变化列出来让用户勾选，
 * 局域网同步（发起方 + 被连接方）与手动导入共用，替代旧的「只有三个数字」的范围对话框。
 *
 * 默认勾选「新增 + 修改」；「删除」是危险动作、真冲突双方都改过，都必须主动勾选
 * （勾选冲突 = 采用对方，不勾 = 保留本机）。取消（点按钮 / 下滑关闭）即整体放弃：
 * 同步场景中止同步，导入场景放弃这次导入。
 *
 * 修改条目可以点「看看改了啥」展开字段级 diff（S3），先看清楚再决定；冲突条目的字段差异
 * 直接默认展开铺在行下方（label｜本机值(划掉)｜对方值(高亮)），关键差异（如金额）折叠态也要看得见。
 *
 * 顶部一排筛选 chips（全部/新增/修改/删除/冲突）只影响显示、不动勾选结果；
 * 点「同步所选」后先弹确认摘要（删除类逐条 witness），「再看看」回到勾选状态且不丢勾选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncChangeListSheet(
    changes: BackupChanges,
    title: String,
    sourceLabel: String,
    actionLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (selectedKeys: Set<String>) -> Unit
) {
    // 默认勾选：新增与修改都进来；删除与真冲突必须主动勾选。
    var selected by remember(changes) {
        mutableStateOf(
            changes.changes
                .filter { it.kind != SyncChangeKind.DELETED && !it.conflict }
                .map { it.key }
                .toSet()
        )
    }
    // S3：点开 diff 详情的那条变更。
    var diffTarget by remember { mutableStateOf<SyncChange?>(null) }

    // 审计修复：筛选 chips 只影响「显示哪些条目」。勾选状态 selected 与它完全解耦——
    // 切筛选、回「全部」，已勾的 keys 原样保留，不会因为条目暂时看不见而丢勾选。
    var filter by remember(changes) { mutableStateOf(ChangeFilter.ALL) }
    val filterCounts = remember(changes) {
        ChangeFilter.entries.associateWith { option ->
            when (option) {
                ChangeFilter.ALL -> changes.changes.size
                ChangeFilter.ADDED -> changes.added
                ChangeFilter.MODIFIED -> changes.updated
                ChangeFilter.DELETED -> changes.deleted
                ChangeFilter.CONFLICT -> changes.conflicts
            }
        }
    }
    val visibleChanges = remember(changes, filter) {
        if (filter == ChangeFilter.ALL) changes.changes else changes.changes.filter { filter.matches(it) }
    }
    val grouped = remember(visibleChanges) { visibleChanges.groupBy { it.entity } }

    // 审计修复：执行前确认闸门。「同步所选」只是汇总勾选结果，真正写库前还要过一道摘要确认；
    // 发起方（chooseChanges）与被连接方（applyHostChanges）两条路径共用本 Sheet，一处加闸全覆盖。
    var showConfirmGate by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(CardWhite)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = sourceLabel,
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = "新增和修改默认勾选；「删除」和「冲突」必须主动勾选才会采用对方。" +
                    "这次没勾的内容，下次同步还会再问一次。",
                fontSize = 12.sp,
                color = TextHint,
                modifier = Modifier.padding(top = 2.dp)
            )
            // 筛选 chips（审计修复）：几十条时按种类直达冲突/删除，数量标在 chip 上；
            // 数量为 0 的种类不标数字，避免「冲突 0」这种噪音。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ChangeFilter.entries.forEach { option ->
                    val count = filterCounts.getValue(option)
                    EditorSelectionChip(
                        text = if (option == ChangeFilter.ALL || count == 0) {
                            option.label
                        } else {
                            "${option.label} $count"
                        },
                        selected = filter == option,
                        onClick = { filter = option }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // 筛选后可能为空（例如该批没有删除条目），给一句提示而不是白屏。
                if (grouped.isEmpty() && filter != ChangeFilter.ALL) {
                    item(key = "filter_empty_hint") {
                        Text(
                            text = "这个筛选下暂时没有条目，点「全部」看完整列表",
                            fontSize = 12.sp,
                            color = TextHint,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
                grouped.forEach { (entity, rows) ->
                    item(key = "header_${entity.name}") {
                        GroupHeader(
                            label = entity.label,
                            totalCount = rows.size,
                            selectedCount = rows.count { it.key in selected },
                            onToggleGroup = { selectAll ->
                                selected = if (selectAll) {
                                    selected + rows.map { it.key }.toSet()
                                } else {
                                    selected - rows.map { it.key }.toSet()
                                }
                            }
                        )
                    }
                    items(rows, key = { it.key }) { change ->
                        ChangeRow(
                            change = change,
                            checked = change.key in selected,
                            onToggle = { target ->
                                selected = if (target) selected + change.key else selected - change.key
                            },
                            onShowDiff = { diffTarget = change }
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val selectedChanges = changes.changes.filter { it.key in selected }
            Text(
                text = buildString {
                    append("已选 ${selectedChanges.size} 条：")
                    append("新增 ${selectedChanges.count { it.kind == SyncChangeKind.ADDED }} · ")
                    append("修改 ${selectedChanges.count { it.kind == SyncChangeKind.MODIFIED }} · ")
                    append("删除 ${selectedChanges.count { it.kind == SyncChangeKind.DELETED }}")
                    if (changes.conflicts > 0) {
                        // 冲突裁决的语义提醒：勾了=听对方的，没勾=保留本机的。
                        append("；其中冲突 ${selectedChanges.count { it.conflict }} 条用对方、" +
                            "${changes.conflicts - selectedChanges.count { it.conflict }} 条保留本机")
                    }
                },
                fontSize = 12.sp,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            GradientButton(
                text = "$actionLabel ${selectedChanges.size} 条",
                enabled = selectedChanges.isNotEmpty(),
                // 审计修复：不直接落库，先弹确认摘要（见下方 showConfirmGate）。
                onClick = { showConfirmGate = true }
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    // 执行前确认闸门（审计修复）：写库前最后一眼——写多少条、删了谁、跳过几条，
    // 删除类逐条 witness（删除是最高风险项）。「再看看」只是关掉弹窗、回到勾选状态，勾选一个不丢；
    // 下滑关闭 Sheet 仍是整体放弃，同步流程的取消能力保持不变。
    if (showConfirmGate) {
        val gateChanges = changes.changes.filter { it.key in selected }
        val gateAdded = gateChanges.count { it.kind == SyncChangeKind.ADDED }
        val gateUpdated = gateChanges.count { it.kind == SyncChangeKind.MODIFIED }
        val gateDeleted = gateChanges.count { it.kind == SyncChangeKind.DELETED }
        // 冲突裁决统计只算「勾了的冲突」：勾选 = 采用对方，没勾 = 保留本机。
        val gateConflicts = gateChanges.count { it.conflict }
        val gateSkipped = changes.changes.size - gateChanges.size
        val gateDeletedItems = gateChanges.filter { it.kind == SyncChangeKind.DELETED }
        AppDialog(
            title = "确认同步",
            subtitle = buildString {
                append("即将写入 ${gateChanges.size} 条：新增 $gateAdded · 修改 $gateUpdated · 删除 $gateDeleted")
                if (gateConflicts > 0) append("；冲突裁决 $gateConflicts 条（采用对方）")
                append("。")
                if (gateSkipped > 0) append("未勾选 $gateSkipped 条本次跳过，下次同步会再问。")
            },
            confirmText = "开始同步",
            onConfirm = {
                showConfirmGate = false
                onConfirm(gateChanges.map { it.key }.toSet())
            },
            dismissText = "再看看",
            onDismissRequest = { showConfirmGate = false },
            // 有删除项时确认按钮用 destructive 样式，视觉上再压一道警觉。
            destructiveConfirm = gateDeleted > 0
        ) {
            if (gateDeletedItems.isNotEmpty()) {
                Text(
                    text = "以下内容将被删除：",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                // 删除条目可能很多，限高滚动，避免摘要弹窗被撑爆。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    gateDeletedItems.forEach { deleted ->
                        Text(
                            text = "· ${deleted.title}",
                            fontSize = 12.sp,
                            color = StatusExpired
                        )
                    }
                }
            }
        }
    }

    diffTarget?.let { target ->
        if (target.diffs.isNotEmpty()) {
            DiffDialog(change = target, onDismiss = { diffTarget = null })
        }
    }
}

/**
 * 变更列表的筛选维度（审计修复）：只影响「显示哪些条目」，与勾选状态完全解耦。
 * 「冲突」筛的是 conflict==true 的条目（它们可能同时是修改类，不与种类筛选互斥）。
 */
private enum class ChangeFilter(
    val label: String,
    val kind: SyncChangeKind? = null,
    val conflictOnly: Boolean = false
) {
    ALL("全部"),
    ADDED(SyncChangeKind.ADDED.label, SyncChangeKind.ADDED),
    MODIFIED(SyncChangeKind.MODIFIED.label, SyncChangeKind.MODIFIED),
    DELETED(SyncChangeKind.DELETED.label, SyncChangeKind.DELETED),
    CONFLICT("冲突", conflictOnly = true);

    /** 该条目是否落在此筛选下。 */
    fun matches(change: SyncChange): Boolean = when {
        conflictOnly -> change.conflict
        kind != null -> change.kind == kind
        else -> true
    }
}

/** 分组头：整组勾选（三态）+ 组名与条数，保留「按类别整体取舍」的快捷粒度。 */
@Composable
private fun GroupHeader(
    label: String,
    totalCount: Int,
    selectedCount: Int,
    onToggleGroup: (Boolean) -> Unit
) {
    val state = when (selectedCount) {
        0 -> ToggleableState.Off
        totalCount -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Checkbox) { onToggleGroup(selectedCount != totalCount) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TriStateCheckbox(
            state = state,
            onClick = { onToggleGroup(selectedCount != totalCount) }
        )
        Text(
            text = "$label · $totalCount 条",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        )
        if (selectedCount > 0) {
            Text(text = "已选 $selectedCount", fontSize = 12.sp, color = TextHint)
        }
    }
}

/**
 * 单条变更：勾选框 + 标题/副标题 + 变更种类徽标；整行可点，热区不低于 48dp。
 * 审计修复：冲突条目的字段差异默认展开铺在行下方，关键差异（如金额）折叠态也要看得见；
 * 差异区域不响应点击（clickable 只在标题行上），避免「看差异时误触勾选、误改裁决」。
 */
@Composable
private fun ChangeRow(
    change: SyncChange,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onShowDiff: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceWarmDeep)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Checkbox) { onToggle(!checked) }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onToggle)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp)
            ) {
                Text(
                    text = change.title,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    maxLines = 1
                )
                change.subtitle?.let { subtitle ->
                    Text(text = subtitle, fontSize = 12.sp, color = TextHint, maxLines = 1)
                }
                if (change.conflict) {
                    Text(
                        text = "两边都改过：勾选=用对方，不勾=保留本机",
                        fontSize = 11.sp,
                        color = StatusExpired,
                        maxLines = 1
                    )
                }
            }
            // S3：「看看改了啥」入口只留给普通修改条目；冲突条目的差异已默认展开（见下）。
            if (change.diffs.isNotEmpty() && !change.conflict) {
                Text(
                    text = "改了啥",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OrangeStart,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onShowDiff() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
            KindBadge(change.kind, conflict = change.conflict)
            Spacer(Modifier.width(6.dp))
        }
        // 冲突条目默认展开差异（审计修复）：紧凑行内渲染，不再强制走弹窗。
        if (change.conflict && change.diffs.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 54.dp, end = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                change.diffs.forEach { diff ->
                    ConflictDiffLine(diff)
                }
            }
        }
    }
}

/** 变更种类徽标：新增绿、修改橙、删除红、冲突高亮红，与全 App 的状态色语义一致。 */
@Composable
private fun KindBadge(kind: SyncChangeKind, conflict: Boolean = false) {
    val color = when {
        conflict -> StatusExpired
        kind == SyncChangeKind.ADDED -> StatusNormal
        kind == SyncChangeKind.MODIFIED -> OrangeStart
        else -> StatusExpired
    }
    Text(
        text = if (conflict) "冲突" else kind.label,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** 字段级 diff 对话框（S3）：逐行展示「本地值 → 对方值」，先看清楚再决定勾不勾。 */
@Composable
private fun DiffDialog(change: SyncChange, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            Text(
                text = "知道了",
                color = OrangeStart,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        },
        title = {
            Text(text = change.title, fontSize = 16.sp, color = TextPrimary)
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(change.diffs) { diff ->
                    DiffLine(diff)
                }
            }
        },
        containerColor = CardWhite
    )
}

/** 一行字段对比：标签 + 本地值 → 对方值。 */
@Composable
private fun DiffLine(diff: FieldDiff) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceWarmDeep)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(text = diff.label, fontSize = 12.sp, color = TextHint)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = diff.localValue,
                fontSize = 13.sp,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 2
            )
            Text(text = " → ", fontSize = 13.sp, color = TextHint)
            Text(
                text = diff.remoteValue,
                fontSize = 13.sp,
                color = OrangeStart,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 2
            )
        }
    }
}

/**
 * 冲突条目的紧凑差异行（审计修复）：label｜本机值(划掉)｜对方值(高亮)。
 * 与 DiffDialog 里的 DiffLine 分开实现：这里是行内单行紧凑形态，本机值划掉、对方值高亮，
 * 让「勾了会失去什么、得到什么」在折叠态一眼可读，不再依赖弹窗。
 */
@Composable
private fun ConflictDiffLine(diff: FieldDiff) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = diff.label,
            fontSize = 11.sp,
            color = TextHint,
            maxLines = 1
        )
        Text(text = "｜", fontSize = 11.sp, color = TextHint)
        Text(
            text = diff.localValue,
            fontSize = 12.sp,
            color = TextPrimary,
            textDecoration = TextDecoration.LineThrough,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(text = "｜", fontSize = 11.sp, color = TextHint)
        Text(
            text = diff.remoteValue,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = OrangeStart,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}
