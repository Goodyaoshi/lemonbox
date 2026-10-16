package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.ScrimSoft
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

/**
 * 应用锁门禁（F19）：覆盖在内容之上，输入 PIN 或走系统密码 / 指纹验证后才放行。
 * 只做展示与输入，验证成功后回调 [onUnlocked]；设备凭据验证由宿主 Activity 负责。
 */
@Composable
fun AppLockOverlay(
    pinHash: String,
    credentialAvailable: Boolean,
    onUnlocked: () -> Unit,
    onUseDeviceCredential: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    fun tryUnlock() {
        if (AppPreferences.hashPin(pin) == pinHash) {
            error = false
            onUnlocked()
        } else {
            error = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ScrimSoft),
        contentAlignment = Alignment.Center
    ) {
        AppSurfaceCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            // 圆角走形状令牌（F6）：与全 App 卡片统一到 extraLarge（24dp）。
            shape = MaterialTheme.shapes.extraLarge,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(OrangeTint),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = OrangeStart,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Text(
                    text = "已锁定",
                    // 字号走主题字阶（F6）：titleLarge 为 18sp。
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = "输入 PIN 继续，或用系统密码 / 指纹验证。",
                    // 字号走主题字阶（F6）：labelLarge 为 14sp。
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )

                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter { ch -> ch.isDigit() }.take(MAX_PIN_LENGTH)
                        error = false
                    },
                    singleLine = true,
                    label = { Text("PIN") },
                    isError = error,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                )
                if (error) {
                    Text(
                        text = "PIN 不对，再试一次",
                        // 字号走主题字阶（F6）：labelMedium 为 12sp。
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusExpired,
                        // 读屏（F18）：错误提示标记为活动区域，TalkBack 会主动播报而不用焦点扫到它。
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp)
                        // 圆角走形状令牌（F6）。
                        .clip(MaterialTheme.shapes.medium)
                        .background(OrangeStart.copy(alpha = if (pin.isBlank()) 0.35f else 0.12f))
                        // 触达面积达标（F7）：先撑出 48dp 最小可点区再绘制按钮，字号放大后不失效。
                        .minimumInteractiveComponentSize()
                        .clickable(enabled = pin.isNotBlank()) { tryUnlock() }
                        // 读屏（F18）：这个可点卡片没有语义控件包裹，显式声明为按钮角色。
                        .semantics { role = Role.Button }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "解锁",
                        // 字号走主题字阶（F6）：labelLarge 为 14sp。
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = OrangeStart
                    )
                }

                if (credentialAvailable) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            // 圆角走形状令牌（F6）。
                            .clip(MaterialTheme.shapes.medium)
                            // 触达面积达标（F7）：撑出 48dp 最小可点区。
                            .minimumInteractiveComponentSize()
                            .clickable(onClick = onUseDeviceCredential)
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "用系统密码 / 指纹验证",
                            // 字号走主题字阶（F6）：labelLarge 为 14sp。
                            style = MaterialTheme.typography.labelLarge,
                            color = TextHint
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_PIN_LENGTH = 12
