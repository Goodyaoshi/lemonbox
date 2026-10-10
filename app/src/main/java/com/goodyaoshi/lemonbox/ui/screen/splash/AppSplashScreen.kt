package com.goodyaoshi.lemonbox.ui.screen.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodyaoshi.lemonbox.R
import com.goodyaoshi.lemonbox.ui.theme.Background
import com.goodyaoshi.lemonbox.ui.theme.BackgroundTop
import com.goodyaoshi.lemonbox.ui.theme.BackgroundWarm
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.LemonCream
import com.goodyaoshi.lemonbox.ui.theme.LemonGlow
import com.goodyaoshi.lemonbox.ui.theme.LemonMist
import com.goodyaoshi.lemonbox.ui.theme.LemonSoft
import com.goodyaoshi.lemonbox.ui.theme.OrangeGlow
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.theme.WarmBrown
import com.goodyaoshi.lemonbox.ui.theme.WarmBrownDeep
import com.goodyaoshi.lemonbox.ui.theme.WarmBrownSoft
import com.goodyaoshi.lemonbox.ui.theme.WarmHint
import com.goodyaoshi.lemonbox.util.BrandCopy

/**
 * 开屏页。背景用奶油白→淡柠檬黄的微渐变托底，图标放在白色底盘上并带暖色投影，
 * 文字按「主标题 / 副标题 / 情感文案」三级拉开，情感文案单独放进半透明卡片里，
 * 避免原先「像免责声明」的观感。
 *
 * [darkTheme] 由 MainActivity 依据外观模式传入，深色下改用深绿底与高对比文字，
 * 避免夜间开屏出现大块亮色。
 */
@Composable
fun AppSplashScreen(darkTheme: Boolean = false) {
    val versionName = rememberVersionName()

    val backgroundColors = if (darkTheme) {
        listOf(BackgroundWarm, Background, BackgroundTop)
    } else {
        listOf(LemonCream, LemonMist, LemonSoft)
    }
    val glowColor = if (darkTheme) OrangeGlow else LemonGlow

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = backgroundColors))
    ) {
        SplashBackdrop(darkTheme = darkTheme)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .shadow(
                        elevation = 26.dp,
                        shape = RoundedCornerShape(40.dp),
                        ambientColor = glowColor,
                        spotColor = glowColor
                    )
                    .clip(RoundedCornerShape(40.dp))
                    .background(if (darkTheme) CardWhite else Color.White),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_brand_splash),
                    contentDescription = null,
                    modifier = Modifier.size(88.dp)
                )
            }

            Spacer(modifier = Modifier.height(34.dp))

            Text(
                text = BrandCopy.APP_NAME,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                color = if (darkTheme) TextPrimary else WarmBrown
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "家里的每件东西，都有它的位置",
                fontSize = 14.sp,
                letterSpacing = 1.sp,
                color = if (darkTheme) TextSecondary else WarmBrownSoft
            )

            Spacer(modifier = Modifier.height(46.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (darkTheme) CardWhite.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.72f))
                    .border(
                        width = 1.dp,
                        color = if (darkTheme) DividerSoft else Color.White.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "你负责布置小窝，\n我负责记住东西在哪。",
                    fontSize = 13.5.sp,
                    lineHeight = 22.sp,
                    color = if (darkTheme) TextPrimary else WarmBrownDeep,
                    textAlign = TextAlign.Center
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_leaf_soft),
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .alpha(0.45f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (versionName.isBlank()) BrandCopy.APP_NAME else "${BrandCopy.APP_NAME} · v$versionName",
                // 字号走主题字阶（F6），并满足说明文字 ≥12sp（F7）。
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 0.5.sp,
                color = if (darkTheme) TextHint else WarmHint
            )
        }
    }
}

/** 中央柔光 + 两片若隐若现的柠檬叶，制造空间层次而不抢主体。 */
@Composable
private fun BoxScope.SplashBackdrop(darkTheme: Boolean) {
    val glowCenter = if (darkTheme) OrangeGlow.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.9f)
    val glowEdge = if (darkTheme) OrangeGlow.copy(alpha = 0f) else Color.White.copy(alpha = 0f)

    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .offset(y = (-56).dp)
            .size(340.dp)
            .background(
                Brush.radialGradient(colors = listOf(glowCenter, glowEdge))
            )
    )

    Image(
        painter = painterResource(id = R.drawable.ic_leaf_soft),
        contentDescription = null,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = 44.dp, y = (-34).dp)
            .size(196.dp)
            .rotate(26f)
            .alpha(0.12f)
    )

    Image(
        painter = painterResource(id = R.drawable.ic_leaf_soft),
        contentDescription = null,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .offset(x = (-58).dp, y = 48.dp)
            .size(236.dp)
            .rotate(-18f)
            .alpha(0.09f)
    )
}

@Suppress("DEPRECATION")
@Composable
private fun rememberVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
}