package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(
                elevation = if (enabled) 16.dp else 0.dp,
                // 圆角走形状令牌（F6）：56dp 高的主按钮用胶囊形状，替代 28dp 内联圆角。
                shape = CircleShape,
                ambientColor = LemonStart.copy(alpha = 0.20f),
                spotColor = LemonEnd.copy(alpha = 0.24f)
            )
            .clip(CircleShape)
            .background(
                brush = Brush.horizontalGradient(
                    colors = if (enabled) listOf(LemonStart, LemonEnd)
                    else listOf(SurfaceWarmDeep, SurfaceWarmDeep)
                )
            )
            .clickable(
                interactionSource = interactionSource,
                // 主按钮恢复默认涟漪反馈（I3）：此前 indication = null 主动关闭了
                // 按压反馈，用户点下去毫无视觉确认，容易误以为没点中而重复点击。
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = {
                    // 轻触感：主按钮按下时给一次很轻的触觉反馈，确认「点中了」，不喧宾夺主。
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            // 禁用态改用 TextSecondary（比 TextHint 深一档）：此前灰字压在浅暖底上对比度
            // 不足，看起来像「字被抹掉了」；现在仍能读出按钮文案，只是不可点（P3）。
            color = if (enabled) OnLemon else TextSecondary,
            // 字号走主题字阶（F6）：titleMedium 为 16sp。
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
