package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.goodyaoshi.lemonbox.ui.theme.TagBlue
import com.goodyaoshi.lemonbox.ui.theme.TagBlueText
import com.goodyaoshi.lemonbox.ui.theme.TagGreen
import com.goodyaoshi.lemonbox.ui.theme.TagGreenText
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TagPurple
import com.goodyaoshi.lemonbox.ui.theme.TagPurpleText

@Composable
private fun tagColorPairs(): List<Pair<Color, Color>> = listOf(
    TagBlue to TagBlueText,
    TagGreen to TagGreenText,
    TagOrange to TagOrangeText,
    TagPurple to TagPurpleText
)

@Composable
fun CategoryTag(text: String, modifier: Modifier = Modifier) {
    val pairs = tagColorPairs()
    val colorIndex = text.hashCode().mod(pairs.size).let {
        if (it < 0) it + pairs.size else it
    }
    val (bgColor, textColor) = pairs[colorIndex]
    PillTag(
        text = text,
        modifier = modifier,
        backgroundColor = bgColor,
        contentColor = textColor
    )
}