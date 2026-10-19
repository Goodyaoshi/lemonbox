package com.goodyaoshi.lemonbox.ui.screen.imagecloud

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorSectionLabel
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.ImageCloudViewModel

/**
 * 云存储与同步设置（F20 · 仅正式包）：图片上传 + 多设备数据同步。
 *
 * 存储连接参数与访问密钥**都不在客户端**：它们只存在同步服务端（Cloudflare Worker），
 * 手机每次只换取几分钟有效的预签名地址来传图。因此这一页不再有「口令解锁」，
 * 取而代之的是**登录态**——未登录时云入口一律不动作，数据只留在本机。
 *
 * 存量图片迁移就是「上传本机图片」：对象键由图片内容哈希决定，
 * 已上传过的会被跳过，因此这个按钮**可以反复点**，不会产生重复对象。
 */
@Composable
fun ImageCloudScreen(
    onBack: () -> Unit,
    onOpenSyncLogin: () -> Unit,
    onOpenSyncDiagnostics: () -> Unit,
    viewModel: ImageCloudViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val sync by viewModel.syncState.collectAsState()
    val remoteSync by viewModel.remoteSyncState.collectAsState()

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
                    text = "云存储与同步",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "把家当图片存到你的云端存储。新图上传成功后会清掉本地副本，" +
                        "以节省手机空间；没传成功的会保留在本地等下次重传。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                if (!state.supported) {
                    Spacer(modifier = Modifier.height(16.dp))
                    AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                        Text(
                            text = "当前版本不支持",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "云存储与同步只在正式发布版中提供，开发调试版已关闭，避免测试图片传进真实的云端空间。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    return@Column
                }

                Spacer(modifier = Modifier.height(18.dp))
                EditorSectionLabel(label = "图片上传")
                Spacer(modifier = Modifier.height(8.dp))
                AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                    SwitchRow(
                        title = "启用图片上传",
                        subtitle = if (state.enabled) "已开启，可上传图片" else "关闭时图片只存本机",
                        checked = state.enabled,
                        onCheckedChange = viewModel::setEnabled
                    )
                    DividerLine()
                    // 下面两项都依赖上面的总开关，关闭时置灰——否则点了没有任何反应，
                    // 用户会以为是坏了。
                    SwitchRow(
                        title = "保存后自动上传",
                        subtitle = if (state.autoUpload) "新录入的图片随手传" else "只在点「上传本机图片」时传",
                        checked = state.autoUpload,
                        onCheckedChange = viewModel::setAutoUpload,
                        enabled = state.enabled
                    )
                    DividerLine()
                    SwitchRow(
                        title = "仅 WiFi 自动补传",
                        subtitle = if (state.backgroundSync) {
                            "每隔一阵在 WiFi 下自动把漏传的补上"
                        } else {
                            "已关闭，漏传的只会在点「上传本机图片」时补"
                        },
                        checked = state.backgroundSync,
                        onCheckedChange = viewModel::setBackgroundSync,
                        enabled = state.enabled
                    )
                }

                // 未就绪时先说清差什么，免得用户在一片表单里自己找原因。
                // 颜色按语义分：开关没开是**正常状态**（默认值），用中性色；
                // 开了却还缺东西才算问题，才用红字。早先一律红字，把默认状态渲染成了错误。
                state.readinessBlocker?.let { blocker ->
                    Spacer(modifier = Modifier.height(10.dp))
                    NoticeLine(text = blocker, isError = state.enabled)
                }

                if (!state.loggedIn) {
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = onOpenSyncLogin) {
                        Text(
                            text = "去登录云端账号",
                            color = OrangeStart,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))
                EditorSectionLabel(label = "上传本机图片")
                Spacer(modifier = Modifier.height(8.dp))
                AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                    Text(
                        text = "把手机里现有的家当图片都传上去（首次迁移用）。已传过的会自动跳过。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint
                    )
                    // 未上传张数：本地算得、不发网络请求，让用户随时知道还差多少。
                    state.pendingCount?.let { pending ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (pending == 0) {
                                "所有图片都已上传"
                            } else {
                                "还有 $pending 张图片没上传"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (pending == 0) TextSecondary else OrangeStart
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    if (sync.running) {
                        Text(
                            text = "正在同步… ${sync.processed}/${sync.total}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { sync.fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(MaterialTheme.shapes.small),
                            color = OrangeStart,
                            trackColor = SurfaceWarmDeep
                        )
                        Spacer(modifier = Modifier.height(8.dp))
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
                                color = TextHint,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(onClick = viewModel::cancelSync) {
                            Text(text = "取消", color = StatusExpired, style = MaterialTheme.typography.titleSmall)
                        }
                    } else {
                        GradientButton(
                            text = "上传本机图片",
                            onClick = viewModel::syncAll
                        )
                        if (sync.total > 0) {
                            Spacer(modifier = Modifier.height(10.dp))
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
                        NoticeLine(text = error, isError = true)
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))
                EditorSectionLabel(label = "多设备同步")
                Spacer(modifier = Modifier.height(8.dp))
                AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                    Text(
                        text = "把本机数据（物品、记账等）与其它设备对齐：每条变更都以服务端的先后序号为准，"
                            + "谁都不覆盖谁。不含图片——图片是否上传由上面的开关单独决定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    // 独立开关：图片占空间与流量、文字数据体积很小，两者取舍不同。
                    // 早先共用「启用图片上传」，想省空间的人一关，连数据都不再同步了。
                    SwitchRow(
                        title = "启用多设备同步",
                        subtitle = if (state.cloudSyncEnabled) "已开启，打开 App 时自动同步" else "关闭后本机数据不再对外同步",
                        checked = state.cloudSyncEnabled,
                        onCheckedChange = viewModel::setCloudSyncEnabled
                    )
                    DividerLine()
                    // 登录态是这一段的唯一前提：未登录时下面的一切都不动作。
                    if (!state.loggedIn) {
                        Text(
                            text = "未登录，数据只留在本机",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        GradientButton(
                            text = "登录云端账号",
                            onClick = onOpenSyncLogin
                        )
                    } else {
                        Text(
                            text = state.username?.let { "已登录：$it" } ?: "已登录",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = remoteSync.lastSyncedAt?.let { "上次同步：${formatSyncTime(it)}" }
                                ?: "还没同步过",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        // 冲突可见（S4 的云端版）：云端同步是后台自动的，没法像局域网那样
                        // 弹窗逐条勾选，因此把「有几条本机改动没能上去」如实报出来——
                        // 服务端发现有人在这之后改过就不覆盖，由用户知道而不是静默丢弃。
                        // 0 条时不出现，避免每次同步都挂一行噪声。
                        if (remoteSync.lastConflicts > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            NoticeLine(
                                text = "有 ${remoteSync.lastConflicts} 条两边都改过，" +
                                    "已保留服务端版本（可在设备同步里逐条对比）",
                                isError = false
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        if (remoteSync.running) {
                            Text(
                                text = "正在同步…",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        } else {
                            GradientButton(
                                text = "立即同步",
                                onClick = viewModel::syncNow,
                                enabled = state.cloudSyncEnabled
                            )
                        }
                        // 冲突提示「已保留服务端版本」只是结论，逐条证据在诊断页：
                        // 逐表计数、冲突留痕的字段对比都在那里，故入口常驻登录态分支。
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onOpenSyncDiagnostics, enabled = state.cloudSyncEnabled) {
                            Text(text = "打开同步诊断", color = TextSecondary)
                        }
                    }
                    // 未就绪的原因直接说清（未登录 / 未开启），比只在按钮上变灰有用。
                    state.cloudSyncBlocker?.let { blocker ->
                        Spacer(modifier = Modifier.height(8.dp))
                        NoticeLine(text = blocker, isError = state.cloudSyncEnabled)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "图片按内容指纹存放，同一张图在任何设备上都归到同一个位置，不会存重复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    // 子开关在总开关关闭时置灰：它们依赖总开关，但视觉上原本毫无区分。
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                // 禁用时同时压暗文字，让「暂时不可用」一眼可辨。
                color = if (enabled) TextPrimary else TextHint
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) TextSecondary else TextHint
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun DividerLine() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(DividerSoft)
            .height(1.dp)
    )
}

/** 一行状态提示：失败用红字，中性 / 成功用次要色，避免通篇警告色。 */
@Composable
private fun NoticeLine(text: String, isError: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) StatusExpired else TextSecondary,
        modifier = Modifier.padding(vertical = 2.dp)
    )
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
