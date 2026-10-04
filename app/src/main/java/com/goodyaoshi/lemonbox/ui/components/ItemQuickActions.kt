package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SageAccent
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarm
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

/**
 * 一件物品可执行的全部操作，滑动一级动作与「更多」面板共用同一份回调。
 * 三个状态维度（使用进度 / 物品去向 / 是否需要补货）都在这里，避免只有「用完」一个出口。
 */
data class ItemActionHandlers(
    val onEdit: () -> Unit,
    val onMarkUsed: () -> Unit,
    /** 「用1件」：数量减 1，减到 0 自动记为已用完。 */
    val onConsumeOne: () -> Unit,
    val onSetUsageStatus: (Int) -> Unit,
    val onSetDisposition: (Int) -> Unit,
    val onToggleRestock: () -> Unit,
    val onDelete: () -> Unit
)

/** 「更多」动作用中性灰蓝，与情境动作区分开。 */
private val MoreActionColor = Color(0xFF6E8A96)

/**
 * 构造左滑露出区的动作：最多一个随物品状态变化的高频动作，外加一个「更多」。
 * 高频动作只在物品还在库时才有意义；已离手（借出/送人/丢弃）的物品只保留「更多」。
 */
@Composable
fun buildItemSwipeActions(
    item: Item,
    handlers: ItemActionHandlers,
    onMore: () -> Unit
): List<SwipeActionSpec> {
    val primaryColor = OrangeStart
    val restockColor = LemonStart

    // 多件物品「开始用」后可以逐件消耗：先给「用1件」，再给「用完」。
    val contextual = mutableListOf<SwipeActionSpec>()
    when {
        item.disposition != Item.DISPOSITION_IN_STOCK -> Unit
        item.usageStatus == Item.USAGE_UNUSED -> contextual += SwipeActionSpec(
            label = "开始用",
            icon = Icons.Default.PlayArrow,
            backgroundColor = primaryColor,
            onClick = { handlers.onSetUsageStatus(Item.USAGE_IN_USE) }
        )
        item.usageStatus == Item.USAGE_IN_USE -> {
            if (item.quantity > 1) {
                contextual += SwipeActionSpec(
                    label = "用1件",
                    icon = Icons.Default.Remove,
                    backgroundColor = primaryColor,
                    onClick = handlers.onConsumeOne
                )
            }
            contextual += SwipeActionSpec(
                label = "用完",
                icon = Icons.Default.TaskAlt,
                backgroundColor = primaryColor,
                onClick = handlers.onMarkUsed
            )
        }
        item.usageStatus == Item.USAGE_USED_UP && !item.needRestock -> contextual += SwipeActionSpec(
            label = "加待买",
            icon = Icons.Default.ShoppingCart,
            backgroundColor = restockColor,
            contentColor = OnLemon,
            onClick = handlers.onToggleRestock
        )
    }
    return contextual + SwipeActionSpec(
        label = "更多",
        icon = Icons.Default.MoreHoriz,
        backgroundColor = MoreActionColor,
        onClick = onMore
    )
}

/**
 * 「更多」面板：把使用进度、物品去向、待买开关、编辑与删除收在一处。
 * 一级滑动区因此只保留一两个高频动作，完整能力仍然触手可及。
 */
@Composable
fun ItemMoreActionsDialog(
    item: Item,
    handlers: ItemActionHandlers,
    onDismissRequest: () -> Unit
) {
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
                    text = item.name,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "完整状态与操作，选中后立即保存",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 6.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    StatusChipSection(
                        dimension = StatusDimension.USAGE,
                        selectedCode = item.usageStatus,
                        onSelect = handlers.onSetUsageStatus
                    )
                    StatusChipSection(
                        dimension = StatusDimension.DISPOSITION,
                        selectedCode = item.disposition,
                        onSelect = handlers.onSetDisposition
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    MoreActionRow(
                        icon = Icons.Default.ShoppingCart,
                        title = if (item.needRestock) "取消待买" else "加入待买",
                        subtitle = if (item.needRestock) "从待买清单里移除" else "记进待买清单，方便补货",
                        tint = LemonStart,
                        onClick = {
                            handlers.onToggleRestock()
                            onDismissRequest()
                        }
                    )
                    MoreActionRow(
                        icon = Icons.Default.Edit,
                        title = "编辑",
                        subtitle = "修改名称、分类、位置、价格等",
                        tint = SageAccent,
                        onClick = {
                            onDismissRequest()
                            handlers.onEdit()
                        }
                    )
                    MoreActionRow(
                        icon = Icons.Default.Delete,
                        title = "删除",
                        subtitle = "移入回收站，30 天内可恢复",
                        tint = StatusExpired,
                        destructive = true,
                        onClick = {
                            onDismissRequest()
                            handlers.onDelete()
                        }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(SurfaceWarm)
                            .clickable(onClick = onDismissRequest)
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "关闭",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/** 一个状态维度的候选状态：横向可滚动，选中项用该状态自身的配色高亮。 */
@Composable
private fun StatusChipSection(
    dimension: StatusDimension,
    selectedCode: Int,
    onSelect: (Int) -> Unit
) {
    val options = statusOptionsFor(dimension)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
    ) {
        Text(
            text = statusDimensionTitle(dimension),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                val selected = option.code == selectedCode
                val (container, content) = itemStatusColors(dimension, option.code)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) container else SurfaceWarm)
                        .clickable { onSelect(option.code) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = option.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) content else TextHint
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (destructive) StatusExpired else TextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextHint
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextHint
        )
    }
}