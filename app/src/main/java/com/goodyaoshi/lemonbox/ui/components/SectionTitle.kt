package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

/**
 * 区块标题（P3-27）：左侧柠檬色条 + 标题文案，供「我的」「数据中心」等页的区块分组共用。
 *
 * 此前两个页面各写了一份私有实现，样式已经漂移（色条圆角 CircleShape / shapes.small 不一、
 * 标题字号 titleSmall / bodyMedium 不一）；此处抽成共用组件，统一收敛到主题形状与字阶令牌。
 */
@Composable
fun SectionTitle(
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
                // 圆角走形状令牌（P3-27）：色条两端全圆，收敛到 small（8dp）。
                .clip(MaterialTheme.shapes.small)
                .background(LemonEnd)
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = title,
            // 字号走主题字阶（P3-27）：区块标题收敛到 titleSmall（14sp）。
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = TextSecondary
        )
    }
}
