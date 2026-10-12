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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OnLemonSoft
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
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
    onOpenOcr: () -> Unit = {},
    onOpenRestockForecast: () -> Unit = {},
    onOpenTimeCapsule: () -> Unit = {},
    onOpenPackingList: () -> Unit = {},
    onOpenSleep: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val dueReminderCount by viewModel.dueReminderCount.collectAsState()
    val anniversaryCount by viewModel.anniversaryCount.collectAsState()
    val backupState by viewModel.backupState.collectAsState()
    val nickname by viewModel.nickname.collectAsState()
    var showBackupDialog by remember { mutableStateOf(false) }
    var showNicknameDialog by remember { mutableStateOf(false) }
    var showAppLockDialog by remember { mutableStateOf(false) }
    var nicknameInput by remember { mutableStateOf("") }
    val appLockEnabled by viewModel.appLockEnabled.collectAsState()

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

    val readableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        uri?.let(viewModel::exportReadable)
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
                    .clickable {
                        nicknameInput = nickname
                        showNicknameDialog = true
                    }
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
                                text = nickname.take(1),
                                color = OnLemon,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = nickname,
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
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "修改称呼",
                            tint = OnLemonSoft,
                            modifier = Modifier.size(18.dp)
                        )
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
                title = "小工具",
                modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp)
            )
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                MenuRow(
                    icon = Icons.Default.DocumentScanner,
                    title = "文字识别",
                    subtitle = "拍张照，自动认出发票、清单或物品名",
                    onClick = onOpenOcr
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.AutoGraph,
                    title = "补货预测",
                    subtitle = "按平时的消耗速度，算算哪些快用完了",
                    onClick = onOpenRestockForecast
                )
                DividerSpacer()
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
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.Description,
                    title = "导出可读报告",
                    subtitle = "生成可阅读、可打印的清单（家当 / 临期 / 收支 / 纪念日）",
                    onClick = {
                        readableLauncher.launch("lemonbox-report-${System.currentTimeMillis()}.html")
                    }
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.Refresh,
                    title = "恢复内置分类",
                    subtitle = "补齐食材/零食等内置分类，误删或同步后丢失也能找回",
                    onClick = viewModel::restoreBuiltInData
                )
                DividerSpacer()
                MenuRow(
                    icon = Icons.Default.Lock,
                    title = "应用锁",
                    subtitle = if (appLockEnabled) {
                        "已开启，进入需要 PIN 或系统密码 / 指纹"
                    } else {
                        "给私人内容加一道锁（PIN + 系统密码 / 指纹）"
                    },
                    onClick = { showAppLockDialog = true }
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
                fontSize = 13.sp
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

    if (showAppLockDialog) {
        AppLockSettingsDialog(
            enabled = appLockEnabled,
            onDismiss = { showAppLockDialog = false },
            onEnable = { pin ->
                viewModel.enableAppLock(pin)
                showAppLockDialog = false
            },
            verifyPin = viewModel::verifyAppLockPin,
            onDisable = {
                viewModel.disableAppLock()
                showAppLockDialog = false
            }
        )
    }

    if (backupState.isBusy) {
        // 处理中：给一个不可取消的等待弹窗，明确告诉她在导出还是导入，并提示别退出。
        AppDialog(
            title = backupState.busyLabel ?: "处理中…",
            onDismissRequest = {},
            dismissText = null,
            confirmText = null
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = OrangeStart
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = "大概几秒钟，先别退出应用",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        }
    }

    if (showBackupDialog && backupState.message != null) {
        // 结果分级：成功用「搞定了」的轻松口吻，失败保留原因并说明可重试，别让她以为数据坏了。
        AppDialog(
            title = if (backupState.isError) "没成功" else "搞定了",
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
            if (backupState.isError) {
                Text(
                    text = "你的数据没有改动，可以再试一次。",
                    color = TextHint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
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

/** 应用锁设置弹窗（F19）：未开启时设 PIN 开启；已开启时输 PIN 关闭。 */
@Composable
private fun AppLockSettingsDialog(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onEnable: (String) -> Unit,
    verifyPin: (String) -> Boolean,
    onDisable: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    if (enabled) {
        AppDialog(
            title = "关闭应用锁",
            onDismissRequest = onDismiss,
            confirmText = "关闭",
            destructiveConfirm = true,
            confirmEnabled = pin.length >= MIN_PIN_LENGTH,
            onConfirm = {
                if (verifyPin(pin)) onDisable() else error = true
            }
        ) {
            Text(
                text = "输入当前 PIN 以关闭应用锁。",
                color = TextSecondary,
                fontSize = 13.sp
            )
            PinField(value = pin, onValueChange = { pin = it; error = false }, isError = error)
        }
    } else {
        AppDialog(
            title = "开启应用锁",
            onDismissRequest = onDismiss,
            confirmText = "开启",
            confirmEnabled = pin.length >= MIN_PIN_LENGTH,
            onConfirm = { onEnable(pin) }
        ) {
            Text(
                text = "设一个 4-12 位数字 PIN。之后进入 App 需要验证，也可以用系统密码 / 指纹。",
                color = TextSecondary,
                fontSize = 13.sp
            )
            PinField(value = pin, onValueChange = { pin = it }, isError = false)
        }
    }
}

@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.filter { ch -> ch.isDigit() }.take(MAX_PIN_LENGTH)) },
            singleLine = true,
            label = { Text("PIN") },
            isError = isError,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        if (isError) {
            Text(
                text = "PIN 不对，再试一次",
                color = StatusExpired,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 12
