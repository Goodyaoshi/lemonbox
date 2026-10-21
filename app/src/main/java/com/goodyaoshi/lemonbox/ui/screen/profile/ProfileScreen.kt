package com.goodyaoshi.lemonbox.ui.screen.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SectionTitle
import com.goodyaoshi.lemonbox.ui.components.rememberBottomBarClearance
import com.goodyaoshi.lemonbox.ui.components.rememberBottomBarPanelClearance
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarm
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.ProfileCloudSummary
import com.goodyaoshi.lemonbox.ui.viewmodel.ProfileViewModel
import com.goodyaoshi.lemonbox.util.BrandCopy
import com.goodyaoshi.lemonbox.util.DateUtil

@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit,
    onOpenDataCenter: () -> Unit = {},
    onOpenReminders: () -> Unit = {},
    onOpenAnniversaries: () -> Unit = {},
    onOpenTimeCapsule: () -> Unit = {},
    onOpenPackingList: () -> Unit = {},
    onOpenSleep: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val dueReminderCount by viewModel.dueReminderCount.collectAsState()
    val anniversaryCount by viewModel.anniversaryCount.collectAsState()
    val cloudSummary by viewModel.cloudSummary.collectAsState()
    val nickname by viewModel.nickname.collectAsState()
    var showNicknameDialog by remember { mutableStateOf(false) }
    var nicknameInput by remember { mutableStateOf("") }

    Box {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = rememberBottomBarPanelClearance())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    // 圆角走形状令牌（F6）：卡片统一收敛到 extraLarge（24dp）。
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(SurfaceWarm)
                    .border(1.dp, DividerSoft, MaterialTheme.shapes.extraLarge)
                    .clickable {
                        nicknameInput = nickname
                        showNicknameDialog = true
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 柠檬黄只留在头像这一小块：它仍是品牌色，但不再铺满整张个人卡。
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(colors = listOf(LemonStart, LemonEnd))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = nickname.take(1),
                            color = OnLemon,
                            // 字号走主题字阶（F6）：头像首字与昵称同为 headlineMedium。
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = nickname,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "你的小日子，交给我照顾",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "修改称呼",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            SectionTitle(
                title = "提醒与纪念日",
                modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 8.dp)
            )
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp),
                shape = MaterialTheme.shapes.extraLarge,
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
                title = "小工具",
                modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp)
            )
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp),
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 12.dp
            ) {
                // IA-6：文字识别是「存物 / 记账」流程里的前置能力，入口已在 Save 与记一笔页，
                // 不再作为独立工具混在小工具区，让这里只留纯独立型功能。
                MenuRow(
                    icon = Icons.Default.MailOutline,
                    title = "时间胶囊",
                    subtitle = "写段话给未来的自己，到那天才能打开",
                    onClick = onOpenTimeCapsule
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.Luggage,
                    title = "打包清单",
                    subtitle = "出行前列好要带的东西，出发前照着勾",
                    onClick = onOpenPackingList
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.NightsStay,
                    title = "睡前氛围",
                    subtitle = "夜里的一处安静角落，还有就寝提醒",
                    onClick = onOpenSleep
                )
            }

            SectionTitle(
                title = "设置与数据",
                modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp)
            )
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp),
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 12.dp
            ) {
                MenuRow(
                    icon = Icons.Default.Settings,
                    title = "设置",
                    subtitle = "外观、提醒时间与通知可靠性",
                    onClick = onOpenSettings
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.Storage,
                    title = "数据与同步",
                    subtitle = dataCenterSubtitle(cloudSummary),
                    onClick = onOpenDataCenter
                )
            }

            Text(
                text = "${BrandCopy.APP_NAME} · 为你而做",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                textAlign = TextAlign.Center
            )
        }
    }

    if (showNicknameDialog) {
        // 「改个称呼」：不动其他设置，改完首页问候、通知、这里都跟着变。
        AppDialog(
            title = "改个称呼",
            onDismissRequest = { showNicknameDialog = false },
            confirmText = "保存",
            onConfirm = {
                viewModel.setNickname(nicknameInput)
                showNicknameDialog = false
            }
        ) {
            Text(
                text = "这个称呼会用在首页问候、通知和这里。想叫什么就改什么。",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = nicknameInput,
                onValueChange = { nicknameInput = it.take(AppPreferences.MAX_NICKNAME_LENGTH) },
                singleLine = true,
                label = { Text("称呼") },
                modifier = Modifier.fillMaxWidth()
            )
        }
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
            // 圆角走形状令牌（F6）：行选中高亮收敛到 medium（16dp）。
            .clip(MaterialTheme.shapes.medium)
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
                // 字号走主题字阶（F6）：行标题收敛到 titleMedium。
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

/** 「数据与同步」入口副标题：让用户在点进去之前就知道同步是否在正常工作。 */
private fun dataCenterSubtitle(summary: ProfileCloudSummary): String = when {
    !summary.loggedIn -> "未登录 · 数据只在本机"
    summary.syncing -> "正在同步…"
    summary.hasError -> "上次同步失败，点此查看"
    else -> "云同步已开启 · " + DateUtil.relativeSyncText(summary.lastSyncedAt)
}
