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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
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
 * 只读配置项：标签 + 值，刻意不用输入框样式，避免让人以为可以点进去改。
 */
@Composable
private fun ReadOnlyField(
    label: String,
    value: String,
    emptyText: String = "（未设置）"
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, fontSize = 12.sp, color = TextHint)
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value.ifBlank { emptyText },
            fontSize = 14.sp,
            color = TextPrimary
        )
    }
}

/**
 * 图片云端存储设置（F20 · 仅正式包）。
 *
 * 分工（对应「配置进包、凭据手填」）：
 * - 存储地址 / 区域 / 存储桶 / 前缀：随包内置（CI Secrets 注入），此处只读展示；
 * - AccessKey / SecretKey：只存在本机，由 [ImageCloudViewModel] 交给 Keystore 加密保存，
 *   绝不进 APK，界面也不回显已保存的明文。
 *
 * 存量图片迁移就是「一键同步全部图片」：对象键由图片内容哈希决定，
 * 已上传过的会被跳过，因此这个按钮**可以反复点**，不会产生重复对象。
 */
@Composable
fun ImageCloudScreen(
    onBack: () -> Unit,
    viewModel: ImageCloudViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val sync by viewModel.syncState.collectAsState()

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
                    text = "图片云端存储",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "把家当图片存到你的对象存储（S3 兼容）。新图上传成功后会清掉本地副本，" +
                        "以节省手机空间；没传成功的会保留在本地等下次重传。",
                    fontSize = 13.sp,
                    color = TextSecondary
                )

                if (!state.supported) {
                    Spacer(modifier = Modifier.height(16.dp))
                    AppSurfaceCard(shape = RoundedCornerShape(22.dp), shadowElevation = 12.dp) {
                        Text(
                            text = "当前版本不支持",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "图片云端存储只在正式发布版中提供，开发调试版已关闭，避免测试图片传进真实存储桶。",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    return@Column
                }

                Spacer(modifier = Modifier.height(18.dp))
                EditorSectionLabel(label = "开关")
                Spacer(modifier = Modifier.height(8.dp))
                AppSurfaceCard(shape = RoundedCornerShape(22.dp), shadowElevation = 12.dp) {
                    SwitchRow(
                        title = "启用图片云端存储",
                        subtitle = if (state.enabled) "已开启，可上传图片" else "关闭时不做任何上传",
                        checked = state.enabled,
                        onCheckedChange = viewModel::setEnabled
                    )
                    DividerLine()
                    SwitchRow(
                        title = "保存后自动上传",
                        subtitle = if (state.autoUpload) "新录入的图片随手传" else "只在手动同步时上传",
                        checked = state.autoUpload,
                        onCheckedChange = viewModel::setAutoUpload
                    )
                    DividerLine()
                    SwitchRow(
                        title = "仅 WiFi 自动补传",
                        subtitle = if (state.backgroundSync) {
                            "每隔一阵在 WiFi 下自动把漏传的补上"
                        } else {
                            "已关闭，漏传的只会在手动同步时补"
                        },
                        checked = state.backgroundSync,
                        onCheckedChange = viewModel::setBackgroundSync
                    )
                }

                // 未就绪时先说清差什么，免得用户在一片表单里自己找原因。
                state.readinessBlocker?.let { blocker ->
                    Spacer(modifier = Modifier.height(10.dp))
                    NoticeLine(text = blocker, isError = true)
                }

                Spacer(modifier = Modifier.height(20.dp))
                EditorSectionLabel(label = "访问凭据")
                Spacer(modifier = Modifier.height(8.dp))
                AppSurfaceCard(shape = RoundedCornerShape(22.dp), shadowElevation = 12.dp) {
                    Text(
                        text = if (state.hasCredentials) {
                            "已保存凭据（加密存在本机，不会随 APK 分发）。要更换就重新填写。"
                        } else {
                            "填你的对象存储访问密钥。它只保存在本机、经系统 Keystore 加密，" +
                                "不会编译进安装包。"
                        },
                        fontSize = 12.sp,
                        color = TextHint
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    EditorInputBox(
                        value = state.accessKey,
                        onValueChange = viewModel::updateAccessKey,
                        placeholder = "Access Key ID"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    EditorInputBox(
                        value = state.secretKey,
                        onValueChange = viewModel::updateSecretKey,
                        placeholder = "Secret Access Key",
                        // 打码显示，避免旁人一眼看到密钥。
                        visualTransformation = PasswordVisualTransformation()
                    )
                    if (state.hasCredentials) {
                        TextButton(onClick = viewModel::clearCredentials) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = StatusExpired,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(text = "清除已保存的凭据", color = StatusExpired, fontSize = 13.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                EditorSectionLabel(label = "存储连接")
                Spacer(modifier = Modifier.height(8.dp))
                AppSurfaceCard(shape = RoundedCornerShape(22.dp), shadowElevation = 12.dp) {
                    Text(
                        text = "由发布版本固定，不可修改——所有客户端必须指向同一处存储才能互相看到图片。",
                        fontSize = 12.sp,
                        color = TextHint
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ReadOnlyField(label = "存储地址", value = state.endpoint)
                    Spacer(modifier = Modifier.height(10.dp))
                    ReadOnlyField(label = "区域", value = state.region)
                    Spacer(modifier = Modifier.height(10.dp))
                    ReadOnlyField(label = "存储桶", value = state.bucket)
                    Spacer(modifier = Modifier.height(10.dp))
                    ReadOnlyField(
                        label = "对象前缀",
                        value = state.prefix,
                        emptyText = "（无，直接放在桶根下）"
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                GradientButton(
                    text = if (state.testing) "正在测试…" else "保存并测试连接",
                    enabled = !state.testing,
                    onClick = viewModel::saveAndTest
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
                EditorSectionLabel(label = "同步已有图片")
                Spacer(modifier = Modifier.height(8.dp))
                AppSurfaceCard(shape = RoundedCornerShape(22.dp), shadowElevation = 12.dp) {
                    Text(
                        text = "把手机里现有的家当图片都传上去（首次迁移用）。已传过的会自动跳过。",
                        fontSize = 12.sp,
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
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (pending == 0) TextSecondary else OrangeStart
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    if (sync.running) {
                        Text(
                            text = "正在同步… ${sync.processed}/${sync.total}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { sync.fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = OrangeStart,
                            trackColor = SurfaceWarmDeep
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "新传 ${sync.uploaded} · 已存在跳过 ${sync.skipped}" +
                                if (sync.failed > 0) " · 失败 ${sync.failed}" else "",
                            fontSize = 12.sp,
                            color = TextHint
                        )
                        sync.currentFile?.let { name ->
                            Text(
                                text = name,
                                fontSize = 11.sp,
                                color = TextHint,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(onClick = viewModel::cancelSync) {
                            Text(text = "取消", color = StatusExpired, fontSize = 14.sp)
                        }
                    } else {
                        GradientButton(
                            text = "检查并补传",
                            onClick = viewModel::syncAll
                        )
                        if (sync.total > 0) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "上次：新传 ${sync.uploaded} 张 · 云端已有跳过 ${sync.skipped} 张" +
                                    if (sync.failed > 0) " · 失败 ${sync.failed} 张" else "",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    sync.lastError?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        NoticeLine(text = error, isError = true)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "图片按内容指纹存放，同一张图在任何设备上都归到同一个位置，多设备同步不会存重复。",
                    fontSize = 12.sp,
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
    onCheckedChange: (Boolean) -> Unit
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
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(text = subtitle, fontSize = 12.sp, color = TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
        fontSize = 12.sp,
        color = if (isError) StatusExpired else TextSecondary,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
