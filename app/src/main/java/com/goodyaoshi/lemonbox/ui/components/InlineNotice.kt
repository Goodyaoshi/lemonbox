package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TagRed

/**
 * 页面内联提示条（I10）：承载「失败 / 需注意」这类反馈，直接出现在相关界面内。
 *
 * 相比 Toast，内联提示不会被系统提示队列挤掉、也不用打断操作弹模态框，用户在当前上下文中就能
 * 读到失败原因；配色只取语义色板（[TagRed] 底色 + [StatusExpired] 文字与图标），深浅主题自适应。
 *
 * [onDismiss] 非空时右侧出现关闭按钮：对重试后已恢复、或用户已读到的提示，给一条主动收起的通道，
 * 不用等到下次数据刷新才消失。关闭按钮沿用 Material3 [IconButton]（≥48dp 触控热区）。
 */
@Composable
fun InlineNotice(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(TagRed)
            .padding(start = 14.dp, end = if (onDismiss != null) 4.dp else 14.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = StatusExpired,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = message,
            color = StatusExpired,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (onDismiss != null) {
            Spacer(modifier = Modifier.width(2.dp))
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭提示",
                    tint = StatusExpired,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
