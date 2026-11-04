package com.goodyaoshi.lemonbox.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// 全部槽位都必须显式赋值：Material3 的默认基线是紫色系，只要漏掉一个槽位，
// 对应的原生组件（下拉菜单、时间选择器、开关、输入框描边…）就会露出紫灰，
// 与「绿白 + 柠檬」的品牌调性直接冲突。
private val LightColorScheme = lightColorScheme(
    primary = LightOrangeStart,
    onPrimary = Color.White,
    primaryContainer = LightOrangeLight,
    onPrimaryContainer = Color(0xFF1E3A16),
    inversePrimary = DarkOrangeEnd,
    secondary = MintAccent,
    // 薄荷绿偏亮，白字在它上面只有 1.9:1；改用深绿字。
    onSecondary = Color(0xFF10261A),
    secondaryContainer = LightMintSoft,
    onSecondaryContainer = Color(0xFF1F3D24),
    tertiary = LemonStart,
    onTertiary = OnLemon,
    tertiaryContainer = LemonSoft,
    onTertiaryContainer = OnLemon,
    background = LightBackgroundWarm,
    onBackground = LightTextPrimary,
    surface = LightCardWhite,
    onSurface = LightTextPrimary,
    surfaceVariant = LightOrangeTint,
    onSurfaceVariant = LightTextSecondary,
    surfaceTint = LightOrangeStart,
    surfaceDim = Color(0xFFEDF4E6),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = LightSurfaceWarm,
    surfaceContainer = Color(0xFFF3F9EC),
    surfaceContainerHigh = LightSurfaceWarmDeep,
    surfaceContainerHighest = Color(0xFFE4EED8),
    inverseSurface = LightTextPrimary,
    inverseOnSurface = LightSurfaceWarm,
    outline = Color(0xFFB7C9AA),
    outlineVariant = Color(0xFFDCE8D2),
    scrim = Color.Black,
    error = LightTagRedText,
    onError = Color.White,
    errorContainer = LightTagRed,
    onErrorContainer = Color(0xFF7F221F)
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkOrangeEnd,
    onPrimary = Color(0xFF10240C),
    primaryContainer = Color(0xFF2C3D24),
    onPrimaryContainer = Color(0xFFC9E8B8),
    inversePrimary = LightOrangeStart,
    secondary = MintAccent,
    onSecondary = Color(0xFF10261A),
    secondaryContainer = Color(0xFF2B3A2C),
    onSecondaryContainer = Color(0xFFC6E8CC),
    tertiary = LemonEnd,
    onTertiary = OnLemon,
    tertiaryContainer = Color(0xFF3A3315),
    onTertiaryContainer = Color(0xFFF7DA6B),
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkCard,
    onSurface = DarkTextPrimary,
    surfaceVariant = Color(0xFF2C3A2E),
    onSurfaceVariant = DarkTextSecondary,
    surfaceTint = DarkOrangeEnd,
    surfaceDim = Color(0xFF121A13),
    surfaceBright = Color(0xFF2C3B2D),
    surfaceContainerLowest = Color(0xFF111812),
    surfaceContainerLow = Color(0xFF1A241C),
    surfaceContainer = Color(0xFF202B21),
    surfaceContainerHigh = Color(0xFF263327),
    surfaceContainerHighest = Color(0xFF2C3A2E),
    inverseSurface = DarkTextPrimary,
    inverseOnSurface = LightTextPrimary,
    outline = Color(0xFF55684F),
    outlineVariant = Color(0xFF394733),
    scrim = Color.Black,
    error = DarkStatusExpired,
    onError = Color(0xFF4A1010),
    errorContainer = DarkTagRed,
    onErrorContainer = DarkStatusExpired
)

@Composable
fun LemonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkLemonPalette else LightLemonPalette
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalLemonPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LemonTypography,
            shapes = LemonShapes,
            content = content
        )
    }
}