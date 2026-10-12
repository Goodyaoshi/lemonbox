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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.SwipeActionSpec
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.PackingListViewModel
import com.goodyaoshi.lemonbox.ui.viewmodel.PackingTripRow

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
                    text = "打包清单",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                TripContent(
                    rows = rows,
                    openedKey = openedKey,
                    onOpenedChange = { openedKey = it },
                    onOpen = onOpenTrip,
                    onDelete = viewModel::deleteTrip,
                    onAdd = { showCreate = true }
                )
                Spacer(modifier = Modifier.height(120.dp))
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 24.dp)
                .size(58.dp)
                .clip(CircleShape)
                .background(brush = Brush.linearGradient(colors = listOf(LemonStart, LemonEnd)))
                .clickable { showCreate = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "新建行程",
                tint = OnLemon,
                modifier = Modifier.size(28.dp)
            )
        }
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
}

@Composable
private fun TripContent(
    rows: List<PackingTripRow>,
    openedKey: Any?,
    onOpenedChange: (Any?) -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onAdd: () -> Unit
) {
    Column(
        modifier = Modifier
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
                        ) { onDelete(row.trip.id) }
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
        shape = RoundedCornerShape(22.dp),
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
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
                        fontSize = 12.sp,
                        color = TextHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Text(
                    text = progressLabel(row),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
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

    AppDialog(
        title = "新建行程",
        onDismissRequest = onDismiss,
        confirmText = "创建",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name, destination, null, null) }
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
    }
}

private fun progressLabel(row: PackingTripRow): String = when {
    row.totalCount == 0 -> "还没有要带的东西"
    row.checkedCount == row.totalCount -> "全部 ${row.totalCount} 件都装好了"
    else -> "已装 ${row.checkedCount}/${row.totalCount}"
}

private fun dateRangeLabel(start: String?, end: String?): String = when {
    start.isNullOrBlank() && end.isNullOrBlank() -> ""
    start.isNullOrBlank() -> end.orEmpty()
    end.isNullOrBlank() -> start
    else -> "$start 至 $end"
}

private const val MAX_NAME = 30
private const val MAX_DESTINATION = 30
