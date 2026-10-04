package com.goodyaoshi.lemonbox.ui.screen.sync

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import com.goodyaoshi.lemonbox.data.backup.toUserMessage
import com.goodyaoshi.lemonbox.data.sync.SyncServer
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.LanSyncMode
import com.goodyaoshi.lemonbox.ui.viewmodel.LanSyncViewModel
import com.goodyaoshi.lemonbox.util.DateUtil

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
                    text = "局域网同步",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Text(
                text = "两台设备连同一个 WiFi，用 6 位配对码即可互相补齐数据。仅在局域网内传输，不经过任何服务器。",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )

            Text(
                text = "上次同步：${DateUtil.relativeSyncText(lastSyncAt)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = OrangeStart,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )

            // 一、发起共享
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                Text(
                    text = "一、让对方连我",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                if (uiState.mode == LanSyncMode.HOSTING) {
                    Text(
                        text = "配对码",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = uiState.pairingCode,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = OrangeStart,
                        letterSpacing = 6.sp
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = "对方地址：${uiState.displayAddress ?: "获取中…"}",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = "对方在「连接对方」里填入上面的地址和配对码即可。",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    incomingSummary?.let { summary ->
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "对方数据：${summary.toUserMessage()}",
                            fontSize = 12.sp,
                            color = OrangeStart
                        )
                    }
                    Spacer(modifier = Modifier.size(12.dp))
                    GradientButton(text = "停止共享", onClick = viewModel::stopHosting)
                } else {
                    Text(
                        text = "开启后会显示配对码与地址，等待对方连接；期间请保持本页在前台。",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    GradientButton(text = "发起共享", onClick = viewModel::startHosting)
                }
            }

            // 二、连接对方
            AppSurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                Text(
                    text = "二、连接对方",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "输入对方显示的地址与配对码。会自动合并双方数据，不会覆盖任何一方的新增。",
                    fontSize = 12.sp,
                    color = TextSecondary
                )

                if (peers.isNotEmpty()) {
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(text = "发现同一 WiFi 下的设备", fontSize = 12.sp, color = TextSecondary)
                    peers.forEach { peer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .clickable { addressInput = "${peer.host}:${peer.port}" },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = peer.name, fontSize = 13.sp, color = TextPrimary)
                            Text(
                                text = "${peer.host}:${peer.port}",
                                fontSize = 12.sp,
                                color = OrangeStart
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.size(10.dp))
                OutlinedTextField(
                    value = addressInput,
                    onValueChange = { addressInput = it },
                    label = { Text("对方地址，例如 192.168.1.5 或 192.168.1.5:${SyncServer.DEFAULT_PORT}") },
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
                Spacer(modifier = Modifier.size(12.dp))
                GradientButton(
                    text = if (uiState.isBusy) "同步中…" else "开始同步",
                    enabled = !uiState.isBusy,
                    onClick = { viewModel.connect(addressInput, codeInput) }
                )
            }

            uiState.message?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .clickable { viewModel.consumeMessage() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        color = TextPrimary,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
            }

            Text(
                text = "提示：同步需要「存储/网络」相关权限；若长时间发现不到设备，请确认路由器未开启「AP 隔离」，或直接手动输入地址。",
                fontSize = 11.sp,
                color = OrangeStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp)
                    .padding(12.dp)
            )
        }
    }
}