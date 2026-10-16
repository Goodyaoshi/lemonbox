package com.goodyaoshi.lemonbox.ui.screen.category

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Microwave
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WineBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusCatalog
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusOption
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.CategoryIconOption
import com.goodyaoshi.lemonbox.ui.components.EditorInputBox
import com.goodyaoshi.lemonbox.ui.components.categoryIconFor
import com.goodyaoshi.lemonbox.ui.components.categoryIconOptions
import com.goodyaoshi.lemonbox.ui.components.itemStatusColors
import com.goodyaoshi.lemonbox.ui.components.statusDimensionTitle
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarm
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.CategoryViewModel
import com.goodyaoshi.lemonbox.util.DateUtil


// 家当设置页的「分类 / 存放位置」树与分段 Tab（F9）。
// 这些私有 Composable 负责树形渲染、展开折叠与选中计数，与弹窗逻辑互不相干，
// 独立成本文件后页面入口 CategoryScreen 只保留编排。
// 由 CategoryScreen 调用，故放宽到 internal（同包可见）。

@Composable
internal fun SegmentedTabs(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 复用通用分段控件（P2）：原先此处另写了一套硬编码 4 段实现，
    // 与 ui/components 里的传参版并存，导致分段控件跨页样式/行为漂移；现统一委托组件版。
    com.goodyaoshi.lemonbox.ui.components.SegmentedTabs(
        labels = listOf("分类", "存放位置", "状态选项", "有效期与提醒"),
        selectedIndex = selectedTab,
        onSelect = onSelectTab,
        modifier = modifier
    )
}

@Composable
internal fun MiniActionButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    tint: Color = OrangeStart,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            // 触达面积达标（F7）：原圆钮热区仅约 36dp，外层撑到 48dp，内层保留原 36dp 视觉圆。
            .size(48.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (enabled) CardWhite else SurfaceWarmDeep),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else TextHint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
internal fun CategoryPanel(
    rootCategories: List<Category>,
    selectedCategoryId: Long?,
    expandedCategories: Map<Long, Boolean>,
    counts: Map<Long, Int>,
    onSelectCategory: (Long?) -> Unit,
    onToggleExpand: (Long) -> Unit,
    onAddSubCategory: (Category) -> Unit,
    viewModel: CategoryViewModel
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(rootCategories, key = { it.id }) { category ->
            CategoryNode(
                category = category,
                selectedCategoryId = selectedCategoryId,
                expandedCategories = expandedCategories,
                counts = counts,
                onSelectCategory = onSelectCategory,
                onToggleExpand = onToggleExpand,
                onAddSubCategory = onAddSubCategory,
                viewModel = viewModel,
                depth = 0
            )
        }
    }
}

@Composable
internal fun CategoryNode(
    category: Category,
    selectedCategoryId: Long?,
    expandedCategories: Map<Long, Boolean>,
    counts: Map<Long, Int>,
    onSelectCategory: (Long?) -> Unit,
    onToggleExpand: (Long) -> Unit,
    onAddSubCategory: (Category) -> Unit,
    viewModel: CategoryViewModel,
    depth: Int
) {
    val subCategories by viewModel.getSubCategories(category.id).collectAsState(initial = emptyList())
    val isExpanded = expandedCategories[category.id] ?: false
    val hasChildren = subCategories.isNotEmpty()

    Column {
        CategoryRow(
            category = category,
            count = counts[category.id] ?: 0,
            selected = selectedCategoryId == category.id,
            depth = depth,
            hasChildren = hasChildren,
            expanded = isExpanded,
            onToggleExpand = { onToggleExpand(category.id) },
            onAddSubCategory = { onAddSubCategory(category) },
            // I9：整行只负责「选中」，展开交给右侧独立的箭头热区。
            // 之前整行单击会「既选中又展开」，与箭头重复，点击结果不确定。
            onClick = { onSelectCategory(category.id) }
        )

        AnimatedVisibility(
            visible = isExpanded && hasChildren,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                subCategories.forEach { subCategory ->
                    CategoryNode(
                        category = subCategory,
                        selectedCategoryId = selectedCategoryId,
                        expandedCategories = expandedCategories,
                        counts = counts,
                        onSelectCategory = onSelectCategory,
                        onToggleExpand = onToggleExpand,
                        onAddSubCategory = onAddSubCategory,
                        viewModel = viewModel,
                        depth = depth + 1
                    )
                }
            }
        }
    }
}

@Composable
internal fun CategoryRow(
    category: Category,
    count: Int,
    selected: Boolean,
    depth: Int,
    hasChildren: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onAddSubCategory: () -> Unit,
    onClick: () -> Unit
) {
    val icon = categoryIconFor(category.icon)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
            // 圆角走形状令牌（F6）：收敛到 medium（16dp）。
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) OrangeStart.copy(alpha = 0.1f) else SurfaceWarm)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(if (depth == 0) 40.dp else 32.dp)
                .clip(CircleShape)
                .background(if (selected) CardWhite else SurfaceWarmDeep),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = OrangeStart,
                modifier = Modifier.size(if (depth == 0) 24.dp else 18.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = category.name,
            modifier = Modifier.weight(1f),
            // 字号走主题字阶（F6）：titleSmall 为 14sp。
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (depth == 0) FontWeight.Bold else FontWeight.Medium,
            color = TextPrimary
        )
        Text(
            text = "${count}件",
            // 字号走主题字阶（F6）：labelMedium 为 12sp。
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) OrangeStart else TextSecondary
        )
        if (hasChildren) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                // 触达面积达标（F7）：箭头视觉 20dp，热区撑到 48dp。
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    // 声明 Button 角色，读屏才会播报「按钮」（F16）。
                    .clickable(role = Role.Button, onClick = onToggleExpand),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    // 展开箭头是独立的可点目标，需报出当前状态（F16）。
                    contentDescription = if (expanded) "收起子分类" else "展开子分类",
                    tint = TextHint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            // 触达面积达标（F7）：加号视觉 16dp，热区撑到 48dp。
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onAddSubCategory),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "在此分类下新增",
                tint = TextHint,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
internal fun LocationPanel(
    rootLocations: List<Location>,
    selectedLocationId: Long?,
    expandedLocations: Map<Long, Boolean>,
    counts: Map<Long, Int>,
    onSelectLocation: (Long?) -> Unit,
    onToggleExpand: (Long) -> Unit,
    onAddSubLocation: (Location) -> Unit,
    viewModel: CategoryViewModel
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(rootLocations, key = { it.id }) { location ->
            LocationNode(
                location = location,
                selectedLocationId = selectedLocationId,
                expandedLocations = expandedLocations,
                counts = counts,
                onSelectLocation = onSelectLocation,
                onToggleExpand = onToggleExpand,
                onAddSubLocation = onAddSubLocation,
                viewModel = viewModel,
                depth = 0
            )
        }
    }
}

@Composable
internal fun LocationNode(
    location: Location,
    selectedLocationId: Long?,
    expandedLocations: Map<Long, Boolean>,
    counts: Map<Long, Int>,
    onSelectLocation: (Long?) -> Unit,
    onToggleExpand: (Long) -> Unit,
    onAddSubLocation: (Location) -> Unit,
    viewModel: CategoryViewModel,
    depth: Int
) {
    val subLocations by viewModel.getSubLocations(location.id).collectAsState(initial = emptyList())
    val isExpanded = expandedLocations[location.id] ?: false
    val hasChildren = subLocations.isNotEmpty()

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 16).dp)
                // 圆角走形状令牌（F6）：收敛到 medium（16dp）。
                .clip(MaterialTheme.shapes.medium)
                .background(
                    if (selectedLocationId == location.id) OrangeStart.copy(alpha = 0.1f)
                    else Color.Transparent
                )
                // I9：整行只负责「选中」，展开由右侧独立的箭头热区承担，
                // 避免整行单击同时切换选中与展开、结果不可预期。
                .clickable { onSelectLocation(location.id) }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (depth == 0) Icons.Default.Home else Icons.Default.LocationOn,
                contentDescription = null,
                tint = if (selectedLocationId == location.id) OrangeStart else TextHint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = location.name,
                modifier = Modifier.weight(1f),
                // 字号走主题字阶（F6）：titleSmall 为 14sp。
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "${counts[location.id] ?: 0}件",
                // 字号走主题字阶（F6）：labelMedium 为 12sp。
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            if (hasChildren) {
                Spacer(modifier = Modifier.width(6.dp))
                // I9：箭头是唯一负责「展开 / 收起」的热区，与分类树保持一致；
                // 补上状态描述与 Button 角色，读屏可识别（F16）。
                Box(
                    // 触达面积达标（F7）：箭头视觉 20dp，热区撑到 48dp。
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { onToggleExpand(location.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "收起子位置" else "展开子位置",
                        tint = TextHint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                // 触达面积达标（F7）：加号视觉 16dp，热区撑到 48dp。
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { onAddSubLocation(location) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "在此位置下新增",
                    tint = TextHint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded && hasChildren,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                subLocations.forEach { subLocation ->
                    LocationNode(
                        location = subLocation,
                        selectedLocationId = selectedLocationId,
                        expandedLocations = expandedLocations,
                        counts = counts,
                        onSelectLocation = onSelectLocation,
                        onToggleExpand = onToggleExpand,
                        onAddSubLocation = onAddSubLocation,
                        viewModel = viewModel,
                        depth = depth + 1
                    )
                }
            }
        }
    }
}

internal fun buildLocationDescendants(locations: List<Location>): Map<Long, Set<Long>> =
    buildDescendantIds(locations, idOf = { it.id }, parentIdOf = { it.parentId })

internal fun buildCategoryDescendants(categories: List<Category>): Map<Long, Set<Long>> =
    buildDescendantIds(categories, idOf = { it.id }, parentIdOf = { it.parentId })

/** 每个节点自身 + 全部后代的 id 集合，用于计数与「选父级也能看到子级内容」的筛选。 */
internal fun <T> buildDescendantIds(
    nodes: List<T>,
    idOf: (T) -> Long,
    parentIdOf: (T) -> Long?
): Map<Long, Set<Long>> {
    val childrenMap = nodes.groupBy { parentIdOf(it) }
    val cache = mutableMapOf<Long, Set<Long>>()

    fun collect(id: Long): Set<Long> = cache.getOrPut(id) {
        val childIds = childrenMap[id].orEmpty().flatMap { collect(idOf(it)) }
        setOf(id) + childIds
    }

    return nodes.associate { idOf(it) to collect(idOf(it)) }
}
