package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TagGreen
import com.goodyaoshi.lemonbox.ui.theme.TagGreenText
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TagRed
import com.goodyaoshi.lemonbox.ui.theme.TagRedText
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

/**
 * 内联提示的语气（variant）：决定配色与图标，避免任何提示都套用「危险红」造成语义错位。
 * 此前只有红色危险态，承载成功 / 中性提示时语义打架；现补三档。
 */
enum class NoticeVariant { Danger, Warning, Success, Info }

/**
 * 页面内联提示条（I10）：承载「失败 / 需注意 / 成功」这类反馈，直接出现在相关界面内。
 *
 * 相比 Toast，内联提示不会被系统提示队列挤掉、也不用打断操作弹模态框，用户在当前上下文中就能
 * 读到结果；配色只取语义色板（随深浅主题自适应），不写字面量颜色。
 *
 * 读屏可达（F8）：[Danger] / [Warning] 这类重要失败设 Assertive 活动区域，出现即自动播报；
 * 其余设 Polite，不打断当前播报。
 *
 * [onDismiss] 非空时右侧出现关闭按钮：对重试后已恢复、或用户已读到的提示，给一条主动收起的通道，
 * 不用等到下次数据刷新才消失。关闭按钮沿用 Material3 [IconButton]（≥48dp 触控热区）。
 */
@Composable
fun InlineNotice(
    message: String,
    modifier: Modifier = Modifier,
    variant: NoticeVariant = NoticeVariant.Danger,
    onDismiss: (() -> Unit)? = null
) {
    val background: Color
    val contentColor: Color
    val icon: ImageVector
    when (variant) {
        NoticeVariant.Danger -> {
            background = TagRed
            contentColor = TagRedText
            icon = Icons.Default.ErrorOutline
        }
        NoticeVariant.Warning -> {
            background = TagOrange
            contentColor = TagOrangeText
            icon = Icons.Default.WarningAmber
        }
        NoticeVariant.Success -> {
            background = TagGreen
            contentColor = TagGreenText
            icon = Icons.Default.CheckCircle
        }
        NoticeVariant.Info -> {
            background = SurfaceWarmDeep
            contentColor = TextSecondary
            icon = Icons.Default.Info
        }
    }
    // 重要失败（危险 / 警告）用 Assertive：出现即打断播报，确保用户不会错过；
    // 成功 / 中性用 Polite，等当前播报结束再播。
    val liveMode = if (variant == NoticeVariant.Danger || variant == NoticeVariant.Warning) {
        LiveRegionMode.Assertive
    } else {
        LiveRegionMode.Polite
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 圆角走形状令牌（F6）：与主题 small 档位对齐。
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .semantics { liveRegion = liveMode }
            .padding(start = 14.dp, end = if (onDismiss != null) 4.dp else 14.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = message,
            color = contentColor,
            // 字号走主题字阶（F6）：labelLarge 为 14sp。
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (onDismiss != null) {
            Spacer(modifier = Modifier.width(2.dp))
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭提示",
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
