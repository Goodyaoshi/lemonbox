package com.goodyaoshi.lemonbox.ui.screen.expiry

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.ItemCard
import com.goodyaoshi.lemonbox.ui.components.SectionHeader
import com.goodyaoshi.lemonbox.ui.components.SegmentedTabs
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusWarning
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.viewmodel.ExpiryViewModel
import com.goodyaoshi.lemonbox.util.DateUtil

private data class ExpiryTab(val label: String, val days: Int?)

private val expiryTabs = listOf(
    ExpiryTab("全部", null),
    ExpiryTab("3天内", 3),
    ExpiryTab("7天内", 7),
    ExpiryTab("30天内", 30)
)

@Composable
fun ExpiryScreen(
    onBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToEdit: (Long) -> Unit = {},
    onNavigateToHousehold: () -> Unit = {},
    viewModel: ExpiryViewModel = hiltViewModel()
) {
    val expirableItems by viewModel.expirableItems.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    // 当前展开快捷操作的物品 id（null 表示动作弹窗关闭）。
    var actionTargetId by remember { mutableStateOf<Long?>(null) }

    val filteredItems = expirableItems
        .sortedBy { it.item.expireTime ?: Long.MAX_VALUE }
        .filter { itemDetail ->
            val expireTime = itemDetail.item.expireTime ?: return@filter false
            val days = DateUtil.daysUntil(expireTime)
            val threshold = expiryTabs[selectedTab].days
            // 已过期项 days < 0，应当比「3天内」更紧急地出现在筛选结果里，
            // 因此用 days <= threshold，而不是 days in 0..threshold（后者会把过期项全部漏掉）。
            threshold == null || days <= threshold.toLong()
        }

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
                    text = "到期提醒",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
            }

            // 固定筛选：与全 App 分段切换样式一致，整条等宽热区更大。
            SegmentedTabs(
                labels = expiryTabs.map { it.label },
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 2.dp, bottom = 10.dp)
            )

            if (filteredItems.isEmpty()) {
                // 空态分两种：压根没登记效期 vs 筛选筛没了（I5）。
                // 前者给「去家当补效期」的出路，避免有提示却无路可走；后者给「查看全部」一键回到全量。
                val isAllTab = selectedTab == 0
                EmptyState(
                    title = "没有效期提醒",
                    message = if (isAllTab) {
                        "还没有物品登记到期日，给易过期的东西补上效期就会出现在这里。"
                    } else {
                        "当前筛选条件下没有需要优先处理的物品。"
                    },
                    actionLabel = if (isAllTab) "去家当给物品补效期" else "查看全部效期",
                    actionIcon = if (isAllTab) Icons.Default.Add else Icons.Default.Refresh,
                    onAction = if (isAllTab) onNavigateToHousehold else ({ selectedTab = 0 }),
                    modifier = Modifier.padding(top = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp)
                ) {
                    item {
                        SectionHeader(
                            title = "效期列表",
                            subtitle = "共 ${filteredItems.size} 件物品需要关注",
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }
                    items(filteredItems, key = { it.item.id }) { itemDetail ->
                        ItemCard(
                            itemDetail = itemDetail,
                            onClick = { onNavigateToDetail(itemDetail.item.id) },
                            modifier = Modifier.padding(vertical = 6.dp),
                            // 快捷操作：用完 / 丢弃 / 编辑（F07）。
                            onMore = { actionTargetId = itemDetail.item.id }
                        )
                    }
                }
            }

            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                shape = RoundedCornerShape(24.dp),
                containerColor = TagOrange
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.72f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = null,
                            tint = StatusWarning
                        )
                    }
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(12.dp))
                    Column {
                        Text(
                            text = "小贴士",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            // 橙色浅底上改深琥珀字（TagOrangeText），保证与底色有足够对比度。
                            color = TagOrangeText
                        )
                        Text(
                            text = "建议你优先用掉快到期的东西，少浪费。",
                            fontSize = 12.sp,
                            color = TagOrangeText,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }

    // 快捷操作弹窗（F07）：用完 / 丢弃 / 编辑。
    val actionTarget = expirableItems.firstOrNull { it.item.id == actionTargetId }
    if (actionTarget != null) {
        AppDialog(
            title = "「${actionTarget.item.name}」怎么处理？",
            onDismissRequest = { actionTargetId = null }
        ) {
            ExpiryActionRow(
                icon = Icons.Default.Check,
                label = "标记为已用完",
                onClick = {
                    viewModel.markAsUsed(actionTarget.item.id)
                    actionTargetId = null
                }
            )
            ExpiryActionRow(
                icon = Icons.Default.Delete,
                label = "标记为已丢弃",
                onClick = {
                    viewModel.markAsDiscarded(actionTarget.item.id)
                    actionTargetId = null
                }
            )
            ExpiryActionRow(
                icon = Icons.Default.ShoppingCart,
                label = "加入待买清单",
                onClick = {
                    viewModel.addToBuy(actionTarget.item.id)
                    actionTargetId = null
                }
            )
            ExpiryActionRow(
                icon = Icons.Default.Edit,
                label = "编辑物品信息",
                onClick = {
                    actionTargetId = null
                    onNavigateToEdit(actionTarget.item.id)
                }
            )
        }
    }
}

@Composable
private fun ExpiryActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(SurfaceWarmDeep),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = OrangeStart,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}
