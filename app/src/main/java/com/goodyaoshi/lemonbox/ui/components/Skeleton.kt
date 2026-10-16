package com.goodyaoshi.lemonbox.ui.components

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep

/**
 * 骨架屏：与内容同构的占位卡片，用来替换「数据到达前的假空态」。
 *
 * 用 StateFlow + emptyList() 作初值时，第一帧会被画成「还没有内容」，
 * 容易被误读为「清单被清空了」。加载期改画骨架屏，把「还没读到」和「确实为空」分开，
 * 既避免误读，也让等待过程有形状、不突兀（比转圈更接近最终画面）。
 *
 * 动效遵循系统「减少动画」（animator_duration_scale == 0）时退化为静态浅块。
 */
@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    itemCount: Int = 3,
    showHeader: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp)
) {
    val alpha = rememberSkeletonAlpha()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding)
            // 骨架是纯装饰，读屏只需要知道「正在加载」，不必逐个播报占位块。
            // 读屏可达（F8）：补 Polite 活动区域，进入加载态时自动播报「正在加载」。
            .semantics {
                contentDescription = "正在加载"
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showHeader) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.42f)
                    .height(18.dp),
                alpha = alpha
            )
        }
        repeat(itemCount.coerceAtLeast(1)) {
            SkeletonListCard(alpha = alpha)
        }
        // 底栏占位（F7）：骨架常出现在带底部导航的列表页，补足占位避免末块被底栏压住。
        BottomBarClearance()
    }
}

/** 单张列表骨架卡：圆形图标位 + 标题/副标题两行，与常见列表卡片同构。 */
@Composable
private fun SkeletonListCard(alpha: Float, modifier: Modifier = Modifier) {
    AppSurfaceCard(
        modifier = modifier.fillMaxWidth(),
        // 圆角走形状令牌（F6）。
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        shadowElevation = 10.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonBlock(modifier = Modifier.size(42.dp), shape = CircleShape, alpha = alpha)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .height(14.dp),
                    alpha = alpha
                )
                Spacer(modifier = Modifier.height(9.dp))
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(0.38f)
                        .height(11.dp),
                    alpha = alpha
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(11.dp),
            alpha = alpha
        )
    }
}

/**
 * 详情页骨架：顶部大图占位 + 标题/若干正文行，与详情页的图文结构同构。
 */
@Composable
fun SkeletonDetail(
    modifier: Modifier = Modifier,
    imageHeight: androidx.compose.ui.unit.Dp = 320.dp
) {
    val alpha = rememberSkeletonAlpha()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "正在加载" }
    ) {
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .height(imageHeight),
            // 圆角走形状令牌（F6）：方角收敛到 RectangleShape。
            shape = RectangleShape,
            alpha = alpha
        )
        Column(modifier = Modifier.padding(20.dp)) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(22.dp),
                alpha = alpha
            )
            Spacer(modifier = Modifier.height(12.dp))
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .height(13.dp),
                alpha = alpha
            )
            Spacer(modifier = Modifier.height(24.dp))
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                // 圆角走形状令牌（F6）。
                shape = MaterialTheme.shapes.large,
                alpha = alpha
            )
            Spacer(modifier = Modifier.height(12.dp))
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .height(72.dp),
                // 圆角走形状令牌（F6）。
                shape = MaterialTheme.shapes.large,
                alpha = alpha
            )
        }
    }
}

/** 骨架上的一枚占位块：随主题取色的浅暖块。 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    alpha: Float = 1f
) {
    // 圆角走形状令牌（F6）：默认收敛到 small（8dp）。
    val resolvedShape = shape ?: MaterialTheme.shapes.small
    Box(
        modifier = modifier
            .clip(resolvedShape)
            .alpha(alpha)
            .background(SurfaceWarmDeep)
    )
}

/**
 * 统一的骨架透明度：0.45↔0.9 缓慢呼吸，避免生硬的灰块；
 * 系统关闭动画时返回静态值，尊重「减少动效」。
 */
@Composable
private fun rememberSkeletonAlpha(): Float {
    val context = LocalContext.current
    val transition = rememberInfiniteTransition(label = "skeleton")
    val animated by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    val motionEnabled = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    }
    return if (motionEnabled) animated else 0.6f
}
