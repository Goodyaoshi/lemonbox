package com.goodyaoshi.lemonbox.ui.components

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarm
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

/** 树形选择器的通用节点：分类与存放位置都可映射成它。 */
data class TreeNode(
    val id: Long,
    val parentId: Long?,
    val name: String
)

/**
 * 顺着 parentId 一路向上拼出「一级 / 二级 / 三级」的完整路径，
 * 供选择器字段与详情页展示使用；找不到时返回 null。
 */
fun buildTreePathLabel(selectedId: Long?, nodes: List<TreeNode>): String? {
    if (selectedId == null) return null
    val byId = nodes.associateBy { it.id }
    val parts = mutableListOf<String>()
    var cursor = byId[selectedId]
    var guard = 0
    while (cursor != null && guard++ < 64) {
        parts += cursor.name
        cursor = cursor.parentId?.let { byId[it] }
    }
    return parts.reversed().joinToString(" / ").ifBlank { null }
}

/** 只读的选择字段：展示当前选择的层级路径，点击唤起树形选择弹窗。 */
@Composable
fun EditorPickerField(
    value: String?,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasValue = !value.isNullOrBlank()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(OrangeTint)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (hasValue) value.orEmpty() else placeholder,
            fontSize = 15.sp,
            color = if (hasValue) TextPrimary else TextHint,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = null,
            tint = TextHint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 弹窗式树形选择器：以层级缩进 + 展开折叠的方式展示节点，
 * 支持选择任意层级（父级本身也可以被选中），再次选择可清除。
 */
@Composable
fun HierarchicalPickerDialog(
    title: String,
    nodes: List<TreeNode>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onDismissRequest: () -> Unit,
    emptyHint: String = "这里还没有可选项。"
) {
    var pendingSelection by remember { mutableStateOf(selectedId) }
    // 默认展开选中项所在的整条路径，打开弹窗就能看到当前层级。
    val expandedIds = remember(nodes, selectedId) {
        val byId = nodes.associateBy { it.id }
        val expanded = mutableStateMapOf<Long, Boolean>()
        var cursor = selectedId?.let { byId[it] }
        var guard = 0
        while (cursor != null && guard++ < 64) {
            expanded[cursor.id] = true
            cursor = cursor.parentId?.let { byId[it] }
        }
        expanded
    }
    val childrenByParent = nodes.groupBy { it.parentId }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.24f)),
            contentAlignment = Alignment.Center
        ) {
            AppSurfaceCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 420.dp),
                shape = RoundedCornerShape(30.dp),
                contentPadding = PaddingValues(22.dp),
                shadowElevation = 26.dp
            ) {
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "可以逐层展开，选择任意层级的节点。",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 6.dp)
                )

                if (nodes.isEmpty()) {
                    Text(
                        text = emptyHint,
                        fontSize = 13.sp,
                        color = TextHint,
                        modifier = Modifier.padding(top = 18.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(buildVisibleRows(childrenByParent, expandedIds), key = { it.node.id }) { row ->
                            PickerTreeRow(
                                row = row,
                                selected = pendingSelection == row.node.id,
                                onToggleExpand = {
                                    expandedIds[row.node.id] = !(expandedIds[row.node.id] ?: false)
                                },
                                onSelect = { pendingSelection = row.node.id }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PickerActionButton(
                        text = "清除选择",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSelect(null)
                            onDismissRequest()
                        }
                    )
                    PickerActionButton(
                        text = "确定",
                        modifier = Modifier.weight(1f),
                        highlight = true,
                        onClick = {
                            onSelect(pendingSelection)
                            onDismissRequest()
                        }
                    )
                }
            }
        }
    }
}

private data class PickerRow(
    val node: TreeNode,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean
)

private fun buildVisibleRows(
    childrenByParent: Map<Long?, List<TreeNode>>,
    expandedIds: Map<Long, Boolean>
): List<PickerRow> {
    val rows = mutableListOf<PickerRow>()

    fun append(parentId: Long?, depth: Int) {
        childrenByParent[parentId].orEmpty().sortedBy { it.name }.forEach { node ->
            val hasChildren = childrenByParent[node.id].orEmpty().isNotEmpty()
            val expanded = expandedIds[node.id] == true
            rows += PickerRow(
                node = node,
                depth = depth,
                hasChildren = hasChildren,
                expanded = expanded
            )
            if (expanded) append(node.id, depth + 1)
        }
    }

    append(null, 0)
    return rows
}

@Composable
private fun PickerTreeRow(
    row: PickerRow,
    selected: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) OrangeStart.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(start = (8 + row.depth * 16).dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (row.hasChildren) {
            Icon(
                imageVector = if (row.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                // 展开箭头是独立的可点目标，没有文字兜底，需报出当前状态（F16）。
                contentDescription = if (row.expanded) "收起子分类" else "展开子分类",
                tint = TextHint,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    // 声明 Button 角色，读屏才会播报「按钮」（F16）。
                    .clickable(role = Role.Button, onClick = onToggleExpand)
            )
        } else {
            Spacer(modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = row.node.name,
            fontSize = 15.sp,
            fontWeight = if (row.depth == 0) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) OrangeStart else TextPrimary,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = OrangeStart,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
internal fun PickerActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (highlight) OrangeStart.copy(alpha = 0.12f) else SurfaceWarm)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (highlight) OrangeStart else TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}