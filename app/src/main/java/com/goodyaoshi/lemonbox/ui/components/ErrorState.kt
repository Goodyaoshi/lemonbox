package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TagRed
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

/**
 * 通用错误状态卡片：图标 + 标题 + 说明 + 「重试」，与 [EmptyState] 成对使用（P2）。
 *
 * 存在的理由：改造前，「读取出错」和「真的为空」在界面上长得一模一样（都是一句空态文案），
 * 她没法分辨「是没数据」还是「App 坏了」，也不知道下一步该做什么。错误态用危险色 + 明确的
 * 「重试」按钮把这两种情况分开，并且永远给出一个出口。
 *
 * [onRetry] 传 null 时退化为纯文案（与 [EmptyState] 的按钮策略一致，避免空壳按钮）。
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.CloudOff,
    title: String = "没能加载出来",
    actionLabel: String = "重试",
    actionIcon: ImageVector = Icons.Default.Refresh,
    onRetry: (() -> Unit)? = null
) {
    AppSurfaceCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(TagRed, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = StatusExpired
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(16.dp))
                // 复用空态的按钮版式，只把底色换成危险色，保证「空/错」两种状态的主操作长得一样。
                StateActionPill(
                    label = actionLabel,
                    icon = actionIcon,
                    containerColor = StatusExpired,
                    onClick = onRetry
                )
            }
        }
    }
}
