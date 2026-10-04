package com.goodyaoshi.lemonbox.ui.screen.edit

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import com.goodyaoshi.lemonbox.ui.viewmodel.EditViewModel
import com.goodyaoshi.lemonbox.util.DateUtil

private data class ExpiryQuickOption(val label: String, val timestamp: Long)

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

    var showAdvanced by remember { mutableStateOf(true) }
    var showDatePicker by remember { mutableStateOf(false) }
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
    val expiryQuickOptions = remember {
        listOf(
            ExpiryQuickOption("7天", DateUtil.daysFromNow(7)),
            ExpiryQuickOption("1个月", DateUtil.monthsFromNow(1)),
            ExpiryQuickOption("3个月", DateUtil.monthsFromNow(3)),
            ExpiryQuickOption("6个月", DateUtil.monthsFromNow(6)),
            ExpiryQuickOption("12个月", DateUtil.monthsFromNow(12))
        )
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
                IconButton(onClick = onBack) {
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
                        placeholder = "请输入物品名称"
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
                            .clickable { showAdvanced = !showAdvanced }
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
                        text = "修改后会同步更新首页、家当和分类结果。",
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
    }
}
