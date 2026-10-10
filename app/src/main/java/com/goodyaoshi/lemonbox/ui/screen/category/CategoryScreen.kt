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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** 分类页：统一维护物品分类、存放位置与物品状态选项，从「我的」进入。 */
@Composable
fun CategoryScreen(
    onBack: () -> Unit,
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

    var selectedTab by remember { mutableIntStateOf(0) }
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
                .padding(top = 12.dp, bottom = 102.dp)
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
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            Text(
                text = "物品分类、存放位置、状态选项，以及有效期快捷与到期提醒，都在这里维护；查看物品请到家当筛选。",
                fontSize = 13.sp,
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
                    fontSize = 15.sp,
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
                            fontSize = 12.sp,
                            color = TextHint
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
                        onRemove = viewModel::removeCustomStatus
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
                OutlinedTextField(
                    value = inputName,
                    onValueChange = { inputName = it },
                    singleLine = true,
                    label = { Text("位置名称") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showDeleteDialog) {
        val deleteName = if (selectedTab == 0) selectedCategory?.name else selectedLocation?.name
        AppDialog(
            title = if (selectedTab == 0) "删除分类" else "删除位置",
            subtitle = "相关物品会保留，但关联关系可能会被清空。",
            onDismissRequest = { showDeleteDialog = false },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                if (selectedTab == 0) {
                    viewModel.deleteCategory(selectedCategoryId)
                } else {
                    viewModel.deleteLocation(selectedLocationId)
                }
                showDeleteDialog = false
            }
        ) {
            Text(
                text = "确定要删除“${deleteName.orEmpty()}”吗？",
                fontSize = 14.sp,
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
            OutlinedTextField(
                value = statusInput,
                onValueChange = {
                    statusInput = it
                    statusError = null
                },
                singleLine = true,
                label = { Text("选项名称") },
                modifier = Modifier.fillMaxWidth()
            )
            statusError?.let { message ->
                Text(
                    text = message,
                    fontSize = 12.sp,
                    color = StatusExpired
                )
            }
        }
    }

    if (showExpiryQuickDialog) {
        ExpiryQuickDialog(
            onDismiss = { showExpiryQuickDialog = false },
            onConfirm = { codes ->
                viewModel.setExpiryQuickOptions(codes)
                showExpiryQuickDialog = false
            }
        )
    }
}
