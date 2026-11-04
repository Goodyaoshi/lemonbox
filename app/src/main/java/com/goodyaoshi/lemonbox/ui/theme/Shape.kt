package com.goodyaoshi.lemonbox.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val LemonShapes = Shapes(
    // M3 里 OutlinedTextField / Snackbar 的默认形状取 extraSmall（基线 4dp）；
    // 统一提到 14dp，原生输入框才和 App 内大量圆角填充框的观感一致。
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)
