package com.goodyaoshi.lemonbox.ui.screen.imagecloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncState
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncState
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorInputBox
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.components.InlineNotice
import com.goodyaoshi.lemonbox.ui.components.NoticeVariant
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.ImageCloudUiState
import com.goodyaoshi.lemonbox.ui.viewmodel.ImageCloudViewModel
import com.goodyaoshi.lemonbox.ui.viewmodel.SyncLoginUiState
import com.goodyaoshi.lemonbox.ui.viewmodel.SyncLoginViewModel

/**
 * 云同步设置（F20）。
 *
 * 页面只回答两个问题：**有没有连上**、**还有没有东西没传**。
 * 登录成功即开启数据与图片的自动同步，退出登录即回到纯本地；
 * 不再提供「图片上传 / 自动上传 / 后台补传 / 多设备同步」四个相互依赖的开关。
 *
 * 存储连接参数与访问密钥都不在客户端：它们只存在同步服务端（Cloudflare Worker），
 * 手机每次只换取几分钟有效的预签名地址来传图。
 */
@Composable
fun ImageCloudScreen(
    onBack: () -> Unit,
    onOpenSyncDiagnostics: () -> Unit,
    viewModel: ImageCloudViewModel = hiltViewModel(),
    loginViewModel: SyncLoginViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val sync by viewModel.syncState.collectAsState()
    val remoteSync by viewModel.remoteSyncState.collectAsState()
    val loginState by loginViewModel.uiState.collectAsState()
    // 退出登录要重新输密码才能连上，属于不可轻触的破坏性动作，先二次确认。
    var showLogoutConfirm by remember { mutableStateOf(false) }

    // 同步跑完（running 由 true 落回 false）就重算未上传张数，让界面上的数字跟着更新。
    LaunchedEffect(sync.running) {
        if (!sync.running) viewModel.refreshPendingCount()
    }

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
            ScreenHeader(title = "云同步", onBack = onBack)

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                when {
                    !state.supported && !state.imageSupported -> AppSurfaceCard {
                        Text(
                            text = "当前版本不支持云同步",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "云同步只在正式发布版中提供，开发调试版已关闭。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    !state.loggedIn -> LoginPanel(
                        state = state,
                        loginState = loginState,
                        onUsername = loginViewModel::updateUsername,
                        onPassword = loginViewModel::updatePassword,
                        onCustomBaseUrl = loginViewModel::updateCustomBaseUrl,
                        onSaveServer = loginViewModel::saveServerSetting,
                        onProbe = loginViewModel::probeServer,
                        onLogin = loginViewModel::login
                    )

                    else -> CloudPanel(
                        state = state,
                        remoteSync = remoteSync,
                        sync = sync,
                        onSyncNow = viewModel::syncNow,
                        onCancelRemoteSync = viewModel::cancelRemoteSync,
                        onUploadAll = viewModel::syncAll,
                        onCancelUpload = viewModel::cancelSync,
                        onOpenDiagnostics = onOpenSyncDiagnostics,
                        onLogout = { showLogoutConfirm = true }
                    )
                }
            }
        }

        if (showLogoutConfirm) {
            AppDialog(
                title = "退出登录？",
                subtitle = "退出后本机不再自动同步；本机数据与云端数据都不会被删除，下次登录即可继续。",
                confirmText = "退出",
                destructiveConfirm = true,
                onConfirm = {
                    loginViewModel.logout()
                    showLogoutConfirm = false
                },
                onDismissRequest = { showLogoutConfirm = false }
            )
        }
    }
}

/** 未登录：先说清登录会带来什么，再给最短的登录路径；服务器设置收进「连接设置」。 */
@Composable
private fun LoginPanel(
    state: ImageCloudUiState,
    loginState: SyncLoginUiState,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onCustomBaseUrl: (String) -> Unit,
    onSaveServer: () -> Unit,
    onProbe: () -> Unit,
    onLogin: () -> Unit
) {
    var showAdvanced by remember { mutableStateOf(false) }

    AppSurfaceCard {
        Text(
            text = "登录后自动同步",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = when {
                state.supported && state.imageSupported ->
                    "家当、记账与图片会随账号自动对齐。本机始终保留完整数据，断网也能用。"
                state.imageSupported -> "登录后图片会自动上传到云端。本机始终保留完整数据，断网也能用。"
                else -> "家当、记账等数据会随账号自动对齐。本机始终保留完整数据，断网也能用。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(18.dp))
        EditorInputBox(
            value = loginState.inputUsername,
            onValueChange = onUsername,
            placeholder = "账号"
        )
        Spacer(modifier = Modifier.height(10.dp))
        EditorInputBox(
            value = loginState.inputPassword,
            onValueChange = onPassword,
            placeholder = "密码",
            visualTransformation = PasswordVisualTransformation()
        )

        Spacer(modifier = Modifier.height(16.dp))
        GradientButton(
            text = if (loginState.busy) "正在登录…" else "登录并开启同步",
            enabled = !loginState.busy && loginState.baseUrl.isNotEmpty(),
            onClick = onLogin
        )

        if (loginState.baseUrl.isEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            InlineNotice(
                message = "这个安装包没有配置同步服务器地址，暂时无法登录。",
                variant = NoticeVariant.Danger
            )
        }

        loginState.message?.let { message ->
            Spacer(modifier = Modifier.height(10.dp))
            InlineNotice(
                message = message,
                variant = if (loginState.messageIsError) NoticeVariant.Danger else NoticeVariant.Success
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(
                text = if (showAdvanced) "收起连接设置" else "连接设置",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (showAdvanced) {
            Spacer(modifier = Modifier.height(4.dp))
            EditorInputBox(
                value = loginState.customBaseUrl,
                onValueChange = onCustomBaseUrl,
                placeholder = "备用服务器地址（可选，https://…）"
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSaveServer) {
                    Text(text = "保存地址", color = TextSecondary)
                }
                TextButton(onClick = onProbe, enabled = !loginState.probing) {
                    Text(
                        text = if (loginState.probing) "检测中…" else "测试连接",
                        color = TextSecondary
                    )
                }
            }
            loginState.serverDetail?.let { detail ->
                InlineNotice(
                    message = detail,
                    variant = if (loginState.serverDetailIsError) NoticeVariant.Danger else NoticeVariant.Info
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "账号由服务端预先配置；密码只用于换取登录令牌，不保存在本机。",
            style = MaterialTheme.typography.bodySmall,
            color = TextHint
        )
    }
}

/** 已登录：一张卡说清同步状态，一张卡处理图片；不再出现任何「开/关」。 */
@Composable
private fun CloudPanel(
    state: ImageCloudUiState,
    remoteSync: RemoteSyncState,
    sync: ImageCloudSyncState,
    onSyncNow: () -> Unit,
    onCancelRemoteSync: () -> Unit,
    onUploadAll: () -> Unit,
    onCancelUpload: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onLogout: () -> Unit
) {
    if (state.supported) {
        AppSurfaceCard {
            SectionHeading(
                icon = Icons.Default.Sync,
                title = "自动同步已开启",
                subtitle = state.username?.let { "账号 $it" } ?: "已登录"
            )

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "上次同步：" + (remoteSync.lastSyncedAt?.let(::formatSyncTime) ?: "还没有同步过"),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            if (remoteSync.lastConflicts > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                InlineNotice(
                    message = "有 ${remoteSync.lastConflicts} 条两边都改过，已按本地优先合并。可在「同步诊断」里逐条查看。",
                    variant = NoticeVariant.Info
                )
            }
            if (remoteSync.lastSkippedPayloads > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                InlineNotice(
                    message = "有 ${remoteSync.lastSkippedPayloads} 条服务端记录无法解析，已跳过。" +
                        "请先导出本地备份，再到「同步诊断」查看。",
                    variant = NoticeVariant.Warning
                )
            }
            if (remoteSync.lastTruncated) {
                Spacer(modifier = Modifier.height(8.dp))
                InlineNotice(
                    message = "本次只拉取了部分变更，再点一次「立即同步」可继续。",
                    variant = NoticeVariant.Info
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            if (remoteSync.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = OrangeStart
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = remoteSync.phase ?: "正在同步…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
                TextButton(onClick = onCancelRemoteSync) {
                    Text(text = "取消同步", color = StatusExpired)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onSyncNow) {
                        Text(text = "立即同步", color = OrangeStart, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            remoteSync.lastError?.let { error ->
                Spacer(modifier = Modifier.height(6.dp))
                InlineNotice(message = error, variant = NoticeVariant.Danger)
            }
            state.lastSyncMessage?.let { message ->
                Spacer(modifier = Modifier.height(6.dp))
                InlineNotice(message = message, variant = NoticeVariant.Info)
            }
        }
    }

    if (state.imageSupported) {
        Spacer(modifier = Modifier.height(14.dp))
        AppSurfaceCard {
            SectionHeading(
                icon = Icons.Default.CloudUpload,
                title = "图片同步",
                subtitle = "保存后自动上传 · 仅 WiFi 下补传"
            )

            Spacer(modifier = Modifier.height(12.dp))
            state.pendingCount?.let { pending ->
                Text(
                    text = if (pending == 0) "图片已全部上传" else "还有 $pending 张图片未上传",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (pending == 0) TextSecondary else OrangeStart
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            if (sync.running) {
                Text(
                    text = "正在上传 ${sync.processed}/${sync.total}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { sync.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(MaterialTheme.shapes.small),
                    color = OrangeStart,
                    trackColor = SurfaceWarmDeep
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "新传 ${sync.uploaded} · 已存在跳过 ${sync.skipped}" +
                        if (sync.failed > 0) " · 失败 ${sync.failed}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
                sync.currentFile?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextHint
                    )
                }
                TextButton(onClick = onCancelUpload) {
                    Text(text = "取消上传", color = StatusExpired)
                }
            } else {
                GradientButton(text = "上传本机图片", onClick = onUploadAll)
                if (sync.total > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "上次：新传 ${sync.uploaded} 张 · 云端已有跳过 ${sync.skipped} 张" +
                            if (sync.failed > 0) " · 失败 ${sync.failed} 张" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            sync.lastError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                InlineNotice(message = error, variant = NoticeVariant.Danger)
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onOpenDiagnostics) {
            Text(text = "同步诊断", color = TextSecondary)
        }
        TextButton(onClick = onLogout) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                tint = StatusExpired,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(text = "退出登录", color = StatusExpired)
        }
    }
}

@Composable
private fun SectionHeading(
    icon: ImageVector,
    title: String,
    subtitle: String
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
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextHint
            )
        }
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
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
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
    }
}

/** 上次同步时间：当天只显示时分，跨天补上日期，避免每次都读一串完整时间戳。 */
private fun formatSyncTime(timestamp: Long): String {
    val target = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = java.util.Calendar.getInstance()
    val sameDay = target.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
        target.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "MM-dd HH:mm"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(target.time)
}
