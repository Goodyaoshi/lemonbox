package com.goodyaoshi.lemonbox.ui.screen.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.InlineNotice
import com.goodyaoshi.lemonbox.ui.components.NoticeVariant
import com.goodyaoshi.lemonbox.ui.components.SectionTitle
import com.goodyaoshi.lemonbox.ui.viewmodel.DataCenterUiState
import com.goodyaoshi.lemonbox.ui.viewmodel.DataCenterViewModel
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.util.DateUtil

/**
 * 数据与同步（IA-1）：所有与「数据去哪儿」有关的操作都收在这一页。
 *
 * 三层，按用户实际会问的顺序排列：
 * 1. **自动同步**——登录后由云端在后台完成，这里是它的状态与入口；
 * 2. **局域网同步**——同一 WiFi 下两台设备都在时的手动对齐；
 * 3. **本地备份**——不依赖网络与账号的最后一份保险。
 *
 * 早先「云端账号」「云存储与同步」「设备同步」是三个并列入口，用户要自己
 * 拼出它们的关系；现在只有一张状态卡，登录态与上次同步结果在入口就能看到。
 */
@Composable
fun DataCenterScreen(
    onBack: () -> Unit,
    onOpenLanSync: () -> Unit,
    onOpenImageCloud: () -> Unit = {},
    viewModel: DataCenterViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) viewModel.export(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.import(uri) }

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
                    text = "数据与同步",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
            }

            SectionTitle(
                title = "自动同步",
                modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 8.dp)
            )
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp),
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 12.dp,
                onClick = onOpenImageCloud
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(OrangeStart.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = OrangeStart,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.cloudLoggedIn) "云同步已开启" else "云同步未开启",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = cloudSubtitle(state),
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
                if (!state.cloudLoggedIn) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "登录并开启 ›",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OrangeStart
                    )
                }
                if (state.cloudLoggedIn && state.cloudLastError != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    InlineNotice(
                        message = state.cloudLastError.orEmpty(),
                        variant = NoticeVariant.Danger
                    )
                }
                if (state.cloudLoggedIn && state.cloudSkippedPayloads > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    InlineNotice(
                        message = "有 ${state.cloudSkippedPayloads} 条服务端记录无法解析，已跳过；" +
                            "请先导出本地备份，再进云同步查看。",
                        variant = NoticeVariant.Warning
                    )
                }
            }

            SectionTitle(
                title = "同一 WiFi",
                modifier = Modifier.padding(start = 24.dp, top = 22.dp, bottom = 8.dp)
            )
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp),
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 12.dp
            ) {
                MenuRow(
                    icon = Icons.Default.Sync,
                    title = "局域网同步",
                    subtitle = "两台设备都在时手动对齐；不经过云端，适合一次性搬运",
                    onClick = onOpenLanSync
                )
            }

            SectionTitle(
                title = "本地备份",
                modifier = Modifier.padding(start = 24.dp, top = 22.dp, bottom = 8.dp)
            )
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp),
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 12.dp
            ) {
                MenuRow(
                    icon = Icons.Default.Save,
                    title = "导出备份文件",
                    subtitle = "打包全部数据与本机图片；换机、重装前先存一份",
                    onClick = { exportLauncher.launch(viewModel.defaultBackupFileName()) }
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.Restore,
                    title = "从备份恢复",
                    subtitle = "按较新者合并，不会删除现有数据",
                    onClick = {
                        importLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream"
                            )
                        )
                    }
                )
            }

            if (state.busy) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                    InlineNotice(message = "正在处理，请稍候…", variant = NoticeVariant.Info)
                }
            }
            state.message?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                    InlineNotice(
                        message = message,
                        variant = if (state.messageIsError) NoticeVariant.Danger else NoticeVariant.Success
                    )
                }
            }
        }
    }
}

/** 云同步入口的一行状态：未登录、同步中、失败、或最近一次成功时间。 */
private fun cloudSubtitle(state: DataCenterUiState): String = when {
    !state.cloudLoggedIn -> "登录后自动同步数据与图片，本机数据始终保留"
    state.cloudSyncing -> "正在同步…"
    state.cloudSkippedPayloads > 0 -> "有 ${state.cloudSkippedPayloads} 条服务端记录未落地，点此查看"
    state.cloudLastError != null -> "上次同步失败，点此查看"
    else -> "上次同步：" + DateUtil.relativeSyncText(state.cloudLastSyncedAt)
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
