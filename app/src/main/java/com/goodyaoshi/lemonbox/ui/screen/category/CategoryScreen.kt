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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.EditorInputBox
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
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
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.CategoryViewModel
import com.goodyaoshi.lemonbox.ui.viewmodel.RemovedStatusSnapshot
import com.goodyaoshi.lemonbox.util.DateUtil
import kotlinx.coroutines.launch

/** 家当设置四个 Tab 的序号（P2-24）：供外部深链指定要落到哪个 Tab。 */
object CategoryTab {
    const val CATEGORY = 0
    const val LOCATION = 1
    const val STATUS = 2

    /** 「有效期与提醒」：设置页的「提前几天提醒」深链应落到这里。 */
    const val EXPIRY_REMINDER = 3
}

/** 分类页：统一维护物品分类、存放位置与物品状态选项，入口在家当 Tab 页头。 */
@Composable
fun CategoryScreen(
    onBack: () -> Unit,
    /** 进入时默认展开的 Tab（P2-24）：默认「管理分类」，深链可指定其它 Tab。 */
    initialTab: Int = CategoryTab.CATEGORY,
    viewModel: CategoryViewModel = hiltViewModel()
) {
    val categories by viewModel.categories.collectAsState()
    val rootCategories by viewModel.rootCategories.collectAsState()
    val allLocations by viewModel.allLocations.collectAsState()
    val rootLocations by viewModel.rootLocations.collectAsState()
    val activeItems by viewModel.activeItems.collectAsState()
    val selectedCategoryId by viewModel.selectedCategoryId.collectAsState()
    val selectedLocationId by viewModel.selectedLocationId.collectAsState()
    val customStatuses by viewModel.customStatuses.collectAsState()
    val reminderLadder by viewModel.reminderLadder.collectAsState()
    val expiryQuickOptions by viewModel.expiryQuickOptions.collectAsState()

    // 初始 Tab 由入参决定（P2-24）：设置页的「提前几天提醒」可直达「有效期与提醒」。
    var selectedTab by remember(initialTab) {
        mutableIntStateOf(initialTab.coerceIn(CategoryTab.CATEGORY, CategoryTab.EXPIRY_REMINDER))
    }
    val appSnackbar = LocalAppSnackbar.current
    val uiScope = rememberCoroutineScope()
    val expandedLocations = remember { mutableStateMapOf<Long, Boolean>() }
    val expandedCategories = remember { mutableStateMapOf<Long, Boolean>() }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var inputName by remember { mutableStateOf("") }
    var selectedIconKey by remember { mutableStateOf(categoryIconOptions.first().key) }
    var isEditingCategory by remember { mutableStateOf(false) }
    var isEditingLocation by remember { mutableStateOf(false) }
    // 新增分类时的父级：null 表示新建一级分类，否则挂到该分类下。
    var categoryParentId by remember { mutableStateOf<Long?>(null) }
    var categoryParentName by remember { mutableStateOf<String?>(null) }
    // 新增位置时的父级：null 表示新建一级位置，否则挂到该位置下。
    var locationParentId by remember { mutableStateOf<Long?>(null) }
    var locationParentName by remember { mutableStateOf<String?>(null) }
    // 状态选项弹窗：新增时 dimension 非空、editingStatus 为空；重命名时 editingStatus 非空。
    var statusDialogDimension by remember { mutableStateOf<StatusDimension?>(null) }
    var editingStatus by remember { mutableStateOf<ItemStatusOption?>(null) }
    // 删除自定义状态前的二次确认（P1-2）：记录待删状态与仍在使用它的物品数。
    var pendingRemoveStatus by remember { mutableStateOf<ItemStatusOption?>(null) }
    var pendingRemoveStatusCount by remember { mutableIntStateOf(0) }
    var statusInput by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf<String?>(null) }
    var showExpiryQuickDialog by remember { mutableStateOf(false) }

    val locationDescendants = remember(allLocations) { buildLocationDescendants(allLocations) }
    val categoryDescendants = remember(categories) { buildCategoryDescendants(categories) }
    val categoryCounts = remember(categories, activeItems, categoryDescendants) {
        categories.associate { category ->
            val ids = categoryDescendants[category.id].orEmpty()
            category.id to activeItems.count { detail -> detail.item.categoryId in ids }
        }
    }
    val locationCounts = remember(allLocations, activeItems, locationDescendants) {
        allLocations.associate { location ->
            val ids = locationDescendants[location.id].orEmpty()
            location.id to activeItems.count { detail -> detail.item.locationId in ids }
        }
    }

    val selectedLocation = allLocations.firstOrNull { it.id == selectedLocationId }
    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                // 家当设置为二级页，进入后底部导航栏不显示，无需为其预留空间；
                // 仅留 24.dp 让最后一个条目与屏幕底边保持呼吸感。
                .padding(top = 12.dp, bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
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
                    text = "家当设置",
                    // 页面主标题统一走主题字阶（F6）：headlineLarge 为 28sp。
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary
                )
            }
            Text(
                text = "分类、存放位置、状态选项、有效期快捷与到期提醒都在这里；具体物品到家当里筛。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            SegmentedTabs(
                selectedTab = selectedTab,
                onSelectTab = { index ->
                    selectedTab = index
                    when (index) {
                        0 -> viewModel.selectCategory(null)
                        1 -> viewModel.selectLocation(null)
                        else -> {
                            viewModel.selectCategory(null)
                            viewModel.selectLocation(null)
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (selectedTab) {
                        0 -> "管理分类"
                        1 -> "管理位置"
                        2 -> "状态选项"
                        else -> "有效期与提醒"
                    },
                    // 字号走主题字阶（F6）：titleSmall 为 14sp。
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (selectedTab < 2) {
                    val editingSelected = if (selectedTab == 0) {
                        selectedCategoryId != null
                    } else {
                        selectedLocationId != null
                    }
                    // 受保护的内置数据（食品分类树、冰箱）：不可编辑也不可删除。
                    val selectedIsProtected = if (selectedTab == 0) {
                        selectedCategory?.isProtected == true
                    } else {
                        selectedLocation?.isProtected == true
                    }
                    if (selectedIsProtected) {
                        Text(
                            text = "内置保护",
                            // 字号走主题字阶（F6）：labelMedium 为 12sp。
                            style = MaterialTheme.typography.labelMedium,
                            // 承载可读信息的正文改用 TextSecondary（P2-3）：
                            // TextHint 对比度不足 4.5:1，只留给图标与占位。
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    MiniActionButton(
                        icon = if (editingSelected && !selectedIsProtected) {
                            Icons.Default.Edit
                        } else {
                            Icons.Default.Add
                        },
                        contentDescription = if (editingSelected && !selectedIsProtected) {
                            "编辑"
                        } else {
                            "新增"
                        },
                        enabled = !selectedIsProtected,
                        onClick = {
                            when {
                                selectedTab == 0 &&
                                    selectedCategory != null &&
                                    !selectedCategory.isProtected -> {
                                    inputName = selectedCategory.name
                                    selectedIconKey = selectedCategory.icon
                                        .ifBlank { categoryIconOptions.first().key }
                                    isEditingCategory = true
                                }

                                selectedTab == 1 &&
                                    selectedLocation != null &&
                                    !selectedLocation.isProtected -> {
                                    inputName = selectedLocation.name
                                    isEditingLocation = true
                                }

                                else -> {
                                    inputName = ""
                                    selectedIconKey = categoryIconOptions.first().key
                                    isEditingCategory = false
                                    isEditingLocation = false
                                    // 顶部新增默认建一级，子级走行上的加号。
                                    categoryParentId = null
                                    categoryParentName = null
                                    locationParentId = null
                                    locationParentName = null
                                }
                            }
                            showCategoryDialog = true
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    MiniActionButton(
                        icon = Icons.Default.DeleteOutline,
                        contentDescription = "删除",
                        enabled = editingSelected && !selectedIsProtected,
                        tint = StatusExpired,
                        onClick = { showDeleteDialog = true }
                    )
                }
            }

            AppSurfaceCard(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                shadowElevation = 12.dp
            ) {
                when (selectedTab) {
                    0 -> CategoryPanel(
                        rootCategories = rootCategories,
                        selectedCategoryId = selectedCategoryId,
                        expandedCategories = expandedCategories,
                        counts = categoryCounts,
                        onSelectCategory = viewModel::selectCategory,
                        onToggleExpand = { id ->
                            expandedCategories[id] = !(expandedCategories[id] ?: false)
                        },
                        onAddSubCategory = { category ->
                            inputName = ""
                            selectedIconKey = categoryIconOptions.first().key
                            isEditingCategory = false
                            categoryParentId = category.id
                            categoryParentName = category.name
                            showCategoryDialog = true
                        },
                        viewModel = viewModel
                    )

                    1 -> LocationPanel(
                        rootLocations = rootLocations,
                        selectedLocationId = selectedLocationId,
                        expandedLocations = expandedLocations,
                        counts = locationCounts,
                        onSelectLocation = viewModel::selectLocation,
                        onToggleExpand = { id ->
                            expandedLocations[id] = !(expandedLocations[id] ?: false)
                        },
                        onAddSubLocation = { location ->
                            inputName = ""
                            isEditingLocation = false
                            locationParentId = location.id
                            locationParentName = location.name
                            showCategoryDialog = true
                        },
                        viewModel = viewModel
                    )

                    2 -> StatusOptionsPanel(
                        customStatuses = customStatuses,
                        onAdd = { dimension ->
                            statusInput = ""
                            statusError = null
                            editingStatus = null
                            statusDialogDimension = dimension
                        },
                        onEdit = { option ->
                            statusInput = option.label
                            statusError = null
                            editingStatus = option
                            statusDialogDimension = option.dimension
                        },
                        // 「×」不再单击即删（P1-2）：先记录待删状态并异步数出受影响的物品，弹二次确认。
                        onRemove = { code ->
                            val option = customStatuses.firstOrNull { it.code == code }
                            if (option != null) {
                                pendingRemoveStatus = option
                                pendingRemoveStatusCount = 0
                                uiScope.launch {
                                    pendingRemoveStatusCount = viewModel.countItemsUsingStatus(option)
                                }
                            }
                        }
                    )

                    else -> ExpiryReminderPanel(
                        reminderLadder = reminderLadder,
                        onToggleLadder = { days ->
                            val updated = if (reminderLadder.contains(days)) {
                                reminderLadder - days
                            } else {
                                (reminderLadder + days).sorted()
                            }
                            viewModel.setReminderLadder(updated)
                        },
                        expiryQuickOptions = expiryQuickOptions,
                        onToggleQuickOption = { code ->
                            val updated = if (expiryQuickOptions.contains(code)) {
                                expiryQuickOptions - code
                            } else {
                                expiryQuickOptions + code
                            }
                            viewModel.setExpiryQuickOptions(updated)
                        },
                        onOpenQuickCustom = { showExpiryQuickDialog = true }
                    )
                }
            }
        }
    }

    if (showCategoryDialog) {
        if (selectedTab == 0) {
            CategoryEditorDialog(
                title = when {
                    isEditingCategory -> "编辑分类"
                    categoryParentId != null -> "在「${categoryParentName.orEmpty()}」下新增"
                    else -> "新增分类"
                },
                subtitle = when {
                    isEditingCategory -> "支持修改名称，并从下方图标库中直接选择。"
                    categoryParentId != null -> "新分类会作为「${categoryParentName.orEmpty()}」的子分类，可以继续往下加。"
                    else -> "支持修改名称，并从下方图标库中直接选择。"
                },
                inputName = inputName,
                selectedIconKey = selectedIconKey,
                onNameChange = { inputName = it },
                onIconSelect = { selectedIconKey = it },
                onDismissRequest = { showCategoryDialog = false },
                onConfirm = {
                    if (isEditingCategory) {
                        viewModel.updateCategory(selectedCategoryId, inputName, selectedIconKey)
                    } else {
                        viewModel.addCategory(inputName, selectedIconKey, categoryParentId)
                    }
                    showCategoryDialog = false
                }
            )
        } else {
            AppDialog(
                title = when {
                    isEditingLocation -> "编辑位置"
                    locationParentId == null -> "新增一级位置"
                    else -> "在「${locationParentName.orEmpty()}」下新增"
                },
                subtitle = when {
                    isEditingLocation -> "改名后，已归到该位置的物品会同步显示新名称。"
                    locationParentId == null -> "将创建一个与现有位置同级的一级位置。"
                    else -> "新位置会作为「${locationParentName.orEmpty()}」的子位置。"
                },
                onDismissRequest = { showCategoryDialog = false },
                confirmText = if (isEditingLocation) "保存" else "添加",
                confirmEnabled = inputName.isNotBlank(),
                onConfirm = {
                    if (isEditingLocation) {
                        viewModel.updateLocation(selectedLocationId, inputName)
                    } else {
                        viewModel.addLocation(inputName, locationParentId)
                    }
                    showCategoryDialog = false
                }
            ) {
                EditorInputBox(
                    value = inputName,
                    onValueChange = { inputName = it },
                    placeholder = "位置名称"
                )
            }
        }
    }

    if (showDeleteDialog) {
        val deleteName = if (selectedTab == 0) selectedCategory?.name else selectedLocation?.name
        // 受影响物品数：分类 / 位置各自已经算好「自身 + 后代」的计数，直接取用。
        val affectedCount = if (selectedTab == 0) {
            categoryCounts[selectedCategoryId] ?: 0
        } else {
            locationCounts[selectedLocationId] ?: 0
        }
        val orphanLabel = if (selectedTab == 0) "未分类" else "未设置位置"
        AppDialog(
            title = if (selectedTab == 0) "删除分类" else "删除位置",
            // 文案明确化（P2）：原「关联关系可能会被清空」含糊不清；
            // 改为说清受影响数量、删除后归到哪个状态、以及可稍后重新归类。
            subtitle = "「${deleteName.orEmpty()}」下有 $affectedCount 件物品，" +
                "删除后会变成「$orphanLabel」，可稍后重新归类。",
            onDismissRequest = { showDeleteDialog = false },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                // 删除后给带「撤销」的提示条（P2-4）：撤销时把这批节点的墓碑清掉，
                // 其下物品的原归属也就跟着恢复，不再是「删了就没法回退」。
                val isCategory = selectedTab == 0
                val targetCategoryId = selectedCategoryId
                val targetLocationId = selectedLocationId
                val removedName = deleteName.orEmpty()
                uiScope.launch {
                    val removedIds = if (isCategory) {
                        viewModel.deleteCategory(targetCategoryId)
                    } else {
                        viewModel.deleteLocation(targetLocationId)
                    }
                    if (removedIds.isNotEmpty()) {
                        appSnackbar?.showUndo("已删除「$removedName」", DeleteCopy.UNDO_ACTION) {
                            uiScope.launch {
                                if (isCategory) {
                                    viewModel.restoreCategory(removedIds)
                                } else {
                                    viewModel.restoreLocation(removedIds)
                                }
                            }
                        }
                    }
                }
                showDeleteDialog = false
            }
        ) {
            Text(
                text = "确定要删除“${deleteName.orEmpty()}”吗？",
                // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }

    statusDialogDimension?.let { dimension ->
        AppDialog(
            title = if (editingStatus == null) {
                "新增${statusDimensionTitle(dimension)}"
            } else {
                "重命名${statusDimensionTitle(dimension)}"
            },
            subtitle = "名称会出现在物品详情该维度的选择里，已有的物品会同步更新。",
            onDismissRequest = {
                statusDialogDimension = null
                editingStatus = null
                statusInput = ""
                statusError = null
            },
            confirmText = if (editingStatus == null) "添加" else "保存",
            confirmEnabled = statusInput.isNotBlank(),
            onConfirm = {
                val target = editingStatus
                val applied = if (target == null) {
                    viewModel.addCustomStatus(statusInput, dimension)
                } else {
                    viewModel.updateCustomStatus(target.code, statusInput)
                }
                if (applied) {
                    statusDialogDimension = null
                    editingStatus = null
                    statusInput = ""
                    statusError = null
                } else {
                    statusError = "这个名称已经存在，换一个吧。"
                }
            }
        ) {
            EditorInputBox(
                value = statusInput,
                onValueChange = {
                    statusInput = it
                    statusError = null
                },
                placeholder = "选项名称"
            )
            statusError?.let { message ->
                Text(
                    text = message,
                    // 字号走主题字阶（F6）：bodySmall 为 12sp。
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusExpired
                )
            }
        }
    }

    pendingRemoveStatus?.let { option ->
        AppDialog(
            title = "删除自定义状态",
            // 正文说清影响面（P1-2）：删除前告知有多少件物品仍挂在该状态上。
            subtitle = if (pendingRemoveStatusCount > 0) {
                "「${option.label}」下有 $pendingRemoveStatusCount 件物品仍在使用该状态，" +
                    "删除后它们会显示成「状态 ${option.code}」，可撤销。"
            } else {
                "确定要删除「${option.label}」吗？删错了可以撤销。"
            },
            onDismissRequest = { pendingRemoveStatus = null },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                val target = option
                uiScope.launch {
                    // 删除时记下快照（状态本体 + 受影响物品 id），撤销时一并恢复。
                    val snapshot: RemovedStatusSnapshot? =
                        viewModel.removeCustomStatusWithSnapshot(target.code)
                    if (snapshot != null) {
                        appSnackbar?.showUndo("已删除「${target.label}」", DeleteCopy.UNDO_ACTION) {
                            uiScope.launch { viewModel.restoreCustomStatus(snapshot) }
                        }
                    }
                }
                pendingRemoveStatus = null
            }
        ) {
            Text(
                text = "删除后该维度下将不再提供这个选项。",
                // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }

    if (showExpiryQuickDialog) {
        ExpiryQuickDialog(
            current = expiryQuickOptions,
            onDismiss = { showExpiryQuickDialog = false },
            onConfirm = { codes ->
                viewModel.setExpiryQuickOptions(codes)
                showExpiryQuickDialog = false
            }
        )
    }
}
