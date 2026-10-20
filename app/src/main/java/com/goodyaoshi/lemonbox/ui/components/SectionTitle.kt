package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

/**
 * 区块标题。
 *
 * 早先是「4dp 柠檬色条 + 文字」，但那条浅黄色在浅色背景上的对比度只有 1.4:1，
 * 几乎看不见，只剩一块视觉噪音。现在只留文字，分组靠标题与上方留白表达，
 * 与页面里其余「小色块 + 标题」的模板结构一起收掉。
 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        // 字号走主题字阶（P3-27）：区块标题收敛到 titleSmall（14sp）。
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = TextSecondary,
        modifier = modifier
    )
}
