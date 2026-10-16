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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    // 移动数据流量提醒只记一次的标记（SET1）：用户确认过就记住，不再反复弹流量提醒。
    var meteredConfirmed by rememberSaveable { mutableStateOf(false) }

    // 模型面板默认收起（IA-9）：模型装好之后，它是「已完成的一次性配置」，
    // 常驻展开会把「选一张图片识别」这件正事挤到屏幕外。只有尚未就绪时
    // （那才是真正的阻塞，用户必须处理）才自动展开。
    // 用 remember(modelReady) 绑定：下载完成的那一刻自动收起，不必用户手动关。
    val modelReady = modelState is OcrModelState.Installed
    var showModelPanel by remember(modelReady) { mutableStateOf(!modelReady) }

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
                    // 字号走主题字阶（F6）：页面标题统一到 headlineMedium（22sp）。
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                // 模式外显（IA-8）：一行小字交代当前语境与识别完的去向，不抢视觉焦点。
                modeLabel?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
                ModelStatusSection(
                    modelState = modelState,
                    selectedTier = selectedTier,
                    expanded = showModelPanel,
                    onToggleExpand = { showModelPanel = !showModelPanel },
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
                        shape = MaterialTheme.shapes.extraLarge,
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
                                style = MaterialTheme.typography.titleSmall,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            // 识别中可取消（SET1）：识别可能较慢，给一个出口，避免用户被卡在原地。
                            TextButton(onClick = viewModel::cancelRecognize) {
                                Text(
                                    text = "取消",
                                    color = StatusExpired,
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
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
                            .clip(MaterialTheme.shapes.extraLarge)
                    )
                }

                if (state.lines.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    SectionHeader(title = "识别到的文字")
                    Spacer(modifier = Modifier.height(10.dp))
                    AppSurfaceCard(
                        shape = MaterialTheme.shapes.extraLarge,
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
                                style = MaterialTheme.typography.titleSmall,
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
                // 记住用户已接受移动数据下载（SET1）：确认一次后不再重复提示流量。
                if (metered) meteredConfirmed = true
                viewModel.selectTier(pendingTier)
                viewModel.startDownload()
            }
        ) {
            Text(
                text = "模型约 ${formatBytes(pendingTier.totalBytes)}，建议在 WiFi 下下载。" +
                    "识别一直在手机本地完成，图片不会上传。",
                color = TextSecondary,
                style = MaterialTheme.typography.titleSmall
            )
            // 流量提醒仅在用户尚未确认过时出现（SET1）：确认一次后不再重复打扰。
            if (metered && !meteredConfirmed) {
                Text(
                    text = "当前是移动数据，下载会消耗流量，确定要继续吗？",
                    color = StatusWarning,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = "下载过程中可以随时取消。",
                color = TextHint,
                style = MaterialTheme.typography.bodySmall
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
                style = MaterialTheme.typography.titleSmall
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
                text = "从相册选图需要图片权限；也可以直接「拍照」，那条路不需要授权。",
                color = TextSecondary,
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

/**
 * 模型状态区（IA-9）。
 *
 * 折叠态只占一行：模型名 + 状态（或下载进度），点击展开。
 * 展开态才是完整的两档选择行 + 按状态给出的出口。
 *
 * 为什么默认收起：模型是「配置一次、长期复用」的东西。装好之后每次进来
 * 都被整块面板占住页首，会把「选一张图片识别」这件真正的正事挤到屏幕外。
 * 而**未就绪时必须展开**——那时它是阻塞项，用户不处理就没法用。
 * 下载中同样保持展开，让进度条始终可见（用户点了下载就想看着它）。
 */
@Composable
private fun ModelStatusSection(
    modelState: OcrModelState,
    selectedTier: OcrModelTier,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    isTierInstalled: (OcrModelTier) -> Boolean,
    onDownloadClick: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteClick: () -> Unit,
    onSelectTier: (OcrModelTier) -> Unit
) {
    AppSurfaceCard(
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = if (expanded) 14.dp else 8.dp
    ) {
        // ---- 摘要行：始终可见，点击切换展开 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 触达面积达标（F7）：摘要行不足 48dp 高，先撑出最小可点区再绘制。
                .minimumInteractiveComponentSize()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onToggleExpand)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "识别模型 · ${selectedTier.displayName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = modelStateSummary(modelState),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (modelState) {
                        is OcrModelState.Failed -> StatusExpired
                        is OcrModelState.Installed -> TextHint
                        else -> TextSecondary
                    },
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = if (expanded) "收起模型设置" else "展开模型设置",
                tint = TextHint,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 90f else 0f)
            )
        }

        // 折叠时若正在下载，仍要露出进度——否则用户看不到下载是否在走。
        if (!expanded && modelState is OcrModelState.Downloading) {
            Spacer(modifier = Modifier.height(10.dp))
            DownloadProgress(modelState)
        }

        if (!expanded) return@AppSurfaceCard

        Spacer(modifier = Modifier.height(14.dp))
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
                    text = "「${selectedTier.displayName}」未安装，首次使用需下载，之后无网也能识别。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                GradientButton(text = "下载模型", onClick = onDownloadClick)
            }

            is OcrModelState.Downloading -> {
                Text(
                    text = "正在下载「${selectedTier.displayName}」",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                DownloadProgress(modelState)
                TextButton(
                    onClick = onCancelDownload,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text(text = "取消下载", color = StatusExpired, style = MaterialTheme.typography.titleSmall)
                }
            }

            is OcrModelState.Installed -> {
                Text(
                    text = "「${selectedTier.displayName}」已就绪，识别全程离线，图片不离开手机。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                TextButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text(text = "删除模型", color = TextHint, style = MaterialTheme.typography.titleSmall)
                }
            }

            is OcrModelState.Failed -> {
                Text(
                    text = modelState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusExpired
                )
                Spacer(modifier = Modifier.height(12.dp))
                GradientButton(text = "重试", onClick = onDownloadClick)
            }
        }
    }
}

/** 摘要行的一句话状态，让折叠态也能看出「现在能不能识别」。 */
private fun modelStateSummary(state: OcrModelState): String = when (state) {
    is OcrModelState.Installed -> "已就绪，可离线识别"
    is OcrModelState.NotInstalled -> "未安装，点开下载"
    is OcrModelState.Downloading -> "正在下载 ${(state.fraction * 100).toInt()}%"
    is OcrModelState.Failed -> "下载失败，点开重试"
}

/** 下载进度条与字节数，展开 / 折叠两处共用。 */
@Composable
private fun DownloadProgress(state: OcrModelState.Downloading) {
    LinearProgressIndicator(
        progress = { state.fraction },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(MaterialTheme.shapes.small),
        color = OrangeStart,
        trackColor = SurfaceWarmDeep
    )
    Text(
        text = "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}" +
            "  (${(state.fraction * 100).toInt()}%)",
        style = MaterialTheme.typography.bodySmall,
        color = TextHint,
        modifier = Modifier.padding(top = 8.dp)
    )
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
    val shape = MaterialTheme.shapes.medium
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
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) TextPrimary else TextHint
                )
                if (installed) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "已安装",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = OrangeStart
                    )
                }
            }
            Text(
                text = tier.summary,
                style = MaterialTheme.typography.bodySmall,
                color = TextHint,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            text = "约 ${formatBytes(tier.totalBytes)}",
            style = MaterialTheme.typography.bodySmall,
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
        shape = MaterialTheme.shapes.extraLarge,
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
                style = MaterialTheme.typography.titleSmall,
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
        shape = MaterialTheme.shapes.extraLarge,
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
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) TextPrimary else TextHint
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
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
