package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.ScrimSoft
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarm
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

@Composable
fun AppDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissText: String? = "取消",
    destructiveConfirm: Boolean = false,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(ScrimSoft),
            contentAlignment = Alignment.Center
        ) {
            // 卡片限高（上下各留 24dp），超出部分靠内容区滚动。
            // 必要性：标题与按钮在内容之外，若卡片整体超过屏幕高度，
            // 简易模式（系统字体放大）下底部按钮会被挤出可视区且**无法滚到**。
            val maxCardHeight = maxHeight - 48.dp
            AppSurfaceCard(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 420.dp)
                    .heightIn(max = maxCardHeight),
                shape = RoundedCornerShape(30.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(22.dp),
                shadowElevation = 26.dp
            ) {
                // 标题与内容一起滚动，按钮留在滚动区之外固定可见。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            fontSize = 13.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content
                    )
                }

                if (dismissText != null || secondaryText != null || confirmText != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 22.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        dismissText?.let {
                            DialogActionButton(
                                text = it,
                                modifier = Modifier.weight(1f),
                                onClick = onDismissRequest
                            )
                        }
                        secondaryText?.let { text ->
                            DialogActionButton(
                                text = text,
                                modifier = Modifier.weight(1f),
                                highlight = true,
                                onClick = { onSecondary?.invoke() }
                            )
                        }
                        confirmText?.let { text ->
                            DialogActionButton(
                                text = text,
                                modifier = Modifier.weight(1f),
                                highlight = true,
                                destructive = destructiveConfirm,
                                enabled = confirmEnabled,
                                // 纯提示弹窗（如「知道了」「看完了」）不传 onConfirm 时，
                                // 确认即关闭，而不是点了没反应；传了 onConfirm 的行为不变。
                                onClick = { (onConfirm ?: onDismissRequest).invoke() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val background = when {
        !enabled -> SurfaceWarmDeep
        destructive -> StatusExpired.copy(alpha = 0.12f)
        highlight -> OrangeStart.copy(alpha = 0.12f)
        else -> SurfaceWarm
    }
    val contentColor = when {
        !enabled -> TextHint
        destructive -> StatusExpired
        highlight -> OrangeStart
        else -> TextSecondary
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                // 对话框确认 / 取消按钮恢复默认涟漪（I3）：主流程按钮更需要按压确认。
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
