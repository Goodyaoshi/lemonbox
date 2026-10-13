package com.goodyaoshi.lemonbox.ui.screen.ocr

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.goodyaoshi.lemonbox.data.ocr.OcrModelState
import com.goodyaoshi.lemonbox.data.ocr.OcrModelTier
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.SectionHeader
import com.goodyaoshi.lemonbox.ui.components.rememberGalleryPickerLauncher
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.StatusWarning
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.OcrViewModel
import com.goodyaoshi.lemonbox.util.LedgerMath
import java.io.File
import java.util.Locale

/**
 * 文字识别页（F01/F02/F03）。
 *
 * 模型下载遵守「用户有感知」原则：点击下载入口先弹确认框（移动数据时额外提示流量），
 * 下载中有进度与取消按钮，绝不静默后台下载。识别全程离线，图片不出手机。
 *
 * [modeLabel] 模式外显（IA-8）：从存物/记账/「我的」三个语境共用这一页，
 * 进页先说清当前模式与识别结果的去向；为 null 时不显示（保留无语境的兜底）。
 */
@Composable
fun OcrScreen(
    onBack: () -> Unit,
    onOpenCamera: () -> Unit,
    cameraResultUri: String?,
    onConsumeCameraResult: () -> Unit,
    onNavigateToRecord: (amountCents: Long?, remark: String) -> Unit,
    onNavigateToSave: (name: String, imagePath: String?) -> Unit,
    modeLabel: String? = null,
    // 出口按来路收敛（IA-3）：从「快速录入」进来的只回录入、从「记一笔」进来的只回记账。
    // 此前两个动作恒并列展示，会把想「录入家当」的用户推去记账，与来意相反。
    showRecordAction: Boolean = true,
    showSaveAction: Boolean = true,
    viewModel: OcrViewModel = hiltViewModel()
) {
    val modelState by viewModel.modelState.collectAsState()
    val selectedTier by viewModel.selectedTier.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val snackbar = LocalAppSnackbar.current
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var pendingTier by remember { mutableStateOf(OcrModelTier.DEFAULT) }

    // 请求某个档位：已安装直接切换；未安装弹下载确认（确认后切档并开始下载）。
    val requestTier: (OcrModelTier) -> Unit = { tier ->
        if (viewModel.isTierInstalled(tier)) {
            viewModel.selectTier(tier)
            snackbar?.postMessage("已切换到${tier.displayName}")
        } else {
            pendingTier = tier
            showDownloadDialog = true
        }
    }

    // 自适应选图：有 Photo Picker 就用它（免权限），否则申请权限后走 MediaStore 打开真图库。
    val context = LocalContext.current
    var showGalleryPermissionDialog by remember { mutableStateOf(false) }
    val pickFromGallery = rememberGalleryPickerLauncher(
        onPicked = { uris -> uris.firstOrNull()?.let(viewModel::recognize) },
        // 被拒后不再只丢一句提示：给出「去设置」的出路，并点明可直接改用「拍照」这条不需要授权
        // 的替代路径。旧设备（无 Photo Picker）才会走到这里，是新系统上也存在的少数权限申请点。
        onPermissionDenied = { showGalleryPermissionDialog = true }
    )

    // 相机页回传的图片：拿到就识别，并清掉一次性结果，避免返回时重复触发。
    LaunchedEffect(cameraResultUri) {
        if (!cameraResultUri.isNullOrBlank()) {
            viewModel.recognize(Uri.parse(cameraResultUri))
            onConsumeCameraResult()
        }
    }

    LaunchedEffect(state.error) {
        val message = state.error ?: return@LaunchedEffect
        snackbar?.showMessage(message)
        viewModel.consumeError()
    }

    val canRecognize = modelState is OcrModelState.Installed && !state.isRecognizing

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
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
                    text = "文字识别",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                // 模式外显（IA-8）：一行小字交代当前语境与识别完的去向，不抢视觉焦点。
                modeLabel?.let { label ->
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        color = TextHint,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
                ModelStatusCard(
                    modelState = modelState,
                    selectedTier = selectedTier,
                    isTierInstalled = viewModel::isTierInstalled,
                    onDownloadClick = { requestTier(selectedTier) },
                    onCancelDownload = viewModel::cancelDownload,
                    onDeleteClick = { showDeleteDialog = true },
                    onSelectTier = requestTier
                )

                Spacer(modifier = Modifier.height(22.dp))

                SectionHeader(
                    title = "选一张图片",
                    subtitle = if (canRecognize) null else "下载并安装识别模型后才能开始识别"
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CaptureTile(
                        icon = Icons.Default.PhotoCamera,
                        label = "拍照",
                        enabled = canRecognize,
                        onClick = onOpenCamera,
                        modifier = Modifier.weight(1f)
                    )
                    CaptureTile(
                        icon = Icons.Default.PhotoLibrary,
                        label = "从相册选",
                        enabled = canRecognize,
                        onClick = { pickFromGallery() },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (state.isRecognizing) {
                    Spacer(modifier = Modifier.height(20.dp))
                    AppSurfaceCard(
                        shape = RoundedCornerShape(22.dp),
                        shadowElevation = 12.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = OrangeStart
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "正在识别文字…",
                                fontSize = 14.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }

                state.imagePath?.let { path ->
                    Spacer(modifier = Modifier.height(20.dp))
                    SectionHeader(title = "识别用的图片")
                    Spacer(modifier = Modifier.height(10.dp))
                    AsyncImage(
                        model = File(path),
                        contentDescription = "待识别的图片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .clip(RoundedCornerShape(22.dp))
                    )
                }

                if (state.lines.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    SectionHeader(title = "识别到的文字")
                    Spacer(modifier = Modifier.height(10.dp))
                    AppSurfaceCard(
                        shape = RoundedCornerShape(22.dp),
                        shadowElevation = 12.dp
                    ) {
                        state.lines.forEachIndexed { index, line ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                        .height(1.dp)
                                        .background(DividerSoft)
                                )
                            }
                            Text(
                                text = line.text,
                                fontSize = 15.sp,
                                color = TextPrimary
                            )
                        }
                    }

                    // 出口按来路收敛（IA-3）：只保留与本次来意一致的那一个动作，
                    // 不再把「记账」与「入库」并列摆给所有人看。
                    if (showRecordAction || showSaveAction) {
                        Spacer(modifier = Modifier.height(20.dp))
                        SectionHeader(title = "接着做点什么")
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    if (showRecordAction) {
                        val ledger = state.ledgerSuggestion
                        ActionTile(
                            icon = Icons.AutoMirrored.Filled.ReceiptLong,
                            title = "一键记账",
                            subtitle = ledgerAmountLabel(ledger?.amountCents, ledger?.remark),
                            enabled = true,
                            onClick = {
                                onNavigateToRecord(ledger?.amountCents, ledger?.remark.orEmpty())
                            }
                        )
                        if (showSaveAction) Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (showSaveAction) {
                        val itemName = state.itemSuggestion?.name.orEmpty()
                        ActionTile(
                            icon = Icons.Default.Inventory2,
                            title = "一键入库",
                            subtitle = itemName.ifBlank { "没认出品名，去家当手动填写" },
                            // 认不出品名时不禁用：按钮就在入口页头上，禁用会把用户困在
                            // 「唯一出口点不动」的死路上（这正是原先被推向记账的原因）。
                            // 改为照常进入录入页，由用户手动补品名。
                            enabled = true,
                            onClick = { onNavigateToSave(itemName, state.imagePath) }
                        )
                    }
                }
            }
        }
    }

    if (showDownloadDialog) {
        val metered = remember(showDownloadDialog) { viewModel.isMeteredConnection() }
        AppDialog(
            title = "下载${pendingTier.displayName}模型",
            onDismissRequest = { showDownloadDialog = false },
            confirmText = "开始下载",
            onConfirm = {
                showDownloadDialog = false
                viewModel.selectTier(pendingTier)
                viewModel.startDownload()
            }
        ) {
            Text(
                text = "模型约 ${formatBytes(pendingTier.totalBytes)}，建议在 WiFi 下下载。" +
                    "识别一直在手机本地完成，图片不会上传。",
                color = TextSecondary,
                fontSize = 14.sp
            )
            if (metered) {
                Text(
                    text = "当前是移动数据，下载会消耗流量，确定要继续吗？",
                    color = StatusWarning,
                    fontSize = 13.sp
                )
            }
            Text(
                text = "下载过程中可以随时取消。",
                color = TextHint,
                fontSize = 12.sp
            )
        }
    }

    if (showDeleteDialog) {
        AppDialog(
            title = "删除识别模型？",
            onDismissRequest = { showDeleteDialog = false },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteModel()
            }
        ) {
            Text(
                text = "删除后需要重新下载才能继续识别文字。",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
    }

    if (showGalleryPermissionDialog) {
        AppDialog(
            title = "需要相册权限",
            onDismissRequest = { showGalleryPermissionDialog = false },
            confirmText = "去设置",
            onConfirm = {
                showGalleryPermissionDialog = false
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                )
            }
        ) {
            Text(
                text = "从相册选图需要读取图片的权限。也可以直接用「拍照」识别，那条路不需要授权。",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * 模型状态卡：顶部常驻两档模型（轻量版 / 精准版）的选择行，
 * 选中已安装的档位直接切换、未安装的档位弹出下载确认；下方按
 * 未安装 / 下载中 / 已就绪 / 失败四种状态给出对应出口。
 */
@Composable
private fun ModelStatusCard(
    modelState: OcrModelState,
    selectedTier: OcrModelTier,
    isTierInstalled: (OcrModelTier) -> Boolean,
    onDownloadClick: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteClick: () -> Unit,
    onSelectTier: (OcrModelTier) -> Unit
) {
    AppSurfaceCard(
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 14.dp
    ) {
        Text(
            text = "识别模型",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(12.dp))
        OcrModelTier.entries.forEachIndexed { index, tier ->
            TierOptionRow(
                tier = tier,
                selected = tier == selectedTier,
                installed = isTierInstalled(tier),
                enabled = modelState !is OcrModelState.Downloading,
                onClick = { onSelectTier(tier) }
            )
            if (index < OcrModelTier.entries.lastIndex) {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        when (modelState) {
            is OcrModelState.NotInstalled -> {
                Text(
                    text = "「${selectedTier.displayName}」未安装，首次使用需要下载，之后无网也能识别。",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                GradientButton(text = "下载模型", onClick = onDownloadClick)
            }

            is OcrModelState.Downloading -> {
                Text(
                    text = "正在下载「${selectedTier.displayName}」",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { modelState.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = OrangeStart,
                    trackColor = SurfaceWarmDeep
                )
                Text(
                    text = "${formatBytes(modelState.downloadedBytes)} / ${formatBytes(modelState.totalBytes)}" +
                        "  (${(modelState.fraction * 100).toInt()}%)",
                    fontSize = 12.sp,
                    color = TextHint,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = "离开本页下载也会继续，随时可以取消。",
                    fontSize = 12.sp,
                    color = TextHint,
                    modifier = Modifier.padding(top = 2.dp)
                )
                TextButton(
                    onClick = onCancelDownload,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text(text = "取消下载", color = StatusExpired, fontSize = 14.sp)
                }
            }

            is OcrModelState.Installed -> {
                Text(
                    text = "「${selectedTier.displayName}」已就绪，识别全程离线，图片不会离开手机。",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                TextButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text(text = "删除模型", color = TextHint, fontSize = 14.sp)
                }
            }

            is OcrModelState.Failed -> {
                Text(
                    text = modelState.message,
                    fontSize = 13.sp,
                    color = StatusExpired
                )
                Spacer(modifier = Modifier.height(12.dp))
                GradientButton(text = "重试", onClick = onDownloadClick)
            }
        }
    }
}

/** 单个档位的选择行：单选圆点 + 名称与说明 + 体积与安装状态。 */
@Composable
private fun TierOptionRow(
    tier: OcrModelTier,
    selected: Boolean,
    installed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) OrangeStart.copy(alpha = 0.10f) else SurfaceWarmDeep)
            .then(
                if (selected) Modifier.border(1.dp, OrangeStart.copy(alpha = 0.35f), shape) else Modifier
            )
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (selected) OrangeStart else Color.Transparent)
                .border(
                    width = 2.dp,
                    color = if (selected) OrangeStart else TextHint.copy(alpha = 0.6f),
                    shape = CircleShape
                )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tier.displayName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) TextPrimary else TextHint
                )
                if (installed) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "已安装",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OrangeStart
                    )
                }
            }
            Text(
                text = tier.summary,
                fontSize = 12.sp,
                color = TextHint,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            text = "约 ${formatBytes(tier.totalBytes)}",
            fontSize = 12.sp,
            color = TextHint
        )
    }
}

/** 拍照 / 相册入口方块：不可用时整体置灰，避免误点。 */
@Composable
private fun CaptureTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppSurfaceCard(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(vertical = 18.dp),
        shadowElevation = 12.dp,
        enabled = enabled,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) OrangeStart else TextHint,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) TextPrimary else TextHint
            )
        }
    }
}

/** 结果页的「一键记账 / 一键入库」入口。 */
@Composable
private fun ActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppSurfaceCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        shadowElevation = 12.dp,
        enabled = enabled,
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (enabled) OrangeStart.copy(alpha = 0.12f) else SurfaceWarmDeep),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) OrangeStart else TextHint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) TextPrimary else TextHint
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = TextHint
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextHint
            )
        }
    }
}

/** 一键记账的副标题：有金额展示金额与备注，否则提示将把文字作为备注。 */
private fun ledgerAmountLabel(amountCents: Long?, remark: String?): String {
    return when {
        amountCents != null && !remark.isNullOrBlank() ->
            "金额 ¥${String.format(Locale.US, "%.2f", LedgerMath.centsToYuan(amountCents))} · ${remark.trim()}"

        amountCents != null ->
            "金额 ¥${String.format(Locale.US, "%.2f", LedgerMath.centsToYuan(amountCents))}"

        !remark.isNullOrBlank() -> remark.trim()
        else -> "把识别到的文字作为备注"
    }
}

/** 字节数格式化：MB 保留一位小数，不足 1MB 用 KB。 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
