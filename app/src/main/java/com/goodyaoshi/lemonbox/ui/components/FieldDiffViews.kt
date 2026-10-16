package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.data.backup.FieldDiff
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary

/**
 * 「修改前后对比」的通用视觉语言（GAP-5）：原先只在同步模块内私有实现，
 * 抽成公共组件后，记账编辑回执、导入预览等也能用同一套「本地值 → 新值」表达，
 * 让字段级对比成为一种全 App 通用的语言，而不是某个页面的特产。
 */
@Composable
fun FieldDiffLine(diff: FieldDiff, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 圆角走形状令牌（F6）：收敛到 small（8dp）。
            .clip(MaterialTheme.shapes.small)
            .background(SurfaceWarmDeep)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = diff.label,
            // 字号走主题字阶（F6）：labelMedium 为 12sp。
            style = MaterialTheme.typography.labelMedium,
            color = TextHint
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = diff.localValue,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 2
            )
            Text(
                text = " → ",
                style = MaterialTheme.typography.bodyMedium,
                color = TextHint
            )
            Text(
                text = diff.remoteValue,
                style = MaterialTheme.typography.bodyMedium,
                color = OrangeStart,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 2
            )
        }
    }
}

/**
 * 冲突条目的紧凑差异行：label｜本机值(划掉)｜对方值(高亮)。
 * 行内单行形态，让「勾了会失去什么、得到什么」在折叠态一眼可读。
 */
@Composable
fun ConflictFieldDiffLine(diff: FieldDiff, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = diff.label,
            // 字号走主题字阶（F6）：labelSmall 为 11sp。
            style = MaterialTheme.typography.labelSmall,
            color = TextHint,
            maxLines = 1
        )
        Text(text = "｜", style = MaterialTheme.typography.labelSmall, color = TextHint)
        Text(
            text = diff.localValue,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            textDecoration = TextDecoration.LineThrough,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(text = "｜", style = MaterialTheme.typography.labelSmall, color = TextHint)
        Text(
            text = diff.remoteValue,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = OrangeStart,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}
