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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorInputBox
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
 * 存储连接参数与访问密钥整份**加密**后随包内置（CI Secrets 注入密文）、界面上**不展示也不可改**：
 * 所有客户端必须指向同一处存储才能互相看到数据，暴露给用户只会带来误改。用户唯一要做的是
 * 输入**解锁口令**——解开密文后配置由 [ImageCloudViewModel] 交给 Keystore 加密保存在本机，
 * 包内始终只有密文，没有任何明文密钥。
 *
 * 存量图片迁移就是「上传本机图片」：对象键由图片内容哈希决定，
 * 已上传过的会被跳过，因此这个按钮**可以反复点**，不会产生重复对象。
 */
@Composable
fun ImageCloudScreen(
    onBack: () -> Unit,
    viewModel: ImageCloudViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val sync by viewModel.syncState.collectAsState()
    val cloud by viewModel.cloudSyncState.collectAsState()
    // 「清除本机配置」的二次确认开关（SET1）：清掉后必须重输口令、且口令本机不留，
    // 因此不能一键生效，走破坏性确认框。
    var showClearConfirm by remember { mutableStateOf(false) }

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

                Spacer(modifier = Modifier.height(20.dp))
                EditorSectionLabel(label = "解锁口令")
                Spacer(modifier = Modifier.height(8.dp))
                AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                    Text(
                        text = if (state.unlocked) {
                            "已解锁。存储地址与密钥解密后只加密存在本机，既不进安装包，也不随备份外传。" +
                                "要换一份配置就重新输入口令。"
                        } else {
                            "存储地址与访问密钥都以密文内置在安装包里，输入口令即可解开。" +
                                "口令只在本机用于解密，不会上传，也不会被保存。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint
                    )
                    // 口令来源指引（SET1）：说清口令从哪来、本机不留、找不回，
                    // 免得用户在这个只有一个输入框的卡片里猜「我该填什么」。
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "口令是当初配置这份存储时设的，由你或当初设置它的人保管；"
                            + "本机不保存、也无法帮你找回，忘了只能去问设置的人。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint
                    )
                    // 安全提示：密文与 salt 都在包里，拿到包的人可以离线穷举口令，
                    // 强度完全决定安全性。如实告知，而不是给一个「已加密」的错觉。
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = StatusExpired,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = "口令是解开包内密文的唯一凭据，请用足够长的口令，" +
                                "不要用生日、手机号这类好猜的组合——安装包谁都能拿到，" +
                                    "口令太简单会被人拿去慢慢试出来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusExpired
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    EditorInputBox(
                        value = state.passphrase,
                        onValueChange = viewModel::updatePassphrase,
                        placeholder = "解锁口令",
                        // 打码显示，避免旁人一眼看到口令。
                        visualTransformation = PasswordVisualTransformation()
                    )
                    if (state.unlocked) {
                        // 破坏性动作走二次确认（SET1）：清掉本机配置后必须重输口令才能再用，
                        // 口令本机不留、无法自动恢复，所以不能一键生效。
                        TextButton(onClick = { showClearConfirm = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = StatusExpired,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(text = "清除本机已保存的配置", color = StatusExpired, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // 清除本机配置的破坏性确认框（SET1）：说清影响范围，确认后才执行。
                if (showClearConfirm) {
                    AppDialog(
                        title = "清除本机已保存的配置？",
                        subtitle = "会清掉本机保存的存储配置与已确认的密钥，之后要重新输入口令才能再用；"
                            + "云端已上传的图片不受影响。",
                        confirmText = "清除",
                        destructiveConfirm = true,
                        onConfirm = {
                            viewModel.lockCloud()
                            showClearConfirm = false
                        },
                        onDismissRequest = { showClearConfirm = false }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                GradientButton(
                    text = when {
                        state.unlocking -> "正在解锁…"
                        state.testing -> "正在测试…"
                        else -> "解锁并测试连接"
                    },
                    enabled = !state.unlocking && !state.testing,
                    onClick = viewModel::unlockAndTest
                )

                state.testMessage?.let { message ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = OrangeStart
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                        }
                        NoticeLine(text = message, isError = !state.testOk)
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
                        text = "把本机数据（物品、记账等）与同一存储上的其他设备合并：各设备各推自己的变更，"
                            + "再各自合并，谁都不覆盖谁。不含图片——图片是否上传由上面的开关单独决定。",
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
                    Text(
                        text = cloud.lastSyncedAt?.let { "上次同步：${formatSyncTime(it)}" } ?: "还没同步过",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                    // 冲突可见（S4 的云端版）：云端同步是后台自动的，没法像局域网那样
                    // 弹窗逐条勾选，因此把「有几条被较新的一方覆盖」如实报出来，
                    // 而不是让 LWW 静默吃掉。0 条时不出现，避免每次同步都挂一行噪声。
                    if (cloud.lastConflicts > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        NoticeLine(
                            text = "有 ${cloud.lastConflicts} 条两边都改过，已取较新的一方" +
                                "（可在设备同步里逐条对比）",
                            isError = false
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    if (cloud.running) {
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
                    // 未就绪的原因直接说清（缺凭据 / 未开启），比只在按钮上变灰有用。
                    state.cloudSyncBlocker?.let { blocker ->
                        Spacer(modifier = Modifier.height(8.dp))
                        NoticeLine(text = blocker, isError = state.cloudSyncEnabled)
                    }
                    cloud.lastError?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        NoticeLine(text = error, isError = true)
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
