package com.goodyaoshi.lemonbox.ui.screen.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.UserCopy
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OnLemonSoft
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.ProfileViewModel
import com.goodyaoshi.lemonbox.util.BrandCopy

@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit,
    onOpenLanSync: () -> Unit = {},
    onOpenReminders: () -> Unit = {},
    onOpenAnniversaries: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val dueReminderCount by viewModel.dueReminderCount.collectAsState()
    val anniversaryCount by viewModel.anniversaryCount.collectAsState()
    val backupState by viewModel.backupState.collectAsState()
    var showBackupDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let(viewModel::exportBackup)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::importBackup)
    }

    LaunchedEffect(backupState.message) {
        showBackupDialog = backupState.message != null
    }

    Box {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 88.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(LemonStart, LemonEnd)
                        )
                    )
                    .drawBehind {
                        // 装饰圆只画不占位，避免把卡片撑出下半截空白
                        val radius = 74.dp.toPx()
                        val circleCenter = Offset(size.width - radius, radius)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
                                center = circleCenter,
                                radius = radius * 2f
                            ),
                            radius = radius * 2f,
                            center = circleCenter
                        )
                    }
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(OnLemon.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = UserCopy.NICKNAME_INITIAL,
                                color = OnLemon,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.size(12.dp))
                        Column {
                            Text(
                                text = UserCopy.NICKNAME,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = OnLemon
                            )
                            Text(
                                text = "你的小日子，交给我照顾",
                                fontSize = 12.sp,
                                color = OnLemonSoft
                            )
                        }
                    }
                }
            }

            SectionTitle(
                title = "提醒与纪念日",
                modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 8.dp)
            )
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                MenuRow(
                    icon = Icons.Default.Alarm,
                    title = "待办提醒",
                    subtitle = "解冻、备菜、家务等一次性与周期待办",
                    badge = dueReminderCount.takeIf { it > 0 }?.toString(),
                    onClick = onOpenReminders
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.Favorite,
                    title = "纪念日",
                    subtitle = "在一起的累计、生日与倒数的日子",
                    badge = anniversaryCount.takeIf { it > 0 }?.toString(),
                    onClick = onOpenAnniversaries
                )
            }

            SectionTitle(
                title = "设置与数据",
                modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp)
            )
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                MenuRow(
                    icon = Icons.Default.Settings,
                    title = "设置",
                    subtitle = "外观、提醒时间、备菜提醒与提醒可靠性",
                    onClick = onOpenSettings
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.Sync,
                    title = "局域网同步",
                    subtitle = "同一 WiFi 下用配对码互相补齐数据",
                    onClick = onOpenLanSync
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.FileDownload,
                    title = "导出数据",
                    subtitle = "生成 ZIP 备份包",
                    onClick = {
                        exportLauncher.launch("lemonbox-backup-${System.currentTimeMillis()}.zip")
                    }
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.FileUpload,
                    title = "导入数据",
                    subtitle = "与本地数据智能合并，不覆盖",
                    onClick = {
                        importLauncher.launch(arrayOf("application/zip", "*/*"))
                    }
                )
            }

            Text(
                text = "${BrandCopy.APP_NAME} · 为你而做",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                textAlign = TextAlign.Center
            )
        }
    }

    if (showBackupDialog && backupState.message != null) {
        AppDialog(
            title = "提示",
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
                fontSize = 14.sp
            )
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
                .clip(RoundedCornerShape(2.dp))
                .background(LemonEnd)
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = title,
            fontSize = 13.sp,
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
    onClick: () -> Unit,
    badge: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
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
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextHint
            )
        }
        badge?.let {
            PillTag(text = it, backgroundColor = TagOrange, contentColor = TagOrangeText)
            Spacer(modifier = Modifier.size(6.dp))
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
