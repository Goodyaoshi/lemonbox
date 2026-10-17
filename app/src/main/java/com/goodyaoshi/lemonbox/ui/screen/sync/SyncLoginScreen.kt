package com.goodyaoshi.lemonbox.ui.screen.sync

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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.SyncLoginViewModel

/**
 * 云端账号（登录 / 退出）。
 *
 * 登录是「要不要上云」的总闸：**未登录时一切云入口都不动作**，数据只留在本机；
 * 登录后本机数据才与其他设备对齐。账号由服务端预先配置（一人一个），
 * 客户端既不内置账号密码，也不保存密码——登录换来的是会话令牌，加密存在本机 Keystore 之后。
 */
@Composable
fun SyncLoginScreen(
    onBack: () -> Unit,
    viewModel: SyncLoginViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    // 退出登录走二次确认：确认后要重新输密码才能再连上，不能一点就生效。
    var showLogoutConfirm by remember { mutableStateOf(false) }

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
                    text = "云端账号",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                if (!state.supported) {
                    AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                        Text(
                            text = "当前版本不支持",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "多设备同步只在正式发布版中提供，开发调试版已关闭。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    return@Column
                }

                Text(
                    text = "登录后，这台手机与其它设备的数据会自动对齐；"
                        + "不登录也不影响使用，数据只留在本机。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(18.dp))
                if (state.loggedIn) {
                    EditorSectionLabel(label = "登录状态")
                    Spacer(modifier = Modifier.height(8.dp))
                    AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                        Text(
                            text = "已登录：${state.username.orEmpty()}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        state.expiresAt?.let { expiresAt ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "登录有效期至 ${formatDate(expiresAt)}，到期后需要重新登录。",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextHint
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "这台设备：${state.deviceName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextHint
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        TextButton(onClick = { showLogoutConfirm = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = null,
                                tint = StatusExpired,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = "退出登录",
                                color = StatusExpired,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    EditorSectionLabel(label = "登录")
                    Spacer(modifier = Modifier.height(8.dp))
                    AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                        Text(
                            text = "用当初在服务端配置好的账号密码登录。密码只在这一步校验，"
                                + "不会存在本机；本机保存的是登录换来的令牌。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextHint
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        EditorInputBox(
                            value = state.inputUsername,
                            onValueChange = viewModel::updateUsername,
                            placeholder = "账号"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        EditorInputBox(
                            value = state.inputPassword,
                            onValueChange = viewModel::updatePassword,
                            placeholder = "密码",
                            // 打码显示，避免旁人一眼看到密码。
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    GradientButton(
                        text = if (state.busy) "正在登录…" else "登录",
                        enabled = !state.busy && state.baseUrl.isNotEmpty(),
                        onClick = viewModel::login
                    )

                    if (state.baseUrl.isEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        NoticeLine(text = "这个安装包没有配置同步服务器地址，无法登录", isError = true)
                    }
                }

                state.message?.let { message ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = OrangeStart
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                        }
                        NoticeLine(text = message, isError = state.messageIsError)
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))
                EditorSectionLabel(label = "数据去了哪里")
                Spacer(modifier = Modifier.height(8.dp))
                AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                    Text(
                        text = "记录数据存在的同步服务端由自己搭建，照片存在对象存储里，"
                            + "手机每次只向服务端换取几分钟有效的临时地址来传图，"
                            + "存储的密钥不会下发到手机上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
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
                            text = "同步服务端：${state.baseUrl.ifEmpty { "未配置" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextHint
                        )
                    }
                }

                if (showLogoutConfirm) {
                    AppDialog(
                        title = "退出登录？",
                        subtitle = "退出后本机数据不再与其他设备同步，但本机数据与云端数据都不会被删除；"
                            + "下次登录即可继续。",
                        confirmText = "退出",
                        destructiveConfirm = true,
                        onConfirm = {
                            viewModel.logout()
                            showLogoutConfirm = false
                        },
                        onDismissRequest = { showLogoutConfirm = false }
                    )
                }
            }
        }
    }
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

/** 令牌到期日：只到天，精确到分秒对用户没有意义。 */
private fun formatDate(timestamp: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
