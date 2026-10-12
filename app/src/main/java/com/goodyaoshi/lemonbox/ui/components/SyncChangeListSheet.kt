package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * 修改条目可以点「看看改了啥」展开字段级 diff（S3），先看清楚再决定。
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
    val grouped = remember(changes) { changes.changes.groupBy { it.entity } }

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
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
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
                onClick = { onConfirm(selectedChanges.map { it.key }.toSet()) }
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    diffTarget?.let { target ->
        if (target.diffs.isNotEmpty()) {
            DiffDialog(change = target, onDismiss = { diffTarget = null })
        }
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

/** 单条变更：勾选框 + 标题/副标题 + 变更种类徽标；整行可点，热区不低于 48dp。 */
@Composable
private fun ChangeRow(
    change: SyncChange,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onShowDiff: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceWarmDeep)
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
        // S3：有字段差异的修改条目给一个「看看改了啥」的入口，先看清楚再勾。
        if (change.diffs.isNotEmpty()) {
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
