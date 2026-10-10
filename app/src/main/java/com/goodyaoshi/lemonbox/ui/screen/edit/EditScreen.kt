package com.goodyaoshi.lemonbox.ui.screen.edit

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.BarcodeAssociationCard
import com.goodyaoshi.lemonbox.ui.components.EditorInputBox
import com.goodyaoshi.lemonbox.ui.components.EditorPickerField
import com.goodyaoshi.lemonbox.ui.components.EditorSectionLabel
import com.goodyaoshi.lemonbox.ui.components.EditorSelectionChip
import com.goodyaoshi.lemonbox.ui.components.ExpiryPickerDialog
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.components.HierarchicalPickerDialog
import com.goodyaoshi.lemonbox.ui.components.ItemImageGallery
import com.goodyaoshi.lemonbox.ui.components.QuantityStepper
import com.goodyaoshi.lemonbox.ui.components.ReminderLadderPicker
import com.goodyaoshi.lemonbox.ui.components.UnitPickerRow
import com.goodyaoshi.lemonbox.ui.components.TreeNode
import com.goodyaoshi.lemonbox.ui.components.buildTreePathLabel
import com.goodyaoshi.lemonbox.ui.scan.EXTRA_BARCODE
import com.goodyaoshi.lemonbox.ui.scan.ScanActivity
import com.goodyaoshi.lemonbox.ui.screen.save.SavePhotoMode
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.EditItemState
import com.goodyaoshi.lemonbox.ui.viewmodel.EditViewModel
import com.goodyaoshi.lemonbox.util.DateUtil
import java.time.LocalDate

private data class ExpiryQuickOption(val label: String, val timestamp: Long)

/** 购买日期、开始使用时间都可回溯过去 10 年。 */
private val historyYearRange: IntRange
    get() = (LocalDate.now().year - 10)..LocalDate.now().year

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditScreen(
    itemId: Long,
    pendingImageUri: Uri?,
    pendingImageUris: List<Uri>,
    pendingPhotoMode: SavePhotoMode?,
    resultImageUri: Uri?,
    resultImageUris: List<Uri>,
    resultPhotoMode: SavePhotoMode?,
    onConsumePendingResult: () -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onOpenCamera: (SavePhotoMode) -> Unit,
    viewModel: EditViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by viewModel.state.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val locations by viewModel.locations.collectAsState()
    val defaultReminderLadder by viewModel.defaultReminderLadder.collectAsState()

    // 「更多信息」默认折叠，展开后跨会话记住用户的选择（I6），与录入页共用一份。
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

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(EXTRA_BARCODE)
                ?.takeIf { it.isNotBlank() }
                ?.let(viewModel::updateBarcode)
        }
    }
    val expiryQuickCodes by viewModel.expiryQuickOptions.collectAsState()
    // 快捷档位来自设置页的自定义配置（x天/x周/x月/x年），与录入页保持一致。
    val expiryQuickOptions = remember(expiryQuickCodes) {
        expiryQuickCodes.mapNotNull { code ->
            val timestamp = DateUtil.expiryQuickTimestamp(code) ?: return@mapNotNull null
            ExpiryQuickOption(DateUtil.expiryQuickLabel(code), timestamp)
        }
    }

    LaunchedEffect(itemId) {
        viewModel.loadItem(itemId)
    }

    LaunchedEffect(pendingImageUri, pendingImageUris, pendingPhotoMode) {
        when {
            pendingPhotoMode == SavePhotoMode.REPLACE_PRIMARY && pendingImageUri != null -> {
                viewModel.replacePrimaryPhoto(context, pendingImageUri)
            }

            pendingImageUris.isNotEmpty() -> {
                viewModel.appendPhotos(context, pendingImageUris)
            }

            pendingImageUri != null -> {
                viewModel.appendPhotos(context, listOf(pendingImageUri))
            }
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
            onSaved()
        }
    }

    // I6：加载完成后拍一张表单快照当作「未修改」基准，之后只要和它不一致就认为有改动。
    // 用 itemId 作为 key，切换物品时基准会重新采集。
    var loadedBaseline by remember(itemId) { mutableStateOf<EditItemState?>(null) }
    LaunchedEffect(state.isLoaded, state.itemId) {
        if (state.isLoaded && state.itemId == itemId && loadedBaseline == null) {
            loadedBaseline = state.copy(isSaving = false, isSaved = false)
        }
    }
    val hasUnsavedInput = loadedBaseline?.let {
        state.copy(isSaving = false, isSaved = false) != it
    } ?: false
    val requestBack: () -> Unit = {
        if (hasUnsavedInput) showDiscardDialog = true else onBack()
    }
    // 系统返回手势/按键与左上角返回走同一套确认逻辑（I6）。
    BackHandler { requestBack() }

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

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
                        tint = TextPrimary
                    )
                }
                Text(
                    text = "编辑物品",
                    fontSize = 22.sp,
                    color = TextPrimary
                )
            }

            AppSurfaceCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp)
                ) {
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
                        Spacer(modifier = Modifier.size(12.dp))
                        BarcodeAssociationCard(
                            barcode = barcode,
                            onClear = { viewModel.updateBarcode(null) }
                        )
                    }

                    Spacer(modifier = Modifier.size(18.dp))
                    EditorSectionLabel(label = "物品名称")
                    Spacer(modifier = Modifier.size(8.dp))
                    EditorInputBox(
                        value = state.name,
                        onValueChange = viewModel::updateName,
                        placeholder = "给这件东西起个名字吧"
                    )

                    Spacer(modifier = Modifier.size(16.dp))
                    EditorSectionLabel(label = "分类")
                    Spacer(modifier = Modifier.size(8.dp))
                    EditorPickerField(
                        value = buildTreePathLabel(state.categoryId, categoryNodes),
                        placeholder = "点击选择分类",
                        onClick = { showCategoryPicker = true }
                    )

                    Spacer(modifier = Modifier.size(16.dp))
                    EditorSectionLabel(label = "存放位置")
                    Spacer(modifier = Modifier.size(8.dp))
                    EditorPickerField(
                        value = buildTreePathLabel(state.locationId, locationNodes),
                        placeholder = "点击选择存放位置",
                        onClick = { showLocationPicker = true }
                    )

                    Spacer(modifier = Modifier.size(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setAdvancedExpanded(!showAdvanced) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "更多信息",
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
                            Spacer(modifier = Modifier.size(12.dp))
                            EditorSectionLabel(label = "数量")
                            Spacer(modifier = Modifier.size(8.dp))
                            QuantityStepper(
                                quantity = state.quantity,
                                unit = state.unit,
                                onDecrease = { viewModel.updateQuantity(state.quantity - 1) },
                                onIncrease = { viewModel.updateQuantity(state.quantity + 1) }
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            UnitPickerRow(
                                unit = state.unit,
                                onUnitChange = viewModel::updateUnit
                            )

                            Spacer(modifier = Modifier.size(18.dp))
                            EditorSectionLabel(label = "购买日期")
                            Spacer(modifier = Modifier.size(8.dp))
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

                            // 只有持续使用型才有「开始使用」概念，按件消耗型不展示该字段。
                            if (state.trackMode == Item.TRACK_DURABLE) {
                                Spacer(modifier = Modifier.size(18.dp))
                                EditorSectionLabel(label = "开始使用")
                                Spacer(modifier = Modifier.size(8.dp))
                                EditorInputBox(
                                    value = state.startUseTime?.let(DateUtil::formatDate).orEmpty(),
                                    onValueChange = {},
                                    placeholder = "还没开始用，点击补录",
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

                            Spacer(modifier = Modifier.size(18.dp))
                            EditorSectionLabel(label = "计量方式")
                            Spacer(modifier = Modifier.size(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(
                                    text = "不按件扣数量，按使用天数统计成本",
                                    fontSize = 12.sp,
                                    color = TextHint
                                )
                            }

                            Spacer(modifier = Modifier.size(18.dp))
                            EditorSectionLabel(label = "有效期")
                            Spacer(modifier = Modifier.size(8.dp))
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
                            Spacer(modifier = Modifier.size(10.dp))
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

                            Spacer(modifier = Modifier.size(18.dp))
                            EditorSectionLabel(label = "到期提醒阶梯")
                            Spacer(modifier = Modifier.size(8.dp))
                            ReminderLadderPicker(
                                selectedDays = state.reminderDays,
                                defaultLadder = defaultReminderLadder,
                                onDaysChange = viewModel::updateReminderDays
                            )

                            Spacer(modifier = Modifier.size(18.dp))
                            EditorSectionLabel(label = "价格（估算）")
                            Spacer(modifier = Modifier.size(8.dp))
                            EditorInputBox(
                                value = state.price,
                                onValueChange = viewModel::updatePrice,
                                placeholder = "例如 8.90"
                            )

                            Spacer(modifier = Modifier.size(18.dp))
                            EditorSectionLabel(label = "备注")
                            Spacer(modifier = Modifier.size(8.dp))
                            EditorInputBox(
                                value = state.note,
                                onValueChange = viewModel::updateNote,
                                placeholder = "可以补充物品来源、规格或注意事项",
                                singleLine = false,
                                minHeight = 112
                            )
                        }
                    }

                    Spacer(modifier = Modifier.size(24.dp))
                    GradientButton(
                        text = if (state.isSaving) "保存中..." else "保存修改",
                        onClick = viewModel::save,
                        enabled = state.name.isNotBlank() && !state.isSaving
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "改完会同步更新你的首页、家当和分类。",
                        fontSize = 12.sp,
                        color = TextHint,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
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
                yearRange = historyYearRange
            )
        }

        if (showStartUseDatePicker) {
            ExpiryPickerDialog(
                selectedDateMillis = state.startUseTime,
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
                yearRange = historyYearRange
            )
        }

        // I6：有未保存内容时返回先确认，避免误触返回键丢掉编辑。
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
                    text = "确定要离开并丢弃当前的修改吗？",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            }
        }
    }
}
