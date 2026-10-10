package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.GlassWhite
import com.goodyaoshi.lemonbox.ui.theme.LocalLemonPalette
import com.goodyaoshi.lemonbox.ui.theme.MintSoft
import com.goodyaoshi.lemonbox.ui.theme.OrangeGlow
import com.goodyaoshi.lemonbox.ui.theme.OrangeLight
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

@Composable
fun appGlassStyle(blurAlpha: Float = 0.55f): HazeStyle {
    val palette = LocalLemonPalette.current
    val strength = blurAlpha.coerceIn(0f, 1f)
    return HazeStyle(
        backgroundColor = Color.Transparent,
        tints = listOf(
            HazeTint(palette.glassTint.copy(alpha = 0.08f + (0.05f * strength))),
            HazeTint(palette.glassTint.copy(alpha = 0.03f + (0.03f * strength)))
        ),
        blurRadius = (18 + (8 * strength)).dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(palette.glassFallback.copy(alpha = 0.56f + (0.12f * strength)))
    )
}

@Composable
fun AppDecorativeBackground(modifier: Modifier = Modifier) {
    val palette = LocalLemonPalette.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(palette.backgroundTop, palette.background)
                )
            )
    ) {
        Box(
            modifier = Modifier
                .size(260.dp)
                .offset(x = (-70).dp, y = (-50).dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(OrangeGlow.copy(alpha = 0.34f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(320.dp)
                .align(Alignment.TopEnd)
                .offset(x = 72.dp, y = (-76).dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(OrangeLight.copy(alpha = 0.85f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(240.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 56.dp, y = 48.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(MintSoft.copy(alpha = 0.75f), Color.Transparent)
                    )
                )
        )
    }
}

@Composable
fun AppSurfaceCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    contentPadding: PaddingValues = PaddingValues(20.dp),
    containerColor: Color = CardWhite.copy(alpha = 0.96f),
    shadowElevation: Dp = 18.dp,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardModifier = modifier.shadow(
        elevation = shadowElevation,
        shape = shape,
        ambientColor = OrangeStart.copy(alpha = 0.08f),
        spotColor = Color.Black.copy(alpha = 0.08f)
    )
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)

    if (onClick == null) {
        Card(
            modifier = cardModifier,
            shape = shape,
            colors = colors,
            elevation = elevation
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content
            )
        }
    } else {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            enabled = enabled,
            shape = shape,
            colors = colors,
            elevation = elevation,
            interactionSource = remember { MutableInteractionSource() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content
            )
        }
    }
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    contentPadding: PaddingValues = PaddingValues(20.dp),
    containerColor: Color = GlassWhite,
    borderColor: Color = GlassWhite,
    shadowElevation: Dp = 12.dp,
    blurAlpha: Float = 0.55f,
    content: @Composable ColumnScope.() -> Unit
) {
    val glassBackground = containerColor.copy(alpha = 0.015f + (blurAlpha.coerceIn(0f, 1f) * 0.01f))
    val glassStyle = appGlassStyle(blurAlpha = blurAlpha)
    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation,
                shape = shape,
                ambientColor = OrangeStart.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.08f)
            )
            .clip(shape)
            .then(
                if (hazeState == null) {
                    Modifier.background(glassBackground)
                } else {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = glassStyle
                    )
                }
            )
            .border(
                width = 1.dp,
                color = borderColor,
                shape = shape
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content
        )
    }
}

/**
 * 分段切换标签：外观与「分类与状态」页一致，用于同类内容的不同分组切换。
 * 传入选中的下标与各自的展示文案即可。
 */
@Composable
fun SegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceWarmDeep)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = selectedIndex == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) CardWhite else Color.Transparent)
                    // 触达面积达标（F7）：分段标签原本仅约 34dp 高，补足到 48dp 最小可点区。
                    .minimumInteractiveComponentSize()
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    // 字号走主题字阶（F6）：labelLarge 为 14sp，满足正文 ≥12sp（F7）。
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) OrangeStart else TextHint
                )
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    action: String? = null,
    modifier: Modifier = Modifier,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                // 走主题字阶（F6）：titleLarge 为 18sp，与设计稿一级标题一致。
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            subtitle?.let {
                Text(
                    text = it,
                    // 走主题字阶（F6）：bodySmall 为 12sp，满足说明文字 ≥12sp（F7）。
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        action?.let {
            val actionModifier = if (onActionClick != null) {
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    // 触达面积达标（F7）：纯文字操作入口补足到 48dp 最小可点区。
                    .minimumInteractiveComponentSize()
                    .clickable(onClick = onActionClick)
            } else {
                Modifier
            }
            Text(
                text = it,
                // 走主题字阶（F6）：labelLarge 为 14sp。
                style = MaterialTheme.typography.labelLarge,
                color = OrangeStart,
                fontWeight = FontWeight.SemiBold,
                modifier = actionModifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * 页头右侧的弱化入口胶囊：图标 + 文字，用于低频辅助功能的跳转
 * （记账页的预算/统计、吃饭页的菜谱库），位置与样式全 App 统一。
 */
@Composable
fun HeaderActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            // 触达面积达标（F7）：胶囊仅约 30dp 高，先撑出 48dp 最小可点区，再绘制胶囊本身，视觉尺寸不变。
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(999.dp))
            .background(SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = TextSecondary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            // 字号走主题字阶（F6）：labelMedium 为 12sp，满足 ≥12sp（F7）。
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
    }
}

@Composable
fun PillTag(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    contentColor: Color,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(999.dp)

    Box(
        modifier = if (onClick == null) {
            modifier
                .clip(shape)
                .background(backgroundColor)
                .padding(horizontal = 8.dp, vertical = 3.dp)
        } else {
            modifier
                // 触达面积达标（F7）：先撑出 48dp 最小可点区，再绘制胶囊本身，视觉尺寸不变。
                .minimumInteractiveComponentSize()
                .clip(shape)
                .background(backgroundColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    // 可点击胶囊标签恢复默认涟漪（I3）：它承载"点按切换"动作，需要按压反馈。
                    indication = LocalIndication.current,
                    onClick = onClick
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
        }
    ) {
        Text(
            text = text,
            // 字号走主题字阶（F6）：labelSmall 为 11sp，满足标签不低于 11sp（F7）。
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = OrangeStart
) {
    AppSurfaceCard(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        shadowElevation = 14.dp
    ) {
        Text(
            text = value,
            // 走主题字阶（F6）：headlineSmall 为 24sp。
            style = MaterialTheme.typography.headlineSmall,
            color = accent,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            // 走主题字阶（F6）：bodySmall 为 12sp，满足说明文字 ≥12sp（F7）。
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(DividerSoft)
    )
}
