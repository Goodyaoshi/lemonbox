package com.goodyaoshi.lemonbox.ui.screen.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorSectionLabel
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.QuantityStepper
import com.goodyaoshi.lemonbox.ui.components.SectionHeader
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SageAccent
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.ScanViewModel
import com.goodyaoshi.lemonbox.util.DateUtil

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanScreen(
    barcode: String,
    onBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToSaveForBarcode: (String) -> Unit,
    viewModel: ScanViewModel = hiltViewModel()
) {
    // 全局提示通道（I10）：入库 / 核销等结果改走统一 Snackbar，替代易被忽略的系统 Toast。
    val appSnackbar = LocalAppSnackbar.current
    val state by viewModel.state.collectAsState()

    var showCheckIn by remember { mutableStateOf(false) }
    var checkInQuantity by remember { mutableIntStateOf(1) }
    var checkInExpiry by remember { mutableStateOf<Long?>(null) }

    val quickExpiryCodes by viewModel.expiryQuickOptions.collectAsState()
    // 「不设置」+ 设置页自定义的快捷档位（x天/x周/x月/x年）。
    val quickExpiryOptions = remember(quickExpiryCodes) {
        listOf<String?>(null) + quickExpiryCodes
    }

    LaunchedEffect(barcode) { viewModel.setBarcode(barcode) }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            appSnackbar?.showMessage(message)
            viewModel.consumeMessage()
        }
    }

    val detail = state.activeItems.firstOrNull() ?: state.historyItems.firstOrNull()
    val unit = state.template?.unit ?: detail?.item?.unit ?: "件"

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "扫码结果",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                AppSurfaceCard(
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
                    shadowElevation = 14.dp
                ) {
                    Text(
                        text = state.productName,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "条码：$barcode",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (detail?.categoryName != null || detail?.locationName != null) {
                        Row(
                            modifier = Modifier.padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            detail?.categoryName?.let { name ->
                                PillTag(
                                    text = name,
                                    backgroundColor = OrangeStart.copy(alpha = 0.12f),
                                    contentColor = OrangeStart
                                )
                            }
                            detail?.locationName?.let { name ->
                                PillTag(
                                    text = name,
                                    backgroundColor = SageAccent.copy(alpha = 0.12f),
                                    contentColor = SageAccent
                                )
                            }
                        }
                    }
                }

                when {
                    state.hasActive -> {
                        SectionHeader(
                            title = "在库批次",
                            subtitle = "共 ${state.activeItems.size} 个批次，按有效期先后排列",
                            modifier = Modifier.padding(top = 22.dp, bottom = 12.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            state.activeItems.forEach { itemDetail ->
                                AppSurfaceCard(
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                                    shadowElevation = 8.dp
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${itemDetail.item.quantity} ${itemDetail.item.unit}",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = itemDetail.item.expireTime?.let { "有效期至 ${DateUtil.formatDate(it)}" }
                                                    ?: "未设置有效期",
                                                fontSize = 12.sp,
                                                color = TextSecondary,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    state.hasHistory -> {
                        AppSurfaceCard(
                            modifier = Modifier.padding(top = 22.dp),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(18.dp),
                            shadowElevation = 8.dp
                        ) {
                            Text(
                                text = "暂无在库批次",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "该物品此前已用完或已丢弃，可再次入库。",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    else -> {
                        AppSurfaceCard(
                            modifier = Modifier.padding(top = 22.dp),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(18.dp),
                            shadowElevation = 8.dp
                        ) {
                            Text(
                                text = "未找到该条码的物品",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "还没有收录过这个条码，去登记第一件吧。",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                if (state.hasActive || state.hasHistory) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SecondaryActionButton(
                            text = "查看",
                            enabled = state.primaryItemId != null,
                            onClick = { state.primaryItemId?.let(onNavigateToDetail) },
                            modifier = Modifier.weight(1f)
                        )
                        SecondaryActionButton(
                            text = "使用",
                            enabled = state.hasActive,
                            onClick = { viewModel.consumeEarliest() },
                            modifier = Modifier.weight(1f)
                        )
                        GradientButton(
                            text = "入库",
                            enabled = state.template != null,
                            onClick = {
                                checkInQuantity = 1
                                checkInExpiry = null
                                showCheckIn = true
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    GradientButton(
                        text = "扫码入库",
                        onClick = { onNavigateToSaveForBarcode(barcode) }
                    )
                }
            }
        }
    }

    if (showCheckIn) {
        AppDialog(
            title = "扫码入库",
            subtitle = "「${state.productName}」本次入库的数量与有效期",
            onDismissRequest = { showCheckIn = false },
            confirmText = "确认入库",
            onConfirm = {
                viewModel.checkIn(checkInQuantity, checkInExpiry)
                showCheckIn = false
            }
        ) {
            EditorSectionLabel("数量")
            QuantityStepper(
                quantity = checkInQuantity,
                unit = unit,
                onDecrease = { if (checkInQuantity > 1) checkInQuantity-- },
                onIncrease = { checkInQuantity++ }
            )

            EditorSectionLabel("有效期")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickExpiryOptions.forEach { code ->
                    val value = code?.let(DateUtil::expiryQuickTimestamp)
                    val selected = checkInExpiry == value
                    PillTag(
                        text = if (code == null) "不设置" else DateUtil.expiryQuickLabel(code),
                        backgroundColor = if (selected) OrangeStart else CardWhite,
                        contentColor = if (selected) Color.White else TextHint,
                        onClick = { checkInExpiry = value }
                    )
                }
            }
            if (checkInExpiry != null) {
                Text(
                    text = "有效期至 ${DateUtil.formatDate(checkInExpiry!!)}",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SecondaryActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) TextPrimary else TextHint,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
