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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.ExpiryPickerDialog
import com.goodyaoshi.lemonbox.ui.components.LemonFab
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.SwipeActionSpec
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.PackingListViewModel
import com.goodyaoshi.lemonbox.ui.viewmodel.PackingTripRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 旅行打包清单列表（F16）：按行程展示，每个行程带打包进度，点进去勾选清单。
 */
@Composable
fun PackingListScreen(
    onBack: () -> Unit,
    onOpenTrip: (Long) -> Unit,
    viewModel: PackingListViewModel = hiltViewModel()
) {
    val rows by viewModel.rows.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var openedKey by remember { mutableStateOf<Any?>(null) }
    // 删除前先确认（P1）：滑动删除行程原先划一下就没了，还连带删掉整个清单；
    // 现在补二次确认，确认后仍可在提示条里撤销。
    var pendingDelete by remember { mutableStateOf<PackingTripRow?>(null) }
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
                    text = "打包清单",
                    // 字号走主题字阶（F6）：titleLarge 为 18sp。
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // 最大内容宽度约束并居中（P3-24）：宽屏下不再左右铺满，行宽与其它页一致。
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    TripContent(
                        rows = rows,
                        openedKey = openedKey,
                        onOpenedChange = { openedKey = it },
                        onOpen = onOpenTrip,
                        onRequestDelete = { pendingDelete = it },
                        onAdd = { showCreate = true }
                    )
                }
                Spacer(modifier = Modifier.height(120.dp))
            }
        }

        LemonFab(
            icon = Icons.Default.Add,
            contentDescription = "新建行程",
            onClick = { showCreate = true },
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }

    if (showCreate) {
        CreateTripDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, destination, start, end ->
                viewModel.createTrip(name, destination, start, end) { id ->
                    showCreate = false
                    onOpenTrip(id)
                }
            }
        )
    }

    pendingDelete?.let { target ->
        AppDialog(
            title = "删除行程",
            onDismissRequest = { pendingDelete = null },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                pendingDelete = null
                viewModel.deleteTrip(target.trip.id) { undo ->
                    // 删除完成后再挂提示条：撤销要拿到「删之前」的快照才能把条目一起救回来。
                    if (undo != null) {
                        appSnackbar?.showUndo("已删除「${target.trip.name}」", DeleteCopy.UNDO_ACTION) {
                            viewModel.restoreTrip(undo)
                        }
                    }
                }
            }
        ) {
            Text(
                text = if (target.totalCount > 0) {
                    "这个行程连同里面的 ${target.totalCount} 件打包条目会一起删掉，删错了可以撤销。"
                } else {
                    "确定删除这个行程吗？删错了可以撤销。"
                },
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun TripContent(
    rows: List<PackingTripRow>,
    openedKey: Any?,
    onOpenedChange: (Any?) -> Unit,
    onOpen: (Long) -> Unit,
    onRequestDelete: (PackingTripRow) -> Unit,
    onAdd: () -> Unit
) {
    Column(
        modifier = Modifier
            // 最大内容宽度（P3-24）：配合外层的居中 Box 生效，最大 600dp。
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (rows.isEmpty()) {
            EmptyState(
                title = "还没有行程",
                message = "建一个行程，把要带的东西列出来，出发前照着勾就不会落下。",
                actionLabel = "新建行程",
                onAction = onAdd
            )
        } else {
            rows.forEach { row ->
                SwipeRevealItem(
                    itemKey = row.trip.id,
                    openedItemKey = openedKey,
                    onOpenedItemChange = onOpenedChange,
                    actions = listOf(
                        SwipeActionSpec(
                            label = "删除",
                            icon = Icons.Default.Delete,
                            backgroundColor = StatusExpired
                        ) { onRequestDelete(row) }
                    )
                ) { closeActions, _ ->
                    TripCard(row = row, onClick = {
                        closeActions()
                        onOpen(row.trip.id)
                    })
                }
            }
        }
    }
}

@Composable
private fun TripCard(row: PackingTripRow, onClick: () -> Unit) {
    AppSurfaceCard(
        // 圆角走形状令牌（F6）：22dp 卡片收敛到 extraLarge（24dp）。
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        shadowElevation = 12.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    imageVector = Icons.Default.Luggage,
                    contentDescription = null,
                    tint = OrangeStart,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.trip.name,
                    // 字号走主题字阶（F6）：titleMedium 为 16sp。
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = buildString {
                    if (row.trip.destination.isNotBlank()) append(row.trip.destination)
                    val range = dateRangeLabel(row.trip.startDate, row.trip.endDate)
                    if (range.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(range)
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        // 字号走主题字阶（F6）：bodySmall 为 12sp。
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Text(
                    text = progressLabel(row),
                    // 字号走主题字阶（F6）：bodySmall 为 12sp。
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.totalCount > 0 && row.checkedCount == row.totalCount) {
                        LemonEnd
                    } else {
                        OrangeStart
                    },
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CreateTripDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, destination: String, start: String?, end: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    // 起止日期（P1）：出行日期原先是只读展示、建行程时无法录入，这里补上可选的出发/返回日期，
    // 与提醒页同一套 ExpiryPickerDialog 选择器，选完回填 yyyy-MM-dd。
    var startDate by remember { mutableStateOf<String?>(null) }
    var endDate by remember { mutableStateOf<String?>(null) }
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }

    if (pickingStart) {
        ExpiryPickerDialog(
            selectedDateMillis = startDate.toLocalMillis(),
            onDismissRequest = { pickingStart = false },
            title = "选择出发日期",
            onClear = {
                startDate = null
                pickingStart = false
            },
            onConfirm = { millis ->
                startDate = millis?.toDateString()
                pickingStart = false
            }
        )
    }
    if (pickingEnd) {
        ExpiryPickerDialog(
            selectedDateMillis = endDate.toLocalMillis(),
            onDismissRequest = { pickingEnd = false },
            title = "选择返回日期",
            onClear = {
                endDate = null
                pickingEnd = false
            },
            onConfirm = { millis ->
                endDate = millis?.toDateString()
                pickingEnd = false
            }
        )
    }

    AppDialog(
        title = "新建行程",
        subtitle = "出行日期可选，先定个大概也能建起来。",
        onDismissRequest = onDismiss,
        confirmText = "创建",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name, destination, startDate, endDate) }
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(MAX_NAME) },
            singleLine = true,
            label = { Text("行程名") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it.take(MAX_DESTINATION) },
            singleLine = true,
            label = { Text("目的地（可选）") },
            modifier = Modifier.fillMaxWidth()
        )
        TripDateField(label = "出发日期", value = startDate, onPick = { pickingStart = true })
        TripDateField(label = "返回日期", value = endDate, onPick = { pickingEnd = true })
    }
}

/** 表单里的日期行：显示已选日期，整行可点开选择器（清空走选择器内的「清空」）。 */
@Composable
private fun TripDateField(
    label: String,
    value: String?,
    onPick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onPick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Text(
                text = value ?: "未设置，点击选择",
                // 字号走主题字阶（F6）：bodySmall 为 12sp。
                style = MaterialTheme.typography.bodySmall,
                color = if (value == null) TextHint else OrangeStart
            )
        }
        Text(
            text = if (value == null) "选择" else "修改",
            // 字号走主题字阶（F6）：labelLarge 为 14sp。
            style = MaterialTheme.typography.labelLarge,
            color = OrangeStart
        )
    }
}

/** yyyy-MM-dd 文本转日期选择器需要的毫秒（按系统时区，避免时区导致日期偏移一天）。 */
private fun String?.toLocalMillis(): Long? = this
    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    ?.atStartOfDay(ZoneId.systemDefault())
    ?.toInstant()
    ?.toEpochMilli()

private fun Long.toDateString(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().toString()

private fun progressLabel(row: PackingTripRow): String = when {
    row.totalCount == 0 -> "还没有要带的东西"
    row.checkedCount == row.totalCount -> "全部 ${row.totalCount} 件都装好了（100%）"
    // 进度补百分比（P2）：光看「3/8」要心算，直接把完成度写清楚。
    else -> "已装 ${row.checkedCount}/${row.totalCount}（${row.checkedCount * 100 / row.totalCount}%）"
}

private fun dateRangeLabel(start: String?, end: String?): String = when {
    start.isNullOrBlank() && end.isNullOrBlank() -> ""
    start.isNullOrBlank() -> end.orEmpty()
    end.isNullOrBlank() -> start
    else -> "$start 至 $end"
}

private const val MAX_NAME = 30
private const val MAX_DESTINATION = 30
