package com.goodyaoshi.lemonbox.ui.screen.sync

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.backup.toUserMessage
import com.goodyaoshi.lemonbox.data.sync.SyncPeer
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.LanSyncMode
import com.goodyaoshi.lemonbox.ui.viewmodel.LanSyncViewModel
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
    val incomingSummary by viewModel.incomingSummary.collectAsState()
    val lastSyncAt by viewModel.lastSyncAt.collectAsState()

    var addressInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }

    // 作为被连接方：对方合并过来时弹一次提示，避免「对方连了但本机毫无反应」。
    val context = LocalContext.current
    var shownSummary by remember { mutableStateOf(incomingSummary) }
    LaunchedEffect(incomingSummary) {
        val summary = incomingSummary
        if (summary != null && summary != shownSummary) {
            shownSummary = summary
            Toast.makeText(context, "已合并对方数据：${summary.toUserMessage()}", Toast.LENGTH_LONG).show()
        }
    }

    val hosting = uiState.mode == LanSyncMode.HOSTING

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
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Text(
                text = "两台设备连同一个 WiFi，一方开启共享，另一方在设备列表里点一下就连上，跟配蓝牙一样简单。只在局域网内传输，不经过任何服务器。",
                fontSize = 12.sp,
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
                            text = "本机",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (hosting) "正在等待对方连接…" else "开启后，这台设备会出现在对方的设备列表里",
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
                    Text(
                        text = uiState.pairingCode,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = OrangeStart,
                        letterSpacing = 8.sp
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = "对方在「附近设备」里点一下本机名字即可，无需手动输码。",
                        fontSize = 12.sp,
                        color = TextHint
                    )
                    Text(
                        text = "地址：${uiState.displayAddress ?: "获取中…"}（发现不到设备时可手动输入）",
                        fontSize = 11.sp,
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
                    Text(
                        text = "刷新",
                        fontSize = 12.sp,
                        color = OrangeStart,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { viewModel.refreshDiscovery() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = OrangeStart,
                        modifier = Modifier.size(14.dp)
                    )
                }

                if (peers.isEmpty()) {
                    Spacer(modifier = Modifier.size(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BluetoothSearching,
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
                        text = "让对方也打开这个页面并「开启共享」，稍等片刻即可出现。",
                        fontSize = 11.sp,
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
                    text = "搜索不到？手动输入地址与配对码",
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
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { input -> codeInput = input.filter { it.isDigit() }.take(6) },
                        label = { Text("6 位配对码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "地址与配对码可在对方「本机」卡片里看到。",
                        fontSize = 11.sp,
                        color = TextHint
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    GradientButton(
                        text = if (uiState.isBusy) "同步中…" else "开始同步",
                        enabled = !uiState.isBusy,
                        onClick = { viewModel.connect(addressInput, codeInput) }
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
                text = "提示：同步需要「存储/网络」相关权限；若长时间发现不到设备，请确认路由器未开启「AP 隔离」，或用上方的「手动输入」兜底。",
                fontSize = 11.sp,
                color = OrangeStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp, vertical = 4.dp)
            )
        }
    }
}

/** 附近设备的一行：蓝牙式的圆底图标 + 设备名 + 右侧操作提示，点击即同步。 */
@Composable
private fun DeviceRow(
    peer: SyncPeer,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val needsCode = peer.token.isNullOrBlank()
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
        Text(
            text = peer.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = when {
                !enabled -> "同步中…"
                needsCode -> "需配对码"
                else -> "点击同步"
            },
            fontSize = 12.sp,
            color = if (needsCode && enabled) TextHint else OrangeStart
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
