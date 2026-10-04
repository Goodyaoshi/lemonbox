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

private val LightColorScheme = lightColorScheme(
    primary = LightOrangeStart,
    onPrimary = Color.White,
    primaryContainer = LightOrangeLight,
    onPrimaryContainer = LightTextPrimary,
    secondary = MintAccent,
    onSecondary = Color.White,
    secondaryContainer = LightMintSoft,
    tertiary = LemonStart,
    onTertiary = OnLemon,
    tertiaryContainer = LemonSoft,
    onTertiaryContainer = OnLemon,
    background = LightBackgroundWarm,
    surface = LightCardWhite,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    surfaceVariant = LightOrangeTint,
    outline = LightDividerSoft
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkOrangeEnd,
    onPrimary = Color(0xFF10240C),
    primaryContainer = Color(0xFF2C3D24),
    secondary = MintAccent,
    onSecondary = Color(0xFF10261A),
    secondaryContainer = Color(0xFF2B3A2C),
    tertiary = LemonEnd,
    onTertiary = OnLemon,
    background = DarkBackground,
    surface = DarkCard,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    surfaceVariant = Color(0xFF2C3A2E),
    outline = DarkDividerSoft
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