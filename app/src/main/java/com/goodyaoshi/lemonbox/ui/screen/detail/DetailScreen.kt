package com.goodyaoshi.lemonbox.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.CategoryTag
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.components.ImagePreviewOverlay
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.StatusPickerDialog
import com.goodyaoshi.lemonbox.ui.components.TreeNode
import com.goodyaoshi.lemonbox.ui.components.buildTreePathLabel
import com.goodyaoshi.lemonbox.ui.components.itemStatusOption
import com.goodyaoshi.lemonbox.ui.components.statusDimensionTitle
import com.goodyaoshi.lemonbox.ui.navigation.Screen
import com.goodyaoshi.lemonbox.ui.theme.BackgroundTop
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TagGreen
import com.goodyaoshi.lemonbox.ui.theme.TagGreenText
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TagRed
import com.goodyaoshi.lemonbox.ui.theme.TagRedText
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.DetailViewModel
import com.goodyaoshi.lemonbox.util.DateUtil
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    itemId: Long,
    scope: String,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val locations by viewModel.locations.collectAsState()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    /** 当前正在编辑的状态维度名（null 表示弹窗关闭）。 */
    var statusPickerDimensionName by rememberSaveable { mutableStateOf<String?>(null) }
    var previewImageIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val categoryNodes = remember(categories) {
        categories.map { TreeNode(id = it.id, parentId = it.parentId, name = it.name) }
    }
    val locationNodes = remember(locations) {
        locations.map { TreeNode(id = it.id, parentId = it.parentId, name = it.name) }
    }
    var scopedItemIds by remember(scope, itemId) { mutableStateOf<List<Long>>(emptyList()) }
    val pagerItems = remember(items, scope, scopedItemIds, itemId) {
        if (scope == Screen.Detail.ScopeAll) {
            items
        } else {
            val fallbackIds = items
                .filter { matchesDetailScope(it, scope) }
                .map { it.item.id }
            val effectiveIds = if (scopedItemIds.isEmpty()) fallbackIds else scopedItemIds
            items.filter { it.item.id in effectiveIds.ifEmpty { listOf(itemId) } }
        }
    }
    val pagerState = rememberPagerState(pageCount = { pagerItems.size })
    val pagerScope = rememberCoroutineScope()
    var didJumpToInitialPage by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(items, itemId, scope) {
        if (scope != Screen.Detail.ScopeAll && items.isNotEmpty() && scopedItemIds.isEmpty()) {
            scopedItemIds = items
                .filter { matchesDetailScope(it, scope) }
                .map { it.item.id }
                .ifEmpty { listOf(itemId) }
        }
    }

    LaunchedEffect(pagerItems, itemId) {
        if (pagerItems.isEmpty()) {
            didJumpToInitialPage = false
            return@LaunchedEffect
        }
        val initialIndex = pagerItems.indexOfFirst { it.item.id == itemId }
        if (!didJumpToInitialPage && initialIndex >= 0) {
            pagerState.scrollToPage(initialIndex)
            didJumpToInitialPage = true
        } else if (pagerState.currentPage > pagerItems.lastIndex) {
            pagerState.scrollToPage(pagerItems.lastIndex)
        }
    }

    if (pagerItems.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "正在加载物品详情…",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
        return
    }

    val detail = pagerItems.getOrNull(pagerState.currentPage) ?: pagerItems.first()
    val item = detail.item
    val imagePaths = item.imagePathList()

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val pageItem = pagerItems[page].item
                    val primaryImagePath = pageItem.primaryImagePath()
                    if (primaryImagePath.isNotEmpty()) {
                        AsyncImage(
                            model = primaryImagePath,
                            contentDescription = pageItem.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    previewImageIndex = pageItem.imagePathList().indexOf(primaryImagePath)
                                        .takeIf { it >= 0 } ?: 0
                                },
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(LemonStart, LemonEnd)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = pageItem.name.take(1),
                                fontSize = 72.sp,
                                fontWeight = FontWeight.Bold,
                                color = OnLemon
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.34f), Color.Transparent)
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, BackgroundTop)
                            )
                        )
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 详情页删除入口：此前 showDeleteDialog 只被读取/置 false，从未置 true，
                    // 导致下方确认弹窗成为不可达的死代码。这里补上可见入口，点击后弹出确认框，
                    // 复用同一份 AppDialog 软删除文案与 viewModel.delete 逻辑。
                    DeleteCircleButton(
                        contentDescription = "删除物品"
                    ) {
                        showDeleteDialog = true
                    }
                    ArrowCircleButton(
                        icon = Icons.Default.Edit,
                        contentDescription = "编辑家当"
                    ) {
                        onEdit(item.id)
                    }
                }

                if (pagerState.currentPage > 0) {
                    ArrowCircleButton(
                        icon = Icons.Default.ChevronLeft,
                        contentDescription = "上一张",
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        pagerScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }
                }

                if (pagerState.currentPage < pagerItems.lastIndex) {
                    ArrowCircleButton(
                        icon = Icons.Default.ChevronRight,
                        contentDescription = "下一张",
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        pagerScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                }

                Text(
                    text = "${pagerState.currentPage + 1}/${pagerItems.size}",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            AppSurfaceCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .offset(y = (-26).dp)
            ) {
                Text(
                    text = item.name,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val categoryPath = buildTreePathLabel(item.categoryId, categoryNodes)
                        ?: detail.categoryName
                    categoryPath?.let { CategoryTag(text = it) }
                    val locationPath = buildTreePathLabel(item.locationId, locationNodes)
                        ?: detail.locationName
                    locationPath?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = it,
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                DetailInfoRow("数量", "${item.quantity} ${item.unit}")
                item.expireTime?.let { expireTime ->
                    val days = DateUtil.daysUntil(expireTime)
                    val (container, content) = when {
                        days < 0 -> TagRed to TagRedText
                        days <= 7 -> TagRed to TagRedText
                        else -> TagOrange to TagOrangeText
                    }
                    DetailInfoRow(
                        label = "有效期",
                        value = DateUtil.formatDate(expireTime),
                        tail = {
                            PillTag(
                                text = DateUtil.expiryCountdownText(expireTime),
                                backgroundColor = container,
                                contentColor = content
                            )
                        }
                    )
                }
                item.price?.let { price ->
                    DetailInfoRow("价格", DateUtil.formatCurrency(price))
                }
                item.purchaseDate?.let { purchaseDate ->
                    DetailInfoRow("购买日期", DateUtil.formatDate(purchaseDate))
                }
                // 使用统计：持续使用型看「用多少天」，按件消耗型看「已用 / 共 / 剩余」。
                if (item.trackMode == Item.TRACK_DURABLE) {
                    val usageDayCount = Item.usageDays(
                        startUseTime = item.startUseTime,
                        purchaseDate = item.purchaseDate,
                        createdAt = item.createdAt,
                        usageEndedAt = item.usageEndedAt,
                        usageStatus = item.usageStatus,
                        disposition = item.disposition
                    )
                    if (usageDayCount > 0) {
                        val ended = Item.isUsageEnded(item.usageStatus, item.disposition)
                        DetailInfoRow(
                            label = "使用天数",
                            value = if (ended) "共使用 $usageDayCount 天" else "已使用 $usageDayCount 天"
                        )
                        Item.averageDailyCost(item.price, item.quantity, usageDayCount)?.let { daily ->
                            DetailInfoRow("平均每天", DateUtil.formatCurrency(daily))
                        }
                    }
                } else if (item.totalQuantity > 1 || item.consumedQuantity > 0) {
                    DetailInfoRow("剩余数量", "${item.quantity}${item.unit}")
                    DetailInfoRow(
                        "已用 / 总量",
                        "${item.consumedQuantity} / ${item.totalQuantity}${item.unit}"
                    )
                }
                DetailInfoRow("添加时间", DateUtil.formatDateTime(item.createdAt))
                DetailInfoRow(
                    label = statusDimensionTitle(StatusDimension.USAGE),
                    value = itemStatusOption(StatusDimension.USAGE, item.usageStatus).label,
                    tail = { StatusEditHint() },
                    onClick = { statusPickerDimensionName = StatusDimension.USAGE.name }
                )
                DetailInfoRow(
                    label = statusDimensionTitle(StatusDimension.DISPOSITION),
                    value = itemStatusOption(
                        StatusDimension.DISPOSITION,
                        item.disposition
                    ).label,
                    tail = { StatusEditHint() },
                    onClick = { statusPickerDimensionName = StatusDimension.DISPOSITION.name }
                )
                DetailInfoRow(
                    label = "需要补货",
                    value = if (item.needRestock) "已加入待买清单" else "不需要",
                    tail = {
                        Switch(
                            checked = item.needRestock,
                            onCheckedChange = null
                        )
                    },
                    onClick = { viewModel.setNeedRestock(item.id, !item.needRestock) }
                )
                DetailInfoRow("备注", item.note.ifBlank { "无" })

            }
        }
    }

    if (showDeleteDialog) {
        AppDialog(
            title = DeleteCopy.CONFIRM_TITLE,
            // 与家当 / 待买 / 搜索等入口共用同一份文案（I2）：详情页的删除同样是
            // 软删除（DetailViewModel.delete -> ItemRepository.moveToTrash），
            // 旧文案"删除后不可恢复"与实际行为不符，已收敛为"移入回收站、30 天内可恢复"。
            subtitle = DeleteCopy.SOFT_SUBTITLE,
            onDismissRequest = { showDeleteDialog = false },
            confirmText = DeleteCopy.SOFT_CONFIRM,
            destructiveConfirm = true,
            onConfirm = {
                viewModel.delete(item)
                showDeleteDialog = false
                onBack()
            }
        ) {
            Text(
                text = "确定要删除「${item.name}」吗？",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
    }

    val statusPickerDimension = statusPickerDimensionName?.let { name ->
        runCatching { StatusDimension.valueOf(name) }.getOrNull()
    }
    if (statusPickerDimension != null) {
        val dimension = statusPickerDimension
        StatusPickerDialog(
            dimension = dimension,
            selectedCode = if (dimension == StatusDimension.USAGE) {
                item.usageStatus
            } else {
                item.disposition
            },
            onSelect = { code ->
                if (dimension == StatusDimension.USAGE) {
                    viewModel.setUsageStatus(item.id, code)
                } else {
                    viewModel.setDisposition(item.id, code)
                }
            },
            onDismissRequest = { statusPickerDimensionName = null }
        )
    }

    previewImageIndex?.let { index ->
        if (imagePaths.isNotEmpty()) {
            ImagePreviewOverlay(
                imagePaths = imagePaths,
                initialIndex = index,
                onDismissRequest = { previewImageIndex = null }
            )
        }
    }
}

@Composable
private fun ArrowCircleButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .size(42.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.22f))
            // 悬浮在图片上的纯图标按钮，声明 Button 角色（F16）。
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            // 图片之上的浮层按钮没有文字，必须给出描述（F16）。
            contentDescription = contentDescription,
            tint = Color.White
        )
    }
}

@Composable
private fun DeleteCircleButton(
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // 沿用 [ArrowCircleButton] 的圆形浮层样式，仅把图标换成主题语义危险色 StatusExpired，
    // 与「编辑家当」等中性操作区分。外层 48dp 承载点击区域以满足无障碍最小触控目标，
    // 内层 42dp 负责视觉，与同排的「编辑家当」按钮保持一致的圆形直径。
    Box(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                // 删除入口无文字标签，必须给出中文描述（F16）。
                contentDescription = contentDescription,
                tint = StatusExpired
            )
        }
    }
}

@Composable
private fun DetailInfoRow(
    label: String,
    value: String,
    tail: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = TextSecondary,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        tail?.invoke()
    }
}

@Composable
private fun StatusEditHint() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "修改",
            fontSize = 13.sp,
            color = OrangeStart,
            fontWeight = FontWeight.SemiBold
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = OrangeStart,
            modifier = Modifier.size(16.dp)
        )
    }
}

private fun matchesDetailScope(itemDetail: ItemDetail, scope: String): Boolean {
    val item = itemDetail.item
    val inStock = item.disposition == Item.DISPOSITION_IN_STOCK
    return when (scope) {
        Screen.Detail.ScopeToBuy -> item.needRestock && item.disposition != Item.DISPOSITION_DISCARDED
        Screen.Detail.ScopeUnused -> item.usageStatus == Item.USAGE_UNUSED
        Screen.Detail.ScopeUsedUp -> item.usageStatus == Item.USAGE_USED_UP
        Screen.Detail.ScopeLentOut -> item.disposition == Item.DISPOSITION_LENT_OUT
        Screen.Detail.ScopeGivenAway -> item.disposition == Item.DISPOSITION_GIVEN_AWAY
        Screen.Detail.ScopeDiscarded -> item.disposition == Item.DISPOSITION_DISCARDED
        Screen.Detail.ScopeInStock -> inStock && item.usageStatus != Item.USAGE_USED_UP
        Screen.Detail.ScopeInStockUnused -> inStock && item.usageStatus == Item.USAGE_UNUSED
        Screen.Detail.ScopeInStockInUse -> inStock && item.usageStatus == Item.USAGE_IN_USE
        Screen.Detail.ScopeInStockUsedUp -> inStock && item.usageStatus == Item.USAGE_USED_UP
        Screen.Detail.ScopeOffHand -> !inStock
        else -> true
    }
}
