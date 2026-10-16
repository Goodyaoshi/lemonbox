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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
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
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.LocalLemonPalette
import com.goodyaoshi.lemonbox.ui.theme.MintSoft
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OrangeGlow
import com.goodyaoshi.lemonbox.ui.theme.OrangeLight
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.ShadowSpot
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TagRed
import com.goodyaoshi.lemonbox.ui.theme.TagRedText
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

/** 触达面积下限（F7）：Compose 的最小可点击尺寸，底栏单项的热区不会低于此值。 */
private val BottomBarMinTouchTarget = 48.dp

/** 底栏内容行的最小高度：与 `LemonBottomBar` 内 Row 的 `sizeIn(minHeight)` 保持一致。 */
internal val BottomBarRowMinHeight = 64.dp

/** 底栏内容区上下内边距：与 `LemonBottomBar` 的 `contentPadding` 保持一致。 */
internal val BottomBarContentPadding = 7.dp

/** 底栏卡片在屏幕上的上下外距：与 `LemonBottomBar` 调用处的 `padding(vertical)` 保持一致。 */
internal val BottomBarOuterMargin = 8.dp

/**
 * 底部导航栏卡片自身的占位高度（含上下外距），不含系统导航条。
 *
 * 由底栏自身的结构参数推导，避免「改动底栏却忘记同步这个数字」导致滚动页末项被压住
 * （F7）。内容行再与 48dp 最小热区取大：即使 [BottomItem] 的 `minimumInteractiveComponentSize()`
 * 把单项热区撑到 48dp，底栏也不会高于这里算出的占位值。
 */
internal val BottomBarPanelHeight: Dp
    get() = maxOf(BottomBarRowMinHeight, BottomBarMinTouchTarget) +
        BottomBarContentPadding * 2 +
        BottomBarOuterMargin * 2

/** 悬浮按钮与底部导航栏之间的间距。 */
private val FabAboveBottomBarGap = 16.dp

/** 无底部导航的页面上，悬浮按钮距系统导航条的间距。 */
private val FabAboveNavBarGap = 24.dp

/**
 * 悬浮加号按钮（录入物品 / 记一笔等）。
 *
 * 统一成一处实现的原因：这些按钮原先各自手写 `Box + clickable`，更麻烦的是
 * 「避开底部导航栏」的距离被硬编码成互不相同的数值（108dp / 118dp），
 * 而这个距离随设备的系统导航方式变化——手势导航下导航条几乎不占高度，
 * 三键导航下要占约 48dp，于是同一个按钮在部分设备上就被导航栏压住。
 * 现在按 [WindowInsets] 实时计算，并显式区分页面有没有底部导航栏。
 */
@Composable
fun LemonFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 该页面是否显示底部导航栏（家当 / 记账 / 首页等一级页面为 true）。 */
    hasBottomBar: Boolean = false
) {
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomGap = if (hasBottomBar) {
        navBarPadding + BottomBarPanelHeight + FabAboveBottomBarGap
    } else {
        navBarPadding + FabAboveNavBarGap
    }
    Box(
        modifier = modifier
            .padding(end = 20.dp, bottom = bottomGap)
            .size(58.dp)
            .clip(CircleShape)
            .background(brush = Brush.linearGradient(colors = listOf(LemonStart, LemonEnd)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = OnLemon,
            modifier = Modifier.size(28.dp)
        )
    }
}

/**
 * 为可滚动内容留出底部导航栏的占位高度，避免列表最后一项被导航栏压住。
 * 页面没有底部导航栏时应改用 `WindowInsets.navigationBars`。
 */
@Composable
fun BottomBarClearance(modifier: Modifier = Modifier, extra: Dp = 0.dp) {
    Spacer(modifier = modifier.height(rememberBottomBarClearance(extra)))
}

/**
 * 底部导航栏 + 系统导航条的总占位高度，供 `contentPadding` 一类需要具体数值的地方使用
 * （`contentPadding` 收的是 `Dp`，塞不进 [BottomBarClearance] 这样的占位组件）。
 *
 * [extra] 是底栏之上的额外留白，按调用处原有数值传入，保证视觉间距不被改动。
 */
@Composable
fun rememberBottomBarClearance(extra: Dp = 0.dp): Dp {
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return navBarPadding + BottomBarPanelHeight + extra
}

/** 已自行处理系统导航条的页面需要的底栏占位高度（不含导航条）。 */
@Composable
fun rememberBottomBarPanelClearance(extra: Dp = 0.dp): Dp =
    BottomBarPanelHeight + extra

@Composable
fun AppSurfaceCard(
    modifier: Modifier = Modifier,
    // 圆角走形状令牌（F6）：与全 App 卡片统一收敛到 extraLarge（24dp）。
    shape: Shape = MaterialTheme.shapes.extraLarge,
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
        spotColor = ShadowSpot
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
    // 圆角走形状令牌（F6）：统一收敛到 extraLarge（24dp）。
    shape: Shape = MaterialTheme.shapes.extraLarge,
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
                spotColor = ShadowSpot
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
            // 圆角走形状令牌（F6）：外框中圆角档位。
            .clip(MaterialTheme.shapes.large)
            .background(SurfaceWarmDeep)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = selectedIndex == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    // 圆角走形状令牌（F6）：选中块小圆角档位。
                    .clip(MaterialTheme.shapes.medium)
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
                    // 圆角走形状令牌（F6）：收敛到 small（8dp）。
                    .clip(MaterialTheme.shapes.small)
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
 * （记账页的预算/统计/分类、吃饭页的菜谱库、家当页的域入口），位置与样式全 App 统一。
 * [badge] 大于 0 时在文字右侧追加计数角标，配色与筛选胶囊的角标一致。
 */
@Composable
fun HeaderActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    badge: Int = 0,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            // 触达面积达标（F7）：胶囊仅约 30dp 高，先撑出 48dp 最小可点区，再绘制胶囊本身，视觉尺寸不变。
            .minimumInteractiveComponentSize()
            // 圆角走形状令牌（F6）：胶囊收敛到 CircleShape。
            .clip(CircleShape)
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
        if (badge > 0) {
            Spacer(modifier = Modifier.width(5.dp))
            Box(
                // 角标随位数自然变宽（最小 16dp 圆形），避免两位数被裁切。
                modifier = Modifier
                    .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
                    .clip(CircleShape)
                    // 语义标签红（随主题切换）：深色模式下自动提亮，避免浅底刺眼。
                    .background(TagRedText)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badge.toString(),
                    // 数量角标走主题字阶（F6）：labelSmall 为 11sp，是标签字号下限（F7）。
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TagRed
                )
            }
        }
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
    // 圆角走形状令牌（F6）：胶囊收敛到 CircleShape。
    val shape = CircleShape

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
                    // 读屏可达（F8）：当按钮用的胶囊标签声明 Button 角色，读屏会提示「按钮」。
                    role = Role.Button,
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
        // 圆角走形状令牌（F6）。
        shape = MaterialTheme.shapes.extraLarge,
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
