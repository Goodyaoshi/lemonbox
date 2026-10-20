package com.goodyaoshi.lemonbox.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.ErrorState
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.components.ImagePreviewOverlay
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SkeletonDetail
import com.goodyaoshi.lemonbox.ui.components.StatusPickerDialog
import com.goodyaoshi.lemonbox.ui.components.TreeNode
import com.goodyaoshi.lemonbox.ui.components.buildTreePathLabel
import com.goodyaoshi.lemonbox.ui.components.itemStatusOption
import com.goodyaoshi.lemonbox.ui.components.rememberImageModel
import com.goodyaoshi.lemonbox.ui.components.statusDimensionTitle
import com.goodyaoshi.lemonbox.ui.navigation.Screen
import com.goodyaoshi.lemonbox.ui.theme.BackgroundTop
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OnScrim
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.ScrimMedium
import com.goodyaoshi.lemonbox.ui.theme.ScrimSoft
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
import com.goodyaoshi.lemonbox.util.LedgerMath
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    itemId: Long,
    scope: String,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    // 「记一笔」入口：把当前物品的价格×数量作为默认金额预填进记账页（F06）。
    // 默认空实现以免调用方（如预览）必须提供。
    onRecordPurchase: (itemId: Long, amountCents: Long, name: String) -> Unit = { _, _, _ -> },
    viewModel: DetailViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loadError by viewModel.error.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val locations by viewModel.locations.collectAsState()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val appSnackbar = LocalAppSnackbar.current
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
        // 首帧：物品详情还没读到，画与详情同构的骨架（大图 + 标题 + 正文块），
        // 而不是孤零零一行「正在加载…」；读取出错时给出可重试的错误态；
        // 确认已加载却仍为空时，给出可返回的就地空态。
        Box(modifier = Modifier.fillMaxSize()) {
            AppDecorativeBackground()
            when {
                isLoading -> SkeletonDetail()
                loadError != null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ErrorState(
                        message = loadError.orEmpty(),
                        onRetry = viewModel::retry
                    )
                }
                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(
                        title = "物品不在了",
                        message = "它可能已被删除，或已经放进了回收站。",
                        actionLabel = "返回",
                        onAction = onBack
                    )
                }
            }
        }
        return
    }

    val detail = pagerItems.getOrNull(pagerState.currentPage) ?: pagerItems.first()
    val item = detail.item
    // 手动加入的待买项在数据层是 Item（usageStatus=USED_UP + needRestock），
    // 详情页不能照搬「已用完 / 在库」的库存语义，否则会同时出现两组互相打架的状态。
    val isToBuyItem = item.needRestock && item.usageStatus == Item.USAGE_USED_UP
    val imageHashes = item.imageHashList()

    // 「需要补货」改动给回执 + 撤销（P2-7）：与家当列表同一套提示范式，
    // 不再像此前那样点了就直接生效、既无回执也无法回退。
    fun toggleNeedRestock(target: Boolean) {
        val previous = item.needRestock
        if (previous == target) return
        viewModel.setNeedRestock(item.id, target)
        appSnackbar?.showUndo("已更新需要补货", DeleteCopy.UNDO_ACTION) {
            viewModel.setNeedRestock(item.id, previous)
        }
    }

    // 主图高度随屏幕自适应（P3-3）：按可用高度的 38% 取值并夹在 220–420dp 之间，
    // 小屏首屏不再被固定 380dp 的大图占满，宽屏 / 高屏也不会无限拔高。
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val imageHeight = (maxHeight * 0.38f).coerceIn(220.dp, 420.dp)
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
                    .height(imageHeight)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val pageItem = pagerItems[page].item
                    val primaryImageHash = pageItem.primaryImageHash()
                    val primaryImageModel = rememberImageModel(primaryImageHash)
                    // 有图只看哈希：换设备后本地文件还没落地，也照样能从云端取。
                    if (primaryImageHash != null) {
                        AsyncImage(
                            model = primaryImageModel,
                            contentDescription = pageItem.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    previewImageIndex = pageItem.imageHashList().indexOf(primaryImageHash)
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
                                // 字号走主题字阶（F6）：海报级占位首字改用展示级最大字阶
                                // displaySmall（34sp），不再用游离的 72sp 内联字号。
                                style = MaterialTheme.typography.displaySmall,
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
                                colors = listOf(ScrimMedium, Color.Transparent)
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

                // 返回入口（P1-1）：此前整页只有「删除」「编辑」两个圆钮，用户只能靠系统返回手势离开；
                // 这里在图片左上角补一个与右侧同款（同尺寸、同热区）的返回圆钮。
                ArrowCircleButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                ) {
                    onBack()
                }

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
                        // 术语统一（P3-5）：单件叫「物品」，与编辑页标题「编辑物品」一致。
                        contentDescription = "编辑物品"
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
                    // 字号走主题字阶（F6）：labelMedium 为 12sp。
                    style = MaterialTheme.typography.labelMedium,
                    color = OnScrim,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        // 形状走令牌（F6）：胶囊用 CircleShape。
                        .clip(CircleShape)
                        .background(ScrimMedium)
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
                    // 页面主标题统一走主题字阶（F6）：headlineLarge 为 28sp。
                    style = MaterialTheme.typography.headlineLarge,
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
                                // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                DetailInfoRow(if (isToBuyItem) "想买" else "数量", "${item.quantity} ${item.unit}")
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
                item.productionDate?.let { productionDate ->
                    DetailInfoRow("生产日期", DateUtil.formatDate(productionDate))
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
                if (!isToBuyItem) {
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
                } else {
                    DetailInfoRow(
                        label = "状态",
                        value = "待买中，买到后回待买页点「已买到」"
                    )
                }
                DetailInfoRow(
                    label = "需要补货",
                    value = if (item.needRestock) "已加入待买清单" else "不需要",
                    tail = {
                        Switch(
                            checked = item.needRestock,
                            // 读屏/键盘可独立操作开关（F8）：给出真实回调，
                            // 而非 onCheckedChange = null 只能靠整行点击。
                            onCheckedChange = { checked -> toggleNeedRestock(checked) }
                        )
                    },
                    onClick = { toggleNeedRestock(!item.needRestock) }
                )
                DetailInfoRow("备注", item.note.ifBlank { "无" })
                if (!isToBuyItem) {
                    DetailInfoRow(
                        label = "记一笔",
                        value = "把这件东西的花销记进账本",
                        tail = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                                contentDescription = null,
                                tint = OrangeStart,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            onRecordPurchase(
                                item.id,
                                LedgerMath.itemPriceToCents(item.price ?: 0.0, item.quantity),
                                item.name
                            )
                        }
                    )
                }

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
                // 详情页删除后会立即返回上一页，撤销提示由应用级作用域承载，退栈后仍可见可点。
                appSnackbar?.showUndo(DeleteCopy.UNDO_MESSAGE, DeleteCopy.UNDO_ACTION) {
                    viewModel.restoreFromTrash(item.id)
                }
                showDeleteDialog = false
                onBack()
            }
        ) {
            Text(
                text = "确定要删除「${item.name}」吗？",
                color = TextSecondary,
                // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                style = MaterialTheme.typography.bodyMedium
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
                // 状态改动给回执 + 撤销（P2-7）：与家当列表同一套提示范式。
                val previous = if (dimension == StatusDimension.USAGE) {
                    item.usageStatus
                } else {
                    item.disposition
                }
                if (dimension == StatusDimension.USAGE) {
                    viewModel.setUsageStatus(item.id, code)
                } else {
                    viewModel.setDisposition(item.id, code)
                }
                appSnackbar?.showUndo(
                    "已更新${statusDimensionTitle(dimension)}",
                    DeleteCopy.UNDO_ACTION
                ) {
                    if (dimension == StatusDimension.USAGE) {
                        viewModel.setUsageStatus(item.id, previous)
                    } else {
                        viewModel.setDisposition(item.id, previous)
                    }
                }
            },
            onDismissRequest = { statusPickerDimensionName = null }
        )
    }

    previewImageIndex?.let { index ->
        if (imageHashes.isNotEmpty()) {
            ImagePreviewOverlay(
                imageHashes = imageHashes,
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
    // 触达面积达标（F7）：外层 48dp 承载点击区域，内层 42dp 负责视觉，
    // 与同排的 [DeleteCircleButton] 保持一致的圆形直径与热区。
    Box(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .size(48.dp)
            .clip(CircleShape)
            // 悬浮在图片上的纯图标按钮，声明 Button 角色（F16）。
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(ScrimSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                // 图片之上的浮层按钮没有文字，必须给出描述（F16）。
                contentDescription = contentDescription,
                tint = OnScrim
            )
        }
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
                .background(ScrimSoft),
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
            // 字号走主题字阶（F6）：bodyMedium 为 14sp。
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            // 用最小宽度而非固定宽度：常规字号下各行的标签仍对齐，
            // 系统字体放大（简易模式）时标签可以撑开，不会把文字截断。
            modifier = Modifier.widthIn(min = 72.dp)
        )
        Text(
            text = value,
            // 字号走主题字阶（F6）：bodyMedium 为 14sp。
            style = MaterialTheme.typography.bodyMedium,
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
            // 字号走主题字阶（F6）：bodyMedium 为 14sp。
            style = MaterialTheme.typography.bodyMedium,
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
