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

/**
 * 「有效期与提醒」面板：家当录入页的有效期快捷档位 + 到期提醒阶梯，
 * 原来分散在设置页，合并进家当设置统一维护。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpiryReminderPanel(
    reminderLadder: List<Int>,
    onToggleLadder: (Int) -> Unit,
    expiryQuickOptions: List<String>,
    onToggleQuickOption: (String) -> Unit,
    onOpenQuickCustom: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column {
                Text(
                    text = "有效期快捷选项",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "家当录入页「有效期」的快捷档位，按自己常买物品的保质期自定义（支持 x天/x周/x月/x年）。",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val candidates =
                        (DateUtil.EXPIRY_QUICK_CANDIDATES + expiryQuickOptions).distinct()
                    candidates.forEach { code ->
                        OptionChip(
                            label = DateUtil.expiryQuickLabel(code),
                            selected = expiryQuickOptions.contains(code),
                            onClick = { onToggleQuickOption(code) }
                        )
                    }
                    OptionChip(
                        label = "自定义…",
                        selected = false,
                        onClick = onOpenQuickCustom
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (expiryQuickOptions.isEmpty()) {
                        "尚未设置快捷档位，录入时可直接选日期"
                    } else {
                        "当前档位：" + expiryQuickOptions.joinToString("、") {
                            DateUtil.expiryQuickLabel(it)
                        }
                    },
                    fontSize = 12.sp,
                    color = OrangeStart,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        item {
            Column {
                Text(
                    text = "到期提醒阶梯",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "可多选。物品有效期进入所选的天数档位时提醒一次，未单独设置的物品都跟随这份默认阶梯。",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppPreferences.REMINDER_LADDER_OPTIONS.forEach { days ->
                        OptionChip(
                            label = "$days 天",
                            selected = reminderLadder.contains(days),
                            onClick = { onToggleLadder(days) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (reminderLadder.isEmpty()) {
                        "尚未选择档位，临期与到期当天仍会提醒"
                    } else {
                        "当前阶梯：${reminderLadder.joinToString("、") { "$it 天" }}"
                    },
                    fontSize = 12.sp,
                    color = OrangeStart,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** 有效期快捷档位自定义：输入数值 + 选单位（天/周/月/年），追加到现有档位并去重。 */
@Composable
private fun ExpiryQuickDialog(
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("d") }
    var added by remember { mutableStateOf(DateUtil.DEFAULT_EXPIRY_QUICK_CODES) }
    val unitOptions = listOf("d" to "天", "w" to "周", "m" to "月", "y" to "年")

    AppDialog(
        title = "自定义有效期快捷",
        onDismissRequest = onDismiss,
        confirmText = "保存",
        onConfirm = { onConfirm(added) }
    ) {
        Column {
            Text(
                text = "输入数值并选择单位，添加到快捷档位。",
                fontSize = 12.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    EditorInputBox(
                        value = amountText,
                        onValueChange = { input ->
                            amountText = input.filter { it.isDigit() }.take(3)
                        },
                        placeholder = "数值"
                    )
                }
                unitOptions.forEach { (code, label) ->
                    OptionChip(
                        label = label,
                        selected = unit == code,
                        onClick = { unit = code }
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            val canAdd = (amountText.toIntOrNull() ?: 0) > 0
            OptionChip(
                label = if (canAdd) {
                    "添加：${DateUtil.expiryQuickLabel(amountText.toInt().toString() + unit)}"
                } else {
                    "添加"
                },
                selected = canAdd,
                onClick = {
                    if (!canAdd) return@OptionChip
                    added = DateUtil.normalizeExpiryQuickCodes(
                        added + (amountText.toInt().toString() + unit)
                    )
                    amountText = ""
                }
            )
            if (added.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "当前档位：" + added.joinToString("、") { DateUtil.expiryQuickLabel(it) },
                    fontSize = 12.sp,
                    color = OrangeStart,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** 单选胶囊：选中态用品牌色铺底 + 白字，未选中态用浅底 + 次级文字。 */
@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) OrangeStart else SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) CardWhite else TextSecondary
        )
    }
}

/** 状态选项面板：内置选项只读，自定义选项可点改名、可删除。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusOptionsPanel(
    customStatuses: List<ItemStatusOption>,
    onAdd: (StatusDimension) -> Unit,
    onEdit: (ItemStatusOption) -> Unit,
    onRemove: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(StatusDimension.entries.toList(), key = { it.name }) { dimension ->
            Column {
                Text(
                    text = statusDimensionTitle(dimension),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ItemStatusCatalog.builtInFor(dimension).forEach { option ->
                        StatusChip(
                            label = option.label,
                            tint = itemStatusColors(dimension, option.code).second
                        )
                    }
                    customStatuses
                        .filter { it.dimension == dimension }
                        .forEach { option ->
                            StatusChip(
                                label = option.label,
                                tint = itemStatusColors(dimension, option.code).second,
                                onEdit = { onEdit(option) },
                                onRemove = { onRemove(option.code) }
                            )
                        }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(SurfaceWarmDeep)
                            .clickable { onAdd(dimension) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = OrangeStart,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "自定义",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = OrangeStart
                        )
                    }
                }
            }
        }
    }
}

/** 状态胶囊：内置状态只读；自定义状态可点主体改名，右侧带删除按钮。 */
@Composable
private fun StatusChip(
    label: String,
    tint: Color,
    onEdit: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.12f))
            .clickable(enabled = onEdit != null) { onEdit?.invoke() }
            .padding(
                start = 14.dp,
                end = if (onRemove != null) 6.dp else 14.dp,
                top = 9.dp,
                bottom = 9.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = tint
        )
        onRemove?.let { remove ->
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = remove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "删除该自定义状态",
                    tint = tint,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
private fun CategoryEditorDialog(
    title: String,
    subtitle: String,
    inputName: String,
    selectedIconKey: String,
    onNameChange: (String) -> Unit,
    onIconSelect: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    AppDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismissRequest,
        confirmText = "保存",
        confirmEnabled = inputName.isNotBlank(),
        onConfirm = onConfirm
    ) {
        OutlinedTextField(
            value = inputName,
            onValueChange = onNameChange,
            singleLine = true,
            label = { Text("分类名称") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "选择图标",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.height(220.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(categoryIconOptions.size, key = { index -> categoryIconOptions[index].key }) { index ->
                val option = categoryIconOptions[index]
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (selectedIconKey == option.key) OrangeStart.copy(alpha = 0.1f)
                            else OrangeTint
                        )
                        .border(
                            width = 1.dp,
                            color = if (selectedIconKey == option.key) {
                                OrangeStart.copy(alpha = 0.42f)
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { onIconSelect(option.key) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = option.label,
                        tint = if (selectedIconKey == option.key) OrangeStart else TextHint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentedTabs(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceWarmDeep)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SegmentItem(
            text = "分类",
            selected = selectedTab == 0,
            modifier = Modifier.weight(1f),
            onClick = { onSelectTab(0) }
        )
        SegmentItem(
            text = "存放位置",
            selected = selectedTab == 1,
            modifier = Modifier.weight(1f),
            onClick = { onSelectTab(1) }
        )
        SegmentItem(
            text = "状态选项",
            selected = selectedTab == 2,
            modifier = Modifier.weight(1f),
            onClick = { onSelectTab(2) }
        )
        SegmentItem(
            text = "有效期与提醒",
            selected = selectedTab == 3,
            modifier = Modifier.weight(1f),
            onClick = { onSelectTab(3) }
        )
    }
}

@Composable
private fun SegmentItem(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) OrangeStart else TextHint
        )
    }
}

@Composable
private fun MiniActionButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    tint: Color = OrangeStart,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (enabled) CardWhite else SurfaceWarmDeep)
            .clickable(enabled = enabled, onClick = onClick),
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

@Composable
private fun CategoryPanel(
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
private fun CategoryNode(
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
            onClick = {
                onSelectCategory(category.id)
                if (hasChildren) onToggleExpand(category.id)
            }
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
private fun CategoryRow(
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
            .clip(RoundedCornerShape(18.dp))
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
            fontSize = if (depth == 0) 15.sp else 14.sp,
            fontWeight = if (depth == 0) FontWeight.Bold else FontWeight.Medium,
            color = TextPrimary
        )
        Text(
            text = "${count}件",
            fontSize = 12.sp,
            color = if (selected) OrangeStart else TextSecondary
        )
        if (hasChildren) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = TextHint,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleExpand)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(999.dp))
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
private fun LocationPanel(
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
private fun LocationNode(
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
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (selectedLocationId == location.id) OrangeStart.copy(alpha = 0.1f)
                    else Color.Transparent
                )
                .clickable {
                    onSelectLocation(location.id)
                    if (hasChildren) onToggleExpand(location.id)
                }
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
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "${counts[location.id] ?: 0}件",
                fontSize = 12.sp,
                color = TextSecondary
            )
            if (hasChildren) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = TextHint
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(999.dp))
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

private fun buildLocationDescendants(locations: List<Location>): Map<Long, Set<Long>> =
    buildDescendantIds(locations, idOf = { it.id }, parentIdOf = { it.parentId })

private fun buildCategoryDescendants(categories: List<Category>): Map<Long, Set<Long>> =
    buildDescendantIds(categories, idOf = { it.id }, parentIdOf = { it.parentId })

/** 每个节点自身 + 全部后代的 id 集合，用于计数与「选父级也能看到子级内容」的筛选。 */
private fun <T> buildDescendantIds(
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
