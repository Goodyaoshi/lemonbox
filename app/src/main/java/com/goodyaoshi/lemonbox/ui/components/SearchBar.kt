package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary

@Composable
fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "搜索物品、位置，或说点什么…",
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    onClick: (() -> Unit)? = null,
    onFocusChanged: (Boolean) -> Unit = {},
    onSearch: (() -> Unit)? = null,
    showMagicIconWhenEmpty: Boolean = true
) {
    val focusManager = LocalFocusManager.current

    // 搜索框是一枚胶囊：CircleShape（= 50% 圆角）在任何宽度下都退化为胶囊，
    // 统一替代原本散落在 clip / border / 遮罩层上的 26dp 内联圆角（F6）。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(CardWhite.copy(alpha = 0.96f))
            .border(1.dp, DividerSoft, CircleShape),
        contentAlignment = Alignment.CenterStart
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "搜索",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
                .size(20.dp),
            tint = TextHint
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 46.dp, end = 42.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        // 字号走主题字阶（F6）：labelLarge 为 14sp。
                        style = MaterialTheme.typography.labelLarge,
                        color = TextHint,
                        // 读屏可达（F8）：占位文案与输入框 label 重复，这里只作视觉占位，
                        // 从无障碍树中剔除，避免读屏把同一句话念两遍。
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 读屏可达（F8）：输入框此前无 label，独立 Text 作占位导致聚焦后读屏
                        // 只说「文本框」。这里补 contentDescription，让读屏读出用途。
                        .semantics { contentDescription = placeholder }
                        .onFocusChanged { onFocusChanged(it.isFocused) },
                    singleLine = true,
                    readOnly = readOnly,
                    textStyle = MaterialTheme.typography.labelLarge.copy(
                        color = TextPrimary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            onSearch?.invoke()
                            focusManager.clearFocus()
                        }
                    ),
                    cursorBrush = SolidColor(OrangeStart)
                )
            }
        }

        if (value.isNotEmpty()) {
            // 清空热区对齐 48dp（视觉圆点仍 24dp），避免小圆点难以点中。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(OrangeTint),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清空",
                        modifier = Modifier.size(14.dp),
                        tint = TextHint
                    )
                }
            }
        } else if (showMagicIconWhenEmpty) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .size(18.dp),
                tint = OrangeStart.copy(alpha = 0.65f)
            )
        }

        if (readOnly && onClick != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .clickable(onClick = onClick)
            )
        }
    }
}
