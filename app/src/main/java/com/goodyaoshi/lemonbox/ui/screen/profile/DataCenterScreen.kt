package com.goodyaoshi.lemonbox.ui.screen.profile

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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.SectionTitle
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary

/**
 * 数据中心（IA-1）：设备同步与云端同步的统一二级页。
 * 从「我的」拆出，让个人设置与数据管理各归其位。
 *
 * IA 合并：原先「云端账号」与「云存储与同步」是两个入口，其实是同一件事的两半
 * （先有账号，才有图片上云与多设备对齐），现合并为「云端同步」一个入口，
 * 登录态与开关都收在那一页里，不再让人来回找。
 */
@Composable
fun DataCenterScreen(
    onBack: () -> Unit,
    onOpenLanSync: () -> Unit,
    // 云端同步入口（P4 · IA 合并）：登录云账号后图片上云、多设备数据自动对齐，同一页里完成。
    onOpenImageCloud: () -> Unit = {}
) {
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
                    title = "云端同步",
                    // 一个入口覆盖「登录账号 + 图片上云 + 多设备对齐」，仅正式版可用。
                    subtitle = "登录云账号后图片上云、多设备数据自动对齐，仅正式版可用",
                    onClick = onOpenImageCloud
                )
            }
        }
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
