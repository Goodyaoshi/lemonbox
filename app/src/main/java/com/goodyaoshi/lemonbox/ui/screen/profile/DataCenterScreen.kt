package com.goodyaoshi.lemonbox.ui.screen.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.ProfileViewModel
import kotlinx.coroutines.launch

/**
 * 数据中心（IA-1）：设备同步与数据导出的统一二级页。
 * 从「我的」拆出，让个人设置与数据管理各归其位。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DataCenterScreen(
    onBack: () -> Unit,
    onOpenLanSync: () -> Unit,
    // 云存储入口（F20）：与设备同步同属「数据怎么走」，放同一张卡里。
    onOpenImageCloud: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val backupState by viewModel.backupState.collectAsState()
    var showBackupDialog by remember { mutableStateOf(false) }
    // 恢复内置分类的预览闸门（G-6）：先列出将新增的内置分类再确认，非空才弹明细，
    // 空名单弹「都在了」提示；取消不写库。
    val scope = rememberCoroutineScope()
    var restorePreview by remember { mutableStateOf<List<String>?>(null) }
    var showNoMissingTip by remember { mutableStateOf(false) }

    val readableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        uri?.let(viewModel::exportReadable)
    }

    LaunchedEffect(backupState.message) {
        showBackupDialog = backupState.message != null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
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
                    text = "数据中心",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
            }

            SectionTitle(
                title = "数据同步",
                modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 8.dp)
            )
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp),
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 12.dp
            ) {
                MenuRow(
                    icon = Icons.Default.Sync,
                    title = "设备同步",
                    subtitle = "同一 WiFi 下用配对码互相补齐数据",
                    onClick = onOpenLanSync
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.CloudUpload,
                    title = "云存储与同步",
                    // 名称与页面内容对齐：该页不只有图片上传，还有文字数据的多设备同步。
                    subtitle = "图片上云、多设备数据同步，仅正式版可用",
                    onClick = onOpenImageCloud
                )
            }

            SectionTitle(
                title = "导出与维护",
                modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp)
            )
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp),
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 12.dp
            ) {
                MenuRow(
                    icon = Icons.Default.Description,
                    title = "导出可读报告",
                    subtitle = "生成可阅读、可打印的清单（家当 / 临期 / 收支 / 纪念日）",
                    onClick = {
                        readableLauncher.launch("lemonbox-report-${System.currentTimeMillis()}.html")
                    }
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.Refresh,
                    title = "恢复内置分类",
                    subtitle = "补齐食材/零食等内置分类，误删或同步后丢失也能找回",
                    onClick = {
                        // 预览闸门（G-6）：先算「会新增哪些」，再让用户确认，不点了就写库。
                        scope.launch {
                            val missing = viewModel.previewMissingBuiltInCategories()
                            if (missing.isEmpty()) showNoMissingTip = true else restorePreview = missing
                        }
                    }
                )
            }
        }
    }

    if (backupState.isBusy) {
        // 处理中：给一个不可取消的等待弹窗，明确告诉她在导出还是导入，并提示别退出。
        AppDialog(
            title = backupState.busyLabel ?: "处理中…",
            onDismissRequest = {},
            dismissText = null,
            confirmText = null
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = OrangeStart
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = "大概几秒钟，先别退出应用",
                    color = TextSecondary,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }

    restorePreview?.let { missing ->
        // 预览闸门（G-6）：明细用多列胶囊铺开并限高滚动，几十个名字也不撑破弹窗；
        // 取消不执行，确认才补种并沿用既有的结果提示。
        AppDialog(
            title = "要恢复哪些内置分类？",
            onDismissRequest = { restorePreview = null },
            subtitle = "以下分类还没有，恢复后可随时在分类页管理",
            confirmText = "恢复 ${missing.size} 个分类",
            onConfirm = {
                restorePreview = null
                viewModel.restoreBuiltInData()
            }
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                missing.forEach { name ->
                    PillTag(text = name, backgroundColor = TagOrange, contentColor = TagOrangeText)
                }
            }
        }
    }

    if (showNoMissingTip) {
        // 空名单：没有会新增的分类，直接安抚一句，不让她空跑一趟。
        AppDialog(
            title = "内置分类都在了，不用恢复",
            onDismissRequest = { showNoMissingTip = false },
            confirmText = "知道了",
            dismissText = null
        )
    }

    if (showBackupDialog && backupState.message != null) {
        // 结果分级：成功用「搞定了」的轻松口吻，失败保留原因并说明可重试，别让她以为数据坏了。
        AppDialog(
            title = if (backupState.isError) "没成功" else "搞定了",
            onDismissRequest = {
                showBackupDialog = false
                viewModel.consumeBackupMessage()
            },
            confirmText = "知道了",
            onConfirm = {
                showBackupDialog = false
                viewModel.consumeBackupMessage()
            }
        ) {
            Text(
                text = backupState.message.orEmpty(),
                color = TextSecondary,
                style = MaterialTheme.typography.titleSmall
            )
            if (backupState.isError) {
                Text(
                    text = "你的数据没有改动，可以再试一次。",
                    color = TextHint,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 14.dp)
                // 圆角走形状令牌（F6）：小圆角统一到 small。
                .clip(MaterialTheme.shapes.small)
                .background(LemonEnd)
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = TextSecondary
        )
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(OrangeStart.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = OrangeStart,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
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

@Composable
private fun DividerSpacer() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(DividerSoft)
            .height(1.dp)
    )
}
