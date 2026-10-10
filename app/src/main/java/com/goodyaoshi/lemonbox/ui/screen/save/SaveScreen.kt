package com.goodyaoshi.lemonbox.ui.screen.save

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.BarcodeAssociationCard
import com.goodyaoshi.lemonbox.ui.components.ExpiryPickerDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorInputBox
import com.goodyaoshi.lemonbox.ui.components.EditorPickerField
import com.goodyaoshi.lemonbox.ui.components.EditorSectionLabel
import com.goodyaoshi.lemonbox.ui.components.EditorSelectionChip
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.components.HierarchicalPickerDialog
import com.goodyaoshi.lemonbox.ui.components.ItemImageGallery
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.QuantityStepper
import com.goodyaoshi.lemonbox.ui.components.ReminderLadderPicker
import com.goodyaoshi.lemonbox.ui.components.UnitPickerRow
import com.goodyaoshi.lemonbox.ui.components.TreeNode
import com.goodyaoshi.lemonbox.ui.components.buildTreePathLabel
import com.goodyaoshi.lemonbox.ui.scan.EXTRA_BARCODE
import com.goodyaoshi.lemonbox.ui.scan.ScanActivity
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.GlassWhite
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.SaveItemState
import com.goodyaoshi.lemonbox.ui.viewmodel.SaveViewModel
import com.goodyaoshi.lemonbox.util.DateUtil
import java.time.LocalDate

private data class ExpiryQuickOption(val label: String, val timestamp: Long)

/** 购买日期可回溯过去 10 年。 */
private val purchaseYearRange: IntRange
    get() = (LocalDate.now().year - 10)..LocalDate.now().year

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveScreen(
    imageUri: Uri?,
    pendingImageUris: List<Uri>,
    resultImageUri: Uri?,
    resultImageUris: List<Uri>,
    resultPhotoMode: SavePhotoMode?,
    onConsumePendingResult: () -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onOpenCamera: (mode: SavePhotoMode) -> Unit,
    initialBarcode: String? = null,
    viewModel: SaveViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    // 全局提示通道（I10）：保存成功改走统一 Snackbar，替代易被忽略的系统 Toast。
    val appSnackbar = LocalAppSnackbar.current
    val state by viewModel.state.collectAsState()
    val continuousEntry by viewModel.continuousEntry.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val locations by viewModel.locations.collectAsState()
    val defaultReminderLadder by viewModel.defaultReminderLadder.collectAsState()
    val expiryQuickCodes by viewModel.expiryQuickOptions.collectAsState()

    // 「更多信息」默认折叠，展开后跨会话记住用户的选择（I6）。
    val showAdvanced by viewModel.advancedExpanded.collectAsState()
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showPurchaseDatePicker by remember { mutableStateOf(false) }
    var showStartUseDatePicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showLocationPicker by remember { mutableStateOf(false) }

    val categoryNodes = remember(categories) {
        categories.map { TreeNode(id = it.id, parentId = it.parentId, name = it.name) }
    }
    val locationNodes = remember(locations) {
        locations.map { TreeNode(id = it.id, parentId = it.parentId, name = it.name) }
    }
    // 快捷档位来自设置页的自定义配置（x天/x周/x月/x年）。
    val expiryQuickOptions = remember(expiryQuickCodes) {
        expiryQuickCodes.mapNotNull { code ->
            val timestamp = DateUtil.expiryQuickTimestamp(code) ?: return@mapNotNull null
            ExpiryQuickOption(DateUtil.expiryQuickLabel(code), timestamp)
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(EXTRA_BARCODE)
                ?.takeIf { it.isNotBlank() }
                ?.let(viewModel::updateBarcode)
        }
    }

    LaunchedEffect(imageUri, pendingImageUris) {
        when {
            pendingImageUris.isNotEmpty() -> viewModel.initFromPhotos(context, pendingImageUris)
            else -> viewModel.initFromPhoto(context, imageUri)
        }
    }

    LaunchedEffect(initialBarcode) {
        if (!initialBarcode.isNullOrBlank()) {
            viewModel.updateBarcode(initialBarcode)
        }
    }

    LaunchedEffect(resultImageUri, resultImageUris, resultPhotoMode) {
        when {
            resultPhotoMode == SavePhotoMode.REPLACE_PRIMARY && resultImageUri != null -> {
                viewModel.replacePrimaryPhoto(context, resultImageUri)
            }

            resultImageUris.isNotEmpty() -> {
                viewModel.appendPhotos(context, resultImageUris)
            }

            resultImageUri != null -> {
                viewModel.appendPhotos(context, listOf(resultImageUri))
            }
        }
        if (resultImageUri != null || resultImageUris.isNotEmpty() || resultPhotoMode != null) {
            onConsumePendingResult()
        }
    }

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            if (continuousEntry) {
                // 连续录入：留在本页清空表单，提示后直接再拍一张。
                appSnackbar?.showMessage("已存入，再来一件")
                viewModel.reset()
                onOpenCamera(SavePhotoMode.REPLACE_PRIMARY)
            } else {
                onSaved()
                viewModel.reset()
            }
        }
    }

    // I6：与「空白默认表单」逐字段比对（忽略保存态的瞬时标记）判断有没有未保存内容。
    // 拍照/选图会写进 imagePaths，所以拍完照直接返回同样会触发确认，避免白拍一张。
    val hasUnsavedInput = state.copy(isSaving = false, isSaved = false) != SaveItemState()
    val requestBack: () -> Unit = {
        if (hasUnsavedInput) showDiscardDialog = true else onBack()
    }
    // 系统返回手势/按键与左上角返回走同一套确认逻辑（I6）。
    BackHandler { requestBack() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.imagePaths.isNotEmpty()) {
            AsyncImage(
                model = state.imagePaths.first(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(28.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.34f
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            // 顶部压暗照片（Material 常量黑，与主题无关），
                            // 下方过渡到语义「磨砂玻璃 / 卡片」色，深色模式下自动转为深底。
                            Color.Black.copy(alpha = 0.28f),
                            GlassWhite,
                            CardWhite
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = requestBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "快速录入",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (continuousEntry) "连续录入·开" else "连续录入",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (continuousEntry) Color.White.copy(alpha = 0.32f)
                            else Color.White.copy(alpha = 0.14f)
                        )
                        .clickable { viewModel.toggleContinuousEntry(!continuousEntry) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AppSurfaceCard(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(30.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .imePadding()
                            .padding(bottom = 120.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(width = 42.dp, height = 4.dp)
                                .background(DividerSoft, RoundedCornerShape(999.dp))
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        ItemImageGallery(
                            imagePaths = state.imagePaths,
                            onCapturePrimary = { onOpenCamera(SavePhotoMode.REPLACE_PRIMARY) },
                            onCaptureDetail = { onOpenCamera(SavePhotoMode.APPEND) },
                            onScanBarcode = {
                                scanLauncher.launch(Intent(context, ScanActivity::class.java))
                            },
                            onRemoveImage = viewModel::removePhoto,
                            onMoveImage = viewModel::movePhoto
                        )

                        state.barcode?.let { barcode ->
                            Spacer(modifier = Modifier.height(12.dp))
                            BarcodeAssociationCard(
                                barcode = barcode,
                                onClear = { viewModel.updateBarcode(null) }
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))
                        EditorSectionLabel(label = "物品名称")
                        Spacer(modifier = Modifier.height(8.dp))
                        EditorInputBox(
                            value = state.name,
                            onValueChange = viewModel::updateName,
                            placeholder = "请输入物品名称"
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        EditorSectionLabel(label = "分类")
                        Spacer(modifier = Modifier.height(8.dp))
                        EditorPickerField(
                            value = buildTreePathLabel(state.categoryId, categoryNodes),
                            placeholder = "点击选择分类",
                            onClick = { showCategoryPicker = true }
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        EditorSectionLabel(label = "存放位置")
                        Spacer(modifier = Modifier.height(8.dp))
                        EditorPickerField(
                            value = buildTreePathLabel(state.locationId, locationNodes),
                            placeholder = "点击选择存放位置",
                            onClick = { showLocationPicker = true }
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setAdvancedExpanded(!showAdvanced) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "更多信息（选填）",
                                fontSize = 14.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }

                        AnimatedVisibility(
                            visible = showAdvanced,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Column {
                                Spacer(modifier = Modifier.height(12.dp))
                                EditorSectionLabel(label = "数量")
                                Spacer(modifier = Modifier.height(8.dp))
                                QuantityStepper(
                                    quantity = state.quantity,
                                    unit = state.unit,
                                    onDecrease = { viewModel.updateQuantity(state.quantity - 1) },
                                    onIncrease = { viewModel.updateQuantity(state.quantity + 1) }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                UnitPickerRow(
                                    unit = state.unit,
                                    onUnitChange = viewModel::updateUnit
                                )

                                Spacer(modifier = Modifier.height(18.dp))
                                EditorSectionLabel(label = "购买日期")
                                Spacer(modifier = Modifier.height(8.dp))
                                EditorInputBox(
                                    value = state.purchaseDate?.let(DateUtil::formatDate).orEmpty(),
                                    onValueChange = {},
                                    placeholder = "点击选择日期",
                                    readOnly = true,
                                    modifier = Modifier.clickable { showPurchaseDatePicker = true },
                                    trailingContent = {
                                        Icon(
                                            imageVector = Icons.Default.CalendarToday,
                                            contentDescription = null,
                                            tint = TextHint,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )

                                Spacer(modifier = Modifier.height(18.dp))
                                EditorSectionLabel(label = "计量方式")
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    EditorSelectionChip(
                                        text = "按件消耗",
                                        selected = state.trackMode == Item.TRACK_CONSUMABLE,
                                        onClick = { viewModel.updateTrackMode(Item.TRACK_CONSUMABLE) }
                                    )
                                    EditorSelectionChip(
                                        text = "持续使用",
                                        selected = state.trackMode == Item.TRACK_DURABLE,
                                        onClick = { viewModel.updateTrackMode(Item.TRACK_DURABLE) }
                                    )
                                }
                                if (state.trackMode == Item.TRACK_DURABLE) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "电器、调料这类一直用的东西：不按件扣数量，按使用天数统计成本",
                                        fontSize = 12.sp,
                                        color = TextHint
                                    )

                                    Spacer(modifier = Modifier.height(18.dp))
                                    EditorSectionLabel(label = "开始使用")
                                    Spacer(modifier = Modifier.height(8.dp))
                                    EditorInputBox(
                                        value = (state.startUseTime ?: state.purchaseDate)
                                            ?.let(DateUtil::formatDate).orEmpty(),
                                        onValueChange = {},
                                        placeholder = "点击选择日期",
                                        readOnly = true,
                                        modifier = Modifier.clickable { showStartUseDatePicker = true },
                                        trailingContent = {
                                            Icon(
                                                imageVector = Icons.Default.CalendarToday,
                                                contentDescription = null,
                                                tint = TextHint,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.height(18.dp))
                                EditorSectionLabel(label = "有效期")
                                Spacer(modifier = Modifier.height(8.dp))
                                EditorInputBox(
                                    value = state.expireTime?.let(DateUtil::formatDate).orEmpty(),
                                    onValueChange = {},
                                    placeholder = "点击选择日期",
                                    readOnly = true,
                                    modifier = Modifier.clickable { showDatePicker = true },
                                    trailingContent = {
                                        Icon(
                                            imageVector = Icons.Default.CalendarToday,
                                            contentDescription = null,
                                            tint = TextHint,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    expiryQuickOptions.forEach { option ->
                                        EditorSelectionChip(
                                            text = option.label,
                                            selected = state.expireTime == option.timestamp,
                                            onClick = { viewModel.updateExpireTime(option.timestamp) }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(18.dp))
                                EditorSectionLabel(label = "到期提醒阶梯")
                                Spacer(modifier = Modifier.height(8.dp))
                                ReminderLadderPicker(
                                    selectedDays = state.reminderDays,
                                    defaultLadder = defaultReminderLadder,
                                    onDaysChange = viewModel::updateReminderDays
                                )

                                Spacer(modifier = Modifier.height(18.dp))
                                EditorSectionLabel(label = "价格（估算）")
                                Spacer(modifier = Modifier.height(8.dp))
                                EditorInputBox(
                                    value = state.price,
                                    onValueChange = viewModel::updatePrice,
                                    placeholder = "例如 8.90"
                                )

                                Spacer(modifier = Modifier.height(18.dp))
                                EditorSectionLabel(label = "备注")
                                Spacer(modifier = Modifier.height(8.dp))
                                EditorInputBox(
                                    value = state.note,
                                    onValueChange = viewModel::updateNote,
                                    placeholder = "可以补充品牌、规格或使用说明",
                                    singleLine = false,
                                    minHeight = 112
                                )
                            }
                        }
                    }
                }

                GradientButton(
                    text = if (state.isSaving) "保存中..." else "保存",
                    onClick = viewModel::save,
                    enabled = state.name.isNotBlank() && !state.isSaving,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 18.dp)
                )
            }
        }

        if (showCategoryPicker) {
            HierarchicalPickerDialog(
                title = "选择分类",
                nodes = categoryNodes,
                selectedId = state.categoryId,
                onSelect = viewModel::updateCategory,
                onDismissRequest = { showCategoryPicker = false },
                emptyHint = "还没有分类，可到「分类」页添加。"
            )
        }

        if (showLocationPicker) {
            HierarchicalPickerDialog(
                title = "选择存放位置",
                nodes = locationNodes,
                selectedId = state.locationId,
                onSelect = viewModel::updateLocation,
                onDismissRequest = { showLocationPicker = false },
                emptyHint = "还没有存放位置，可到「分类 → 管理位置」里添加。"
            )
        }

        if (showDatePicker) {
            ExpiryPickerDialog(
                selectedDateMillis = state.expireTime,
                onDismissRequest = { showDatePicker = false },
                onClear = {
                    viewModel.updateExpireTime(null)
                    showDatePicker = false
                },
                onConfirm = { selectedMillis ->
                    viewModel.updateExpireTime(selectedMillis)
                    showDatePicker = false
                }
            )
        }

        if (showPurchaseDatePicker) {
            ExpiryPickerDialog(
                selectedDateMillis = state.purchaseDate,
                onDismissRequest = { showPurchaseDatePicker = false },
                onClear = {
                    viewModel.updatePurchaseDate(null)
                    showPurchaseDatePicker = false
                },
                onConfirm = { selectedMillis ->
                    viewModel.updatePurchaseDate(selectedMillis)
                    showPurchaseDatePicker = false
                },
                title = "选择购买日期",
                yearRange = purchaseYearRange
            )
        }

        if (showStartUseDatePicker) {
            ExpiryPickerDialog(
                selectedDateMillis = state.startUseTime ?: state.purchaseDate,
                onDismissRequest = { showStartUseDatePicker = false },
                onClear = {
                    viewModel.updateStartUseTime(null)
                    showStartUseDatePicker = false
                },
                onConfirm = { selectedMillis ->
                    viewModel.updateStartUseTime(selectedMillis)
                    showStartUseDatePicker = false
                },
                title = "选择开始使用日期",
                yearRange = purchaseYearRange
            )
        }

        // I6：有未保存内容时返回先确认，避免误触返回键丢表单。
        if (showDiscardDialog) {
            AppDialog(
                title = "放弃修改？",
                subtitle = "表单里还有没保存的内容，返回就会丢失。",
                onDismissRequest = { showDiscardDialog = false },
                confirmText = "放弃修改",
                destructiveConfirm = true,
                onConfirm = {
                    showDiscardDialog = false
                    onBack()
                },
                dismissText = "继续编辑"
            ) {
                Text(
                    text = "确定要离开并丢弃当前的录入内容吗？",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

enum class SavePhotoMode {
    APPEND,
    REPLACE_PRIMARY
}
