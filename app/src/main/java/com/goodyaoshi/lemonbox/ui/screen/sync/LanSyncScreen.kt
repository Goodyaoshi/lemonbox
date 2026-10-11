package com.goodyaoshi.lemonbox.ui.screen.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.backup.SyncScope
import com.goodyaoshi.lemonbox.data.backup.toUserMessage
import com.goodyaoshi.lemonbox.data.sync.SyncPeer
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.StatusNormal
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.HostPairPrompt
import com.goodyaoshi.lemonbox.ui.viewmodel.LanSyncMode
import com.goodyaoshi.lemonbox.ui.viewmodel.LanSyncViewModel
import com.goodyaoshi.lemonbox.ui.viewmodel.MatchCodePrompt
import com.goodyaoshi.lemonbox.ui.viewmodel.ScopePrompt
import com.goodyaoshi.lemonbox.ui.viewmodel.SyncStepStatus
import com.goodyaoshi.lemonbox.ui.viewmodel.SyncStepUi
import com.goodyaoshi.lemonbox.ui.viewmodel.SyncTransferUi
import com.goodyaoshi.lemonbox.util.DateUtil

/**
 * 局域网同步：做成「像配对蓝牙一样」的界面 —— 顶部是本机（被连接方），
 * 下面是附近设备列表（连接方点一下就连），手动输入地址与配对码折叠在最后兜底。
 */
@Composable
fun LanSyncScreen(
    onBack: () -> Unit,
    viewModel: LanSyncViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val incomingSummary by viewModel.incomingSummary.collectAsState()
    val lastSyncAt by viewModel.lastSyncAt.collectAsState()

    var addressInput by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }

    // 作为被连接方：对方合并过来时提示一次，避免「对方连了但本机毫无反应」。
    // 全局提示通道（I10）：合并结果改走统一 Snackbar。
    val appSnackbar = LocalAppSnackbar.current
    var shownSummary by remember { mutableStateOf(incomingSummary) }
    LaunchedEffect(incomingSummary) {
        val summary = incomingSummary
        if (summary != null && summary != shownSummary) {
            shownSummary = summary
            appSnackbar?.showMessage("已合并对方数据：${summary.toUserMessage()}")
        }
    }

    val hosting = uiState.mode == LanSyncMode.HOSTING

    // 同步（含配对/等待）期间保持屏幕常亮：传输依赖前台网络，熄屏会切到省电模式，
    // 轻则连接中断、重则同步异常。离开本页或不再忙碌时自动恢复系统默认行为。
    val view = LocalView.current
    DisposableEffect(uiState.isBusy, hosting) {
        view.keepScreenOn = uiState.isBusy || hosting
        onDispose { view.keepScreenOn = false }
    }

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
                    text = "设备同步",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
            }

            Text(
                text = "两台设备连同一个 WiFi（没有 WiFi 时，也可以用一台手机开「个人热点」，另一台连上它），一方开启共享，另一方在设备列表里点一下就连上，双方核对同一个匹配码后就完成同步。只在局域网内传输，不经过任何服务器。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
            )

            Text(
                text = "上次同步：${DateUtil.relativeSyncText(lastSyncAt)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = OrangeStart,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
            )

            // 一、本机（被连接方）
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(
                        icon = if (hosting) {
                            Icons.Default.BluetoothConnected
                        } else {
                            Icons.Default.Smartphone
                        },
                        active = hosting
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "本机 · ${viewModel.localDeviceModel}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = when {
                                uiState.hostScopePrompt != null -> "对方数据已收到，请选择同步范围"
                                uiState.hostAccepted -> "已接受，等待对方确认并传输…"
                                hosting -> "正在等待对方连接…"
                                else -> "开启后，这台设备会出现在对方的设备列表里"
                            },
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    if (hosting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = OrangeStart
                        )
                    }
                }

                if (hosting) {
                    Spacer(modifier = Modifier.size(14.dp))
                    Text(
                        text = "配对码",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    AutoSizePairingCode(code = uiState.pairingCode)
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = "配对码相当于临时的共享暗号：对方连接后，两边屏幕会弹出同一个码，核对一致、双方都点确认才继续同步（出于安全考虑，配对码不会自动广播）。",
                        fontSize = 12.sp,
                        color = TextHint
                    )
                    Text(
                        text = "地址：${uiState.displayAddress ?: "获取中…"}（发现不到设备时可手动输入）",
                        // 说明文字走主题字阶（F6）并提到 12sp（F7）。
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    incomingSummary?.let { summary ->
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "刚合并对方数据：${summary.toUserMessage()}",
                            fontSize = 12.sp,
                            color = OrangeStart
                        )
                    }
                    Spacer(modifier = Modifier.size(14.dp))
                    GradientButton(text = "停止共享", onClick = viewModel::stopHosting)
                } else {
                    Spacer(modifier = Modifier.size(14.dp))
                    GradientButton(text = "开启共享", onClick = viewModel::startHosting)
                }
            }

            // 一·B、同步进度：把原来只显示「同步中…」的黑盒细化成一步步的进度，
            // 同步过程中屏幕保持常亮（见上方 keepScreenOn），避免熄屏打断传输。
            if (uiState.syncSteps.isNotEmpty()) {
                AppSurfaceCard(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = 12.dp
                ) {
                    Text(
                        text = if (uiState.isBusy) "同步进度" else "上次同步进度",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    uiState.syncSteps.forEach { step -> SyncStepRow(step) }
                    uiState.transfer?.let { transfer ->
                        Spacer(modifier = Modifier.size(10.dp))
                        SyncTransferRow(transfer)
                    }
                }
            }

            // 二、附近设备（连接方）
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "附近设备",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // I9：原来「刷新」文字与旁边的刷新图标是两个独立元素，只有文字可点、
                    // 图标又没有描述，热区既不完整也不明确。这里合并成唯一一个热区，
                    // 图标 + 文字整体可点，并声明 Button 角色，读屏播报一次「刷新」。
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(role = Role.Button) { viewModel.refreshDiscovery() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = OrangeStart,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "刷新",
                            fontSize = 12.sp,
                            color = OrangeStart
                        )
                    }
                }

                if (peers.isEmpty()) {
                    Spacer(modifier = Modifier.size(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                            contentDescription = null,
                            tint = TextHint,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "正在搜索同一 WiFi 下的设备…",
                            fontSize = 13.sp,
                            color = TextHint
                        )
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = "让对方也打开这个页面并「开启共享」；一直不出现就点下面的「扫描整个局域网」。",
                        // 说明文字走主题字阶（F6）并提到 12sp（F7）。
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint
                    )
                } else {
                    Spacer(modifier = Modifier.size(6.dp))
                    peers.forEach { peer ->
                        DeviceRow(
                            peer = peer,
                            enabled = !uiState.isBusy,
                            onClick = { viewModel.connectToPeer(peer) }
                        )
                    }
                }

                Spacer(modifier = Modifier.size(10.dp))
                // 兜底发现：mDNS 依赖组播，路由器开 AP 隔离或手机开个人热点时会失效，
                // 这个按钮改成扫描整个网段，不依赖任何发现协议，只要同网段就找得到。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                        .background(OrangeStart.copy(alpha = 0.14f))
                        .clickable(enabled = !scanning && !uiState.isBusy, role = Role.Button) {
                            viewModel.scanLan()
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = OrangeStart
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Wifi,
                            contentDescription = null,
                            tint = OrangeStart,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (scanning) "扫描中… $scanProgress%" else "扫描整个局域网",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OrangeStart
                    )
                }
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = "搜不到对方时点它：直接扫描同网段，不依赖组播，路由器开了 AP 隔离或走个人热点也能找到。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
            }

            // 三、手动连接（兜底，默认折叠）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showManual = !showManual }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "搜索不到？手动输入地址",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (showManual) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = showManual) {
                AppSurfaceCard(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = 12.dp
                ) {
                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        label = { Text("对方地址，例如 192.168.1.5") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "地址可在对方「本机」卡片里看到；连上后两边会弹出匹配码，核对一致再继续。",
                        // 说明文字走主题字阶（F6）并提到 12sp（F7）。
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    GradientButton(
                        text = if (uiState.isBusy) "同步中…" else "开始同步",
                        enabled = !uiState.isBusy,
                        onClick = { viewModel.connect(addressInput) }
                    )
                }
            }

            uiState.message?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(OrangeTint)
                        .clickable { viewModel.consumeMessage() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        color = TextPrimary,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .weight(1f)
                            .padding(12.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭提示",
                        tint = TextHint,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(16.dp)
                    )
                }
            }

            Text(
                text = "提示：同步要用到「存储/网络」权限；要是很久都找不到设备，先点「扫描整个局域网」——它不依赖组播，AP 隔离或热点下同样有效；再不行就用「手动输入」兜底。",
                // 说明文字走主题字阶（F6）并提到 12sp（F7）。
                style = MaterialTheme.typography.bodySmall,
                color = OrangeStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp, vertical = 4.dp)
            )
        }

        // 配对/范围对话框统一浮在内容之上：发起方核对匹配码 → 选范围；
        // 被连接方确认配对 → 选范围。双方都确认匹配码一致才会真正传输。
        uiState.matchCodePrompt?.let { prompt ->
            MatchCodeDialog(
                prompt = prompt,
                onConfirm = { viewModel.confirmMatch(true) },
                onCancel = { viewModel.confirmMatch(false) }
            )
        }
        uiState.hostPairPrompt?.let { prompt ->
            HostPairDialog(
                prompt = prompt,
                onAccept = viewModel::acceptHostPair,
                onReject = viewModel::rejectHostPair
            )
        }
        uiState.clientScopePrompt?.let { prompt ->
            ScopeChoiceDialog(
                prompt = prompt,
                onChoose = { viewModel.chooseScope(it) },
                onDismiss = { viewModel.chooseScope(null) }
            )
        }
        uiState.hostScopePrompt?.let { prompt ->
            ScopeChoiceDialog(
                prompt = prompt,
                onChoose = viewModel::applyHostScope,
                onDismiss = null
            )
        }
    }
}

/**
 * 发起方核对匹配码：把本端生成的码显示出来，让对方屏幕上的数字与之对照。
 * 只有双方都确认一致，配对才会继续，避免连到陌生设备。
 */
@Composable
private fun MatchCodeDialog(
    prompt: MatchCodePrompt,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = "核对匹配码", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "请和「${prompt.peerLabel}」屏幕上的数字对照，确认是同一个再继续：",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    text = prompt.matchCode,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                    color = OrangeStart,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "数字一致说明连的就是对面那台设备；不一致就不要继续，避免同步到陌生设备。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "一致，继续")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = "不一致", color = TextSecondary)
            }
        }
    )
}

/**
 * 被连接方确认配对：对方是谁、匹配码是多少，核对后才接受，
 * 避免开着共享就被不认识的人直接同步走数据。
 */
@Composable
private fun HostPairDialog(
    prompt: HostPairPrompt,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(text = "有设备请求同步", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "「${prompt.deviceModel.ifBlank { prompt.peerLabel }}」想和你同步数据。",
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    text = "对方屏幕上的匹配码应该是：",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = prompt.matchCode,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                    color = OrangeStart,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "核对无误再点「接受配对」；不确定就拒绝。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(text = "接受配对")
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text(text = "拒绝", color = TextSecondary)
            }
        }
    )
}

/**
 * 选择同步范围：先展示「新增 / 修改 / 删除」数量，再让用户选全部同步或只同步一部分。
 * 主客两侧共用；被连接方必选（onDismiss=null），发起方取消即中止同步。
 */
@Composable
private fun ScopeChoiceDialog(
    prompt: ScopePrompt,
    onChoose: (SyncScope) -> Unit,
    onDismiss: (() -> Unit)?
) {
    val preview = prompt.preview
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        title = { Text(text = "选择同步范围", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "与「${prompt.peerLabel}」的数据对比：",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "新增 ${preview.added} 项 · 修改 ${preview.updated} 项 · 删除 ${preview.deleted} 项",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = "可以全部同步，也可以只同步其中一部分（比如只接收新增，不删除本机已有的）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
                Spacer(modifier = Modifier.size(12.dp))
                SyncScope.entries.forEach { scope ->
                    TextButton(
                        onClick = { onChoose(scope) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = scope.label, color = OrangeStart)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = if (onDismiss != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text(text = "取消", color = TextSecondary)
                }
            }
        } else {
            null
        }
    )
}

/**
 * 配对码展示：6 位数字字号很大，窄屏（小屏或系统大字体）容易溢出。
 * 这里量一次文字宽度，按可用宽度自适应缩小字号，保证整串数字始终完整显示在一行。
 */
@Composable
private fun AutoSizePairingCode(code: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val availablePx = constraints.maxWidth.toFloat()
        val fontSize = remember(code, availablePx) {
            var size = MAX_PAIRING_CODE_SP
            while (size > MIN_PAIRING_CODE_SP) {
                val layout = measurer.measure(
                    text = AnnotatedString(code),
                    style = TextStyle(
                        fontSize = size.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (size / 5f).sp
                    )
                )
                if (layout.size.width <= availablePx) break
                size -= 1f
            }
            size
        }
        Text(
            text = code,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            color = OrangeStart,
            letterSpacing = (fontSize / 5f).sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 配对码字号上限：常规屏显 38sp，与旧版观感一致。 */
private const val MAX_PAIRING_CODE_SP = 38f

/** 配对码字号下限：再小也保证可读，仅极窄屏才会触底。 */
private const val MIN_PAIRING_CODE_SP = 20f

/** 附近设备的一行：蓝牙式的圆底图标 + 设备名 + 右侧操作提示。点击后进入配对确认。 */
@Composable
private fun DeviceRow(
    peer: SyncPeer,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceWarmDeep)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(CardWhite),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                tint = OrangeStart,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peer.model ?: peer.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = peer.host,
                fontSize = 11.sp,
                color = TextHint
            )
        }
        Text(
            text = if (enabled) "点击同步" else "同步中…",
            fontSize = 12.sp,
            color = if (enabled) OrangeStart else TextHint
        )
    }
}

/** 圆形图标底：开启状态用品牌色，未开启用暖底。 */
@Composable
private fun IconBubble(
    icon: ImageVector,
    active: Boolean
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) OrangeStart else SurfaceWarmDeep),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) CardWhite else OrangeStart,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** 同步进度的一行：左侧状态指示（转圈/对勾/叉/待开始），右侧步骤名。 */
@Composable
private fun SyncStepRow(step: SyncStepUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (step.status) {
            SyncStepStatus.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = OrangeStart
            )

            SyncStepStatus.DONE -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = StatusNormal,
                modifier = Modifier.size(16.dp)
            )

            SyncStepStatus.FAILED -> Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = StatusExpired,
                modifier = Modifier.size(16.dp)
            )

            SyncStepStatus.PENDING -> Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(SurfaceWarmDeep)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = when (step.status) {
                SyncStepStatus.RUNNING -> "${step.label}…"
                SyncStepStatus.FAILED -> "${step.label}（失败）"
                else -> step.label
            },
            fontSize = 13.sp,
            color = when (step.status) {
                SyncStepStatus.PENDING -> TextHint
                SyncStepStatus.FAILED -> StatusExpired
                else -> TextPrimary
            }
        )
    }
}

/** 传输进度明细：正在传什么，以及进度条 +「已传 / 总量（百分比）」。 */
@Composable
private fun SyncTransferRow(transfer: SyncTransferUi) {
    val percent = transfer.percent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = transfer.label,
                fontSize = 13.sp,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (percent != null) {
                    "${formatTransferSize(transfer.done)} / ${formatTransferSize(transfer.total)}（$percent%）"
                } else {
                    "已传 ${formatTransferSize(transfer.done)}"
                },
                fontSize = 12.sp,
                color = TextHint
            )
        }
        Spacer(modifier = Modifier.size(6.dp))
        if (percent != null) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = OrangeStart,
                trackColor = SurfaceWarmDeep
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = OrangeStart,
                trackColor = SurfaceWarmDeep
            )
        }
    }
}

/** 把传输字节数按 KB/MB 友好展示。 */
private fun formatTransferSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) {
        "%.1f MB".format(bytes / 1024.0 / 1024.0)
    } else {
        "%.0f KB".format(bytes / 1024.0)
    }
