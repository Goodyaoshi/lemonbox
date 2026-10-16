package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

/**
 * 批量选择操作条（B1）：回收站批量范式的抽象——已选计数 + 全选切换 + 动作区。
 * 供记账流水、提醒等高频列表复用，避免每页各写一套选择交互。
 */
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "选择后可以批量操作",
    actions: @Composable RowScope.() -> Unit
) {
    AppSurfaceCard(
        modifier = modifier,
        // 圆角走形状令牌（F6）：收敛到 extraLarge（24dp）。
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (selectedCount == 0) "未选择项目" else "已选择 $selectedCount 项",
                    // 字号走主题字阶（F6）：titleMedium 为 16sp。
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = hint,
                    // 字号走主题字阶（F6）：bodySmall 为 12sp。
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            // 这里只是切换选择，不执行删除；沿用回收站的「全选 / 取消全选」文案约定。
            Text(
                text = if (allSelected) "取消全选" else "全选",
                // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = OrangeStart,
                modifier = Modifier
                    // 圆角走形状令牌（F6）：胶囊收敛到 CircleShape。
                    .clip(CircleShape)
                    .clickable(onClick = onToggleSelectAll)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions()
        }
    }
}

/** 批量操作条里的动作按钮：与回收站的操作按钮同一套视觉语言。 */
@Composable
fun SelectionActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlight: Boolean = false,
    destructive: Boolean = false
) {
    val container = when {
        !enabled -> SurfaceWarmDeep
        destructive -> StatusExpired.copy(alpha = 0.16f)
        highlight -> OrangeStart.copy(alpha = 0.14f)
        else -> SurfaceWarmDeep
    }
    val content = when {
        !enabled -> TextHint
        destructive -> StatusExpired
        highlight -> OrangeStart
        else -> TextPrimary
    }
    Text(
        text = text,
        // 字号走主题字阶（F6）：bodyMedium 为 14sp。
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = content,
        textAlign = TextAlign.Center,
        modifier = modifier
            // 圆角走形状令牌（F6）：胶囊收敛到 CircleShape。
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}
