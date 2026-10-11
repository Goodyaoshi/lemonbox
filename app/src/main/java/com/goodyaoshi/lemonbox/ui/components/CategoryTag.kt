package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 分类标签：颜色由 [categoryTagColors] 按稳定标识取色，保证同一分类在各页面同色。
 * 详情页只有分类路径文本、拿不到 id，故按名称取色（同名同色）。
 */
@Composable
fun CategoryTag(text: String, modifier: Modifier = Modifier) {
    val (bgColor, textColor) = categoryTagColors(categoryId = null, name = text)
    PillTag(
        text = text,
        modifier = modifier,
        backgroundColor = bgColor,
        contentColor = textColor
    )
}
