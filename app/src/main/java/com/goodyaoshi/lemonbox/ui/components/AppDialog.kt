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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    AppModalPanel(onDismissRequest = onDismissRequest, modifier = modifier) {
        // 标题与内容一起滚动，按钮留在滚动区之外固定可见。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = title,
                // 字号走主题字阶（F6）：headlineMedium 为 22sp，与全局标题语言统一。
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                modifier = Modifier.semantics { heading() }
            )
            subtitle?.let {
                Text(
                    text = it,
                    // 字号走主题字阶（F6）：bodySmall 为 12sp（F7 下限）。
                    style = MaterialTheme.typography.bodySmall,
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

/**
 * 全应用统一的模态面板外壳（P1）：遮罩 + 居中卡片 + 限高 + 统一圆角 / 内距 / 投影。
 *
 * 必要性：此前状态选择、物品「更多」、树形选择器等弹窗各自复刻了同一套骨架——
 * 遮罩色、卡片限高、「圆角 30dp」「内距 22dp」「投影 26dp」这些数值抄了四份，
 * 于是想统一调一次圆角要改四个文件，改漏一处就出现两种模态样式。
 * 现在收成一处，[AppDialog] 与各专用弹窗都走这里。
 *
 * [onDismissRequest] 交给外层 [Dialog]（点遮罩 / 返回键）。
 * 内容用 [ColumnScope]，弹窗自行决定哪些部分滚动、哪些固定（通常是操作按钮固定）。
 */
@Composable
fun AppModalPanel(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(22.dp),
    maxWidth: Dp = 420.dp,
    content: @Composable ColumnScope.() -> Unit
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
            // 卡片限高（上下各留 24dp）。必要性：标题与按钮都在滚动区之外，
            // 简易模式（系统字体放大）下卡片一旦超过屏幕高度，底部按钮会被挤出可视区且无法滚到。
            val maxCardHeight = maxHeight - 48.dp
            AppSurfaceCard(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .widthIn(max = maxWidth)
                    .heightIn(max = maxCardHeight),
                // 圆角走形状令牌（F6）：全部模态卡片统一到 extraLarge（24dp）。
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding = contentPadding,
                shadowElevation = 26.dp,
                floating = true,
                content = content
            )
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
            // 圆角走形状令牌（F6）。
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            // 触达面积达标（F7）：先撑出 48dp 最小可点区再绘制按钮，字号放大后不失效。
            .minimumInteractiveComponentSize()
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
            // 字号走主题字阶（F6）：labelLarge 为 14sp。
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}
