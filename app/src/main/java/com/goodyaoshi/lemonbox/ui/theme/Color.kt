package com.goodyaoshi.lemonbox.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ============================================================
// 「清新柠檬」主题色板
// 说明：历史上这套槽位叫 Orange*，现在统一承载品牌功能色（叶绿）
// 与辅助色（薄荷）。变量名保持不变，以便全站一次性换肤。
// 设计基调：清爽绿白底 + 柠檬黄主视觉 + 叶绿功能色 + 深绿灰文字。
//
// 深浅色适配：语义色（页面底色 / 卡片 / 文字 / 浅色块）改为随主题变化的
// @Composable 读取，底层由 LemonTheme 提供的 LemonPalette 决定；
// 品牌色与状态色（柠檬黄 / 薄荷 / 标签 / 状态）保持恒定，深浅模式通用。
// 因此调用点无需改动，只需保证在 @Composable 上下文中读取。
// ============================================================

// ---- 柠檬黄：hero 渐变 / 主按钮渐变（务必配深色文字）----
val LemonStart = Color(0xFFEFC22E)    // 柠檬黄（鲜亮主视觉）
val LemonEnd = Color(0xFFF7DA6B)      // 柠檬黄（浅）
val OnLemon = Color(0xFF3A2E10)       // 柠檬黄上的主文字（深橄榄）
val OnLemonSoft = Color(0xE63A2E10)   // 柠檬黄上的次级文字

// ---- 状态色 ----
val StatusExpired = Color(0xFFE2574C)
val StatusNormal = Color(0xFF4E9B37)
val StatusWarning = Color(0xFFD9A11F)
val StatusInfo = Color(0xFF4E8F86)

// ---- 标签底色 / 文字色（浅色原始值；深色见 DarkTag*，对外经主题感知 getter 暴露）----
internal val LightTagBlue = Color(0xFFE7F3F1)
internal val LightTagBlueText = Color(0xFF4E8F86)
internal val LightTagGreen = Color(0xFFE4F3DA)
internal val LightTagGreenText = Color(0xFF4E9B37)
internal val LightTagOrange = Color(0xFFFBF3D3)
internal val LightTagOrangeText = Color(0xFF9A7B1E)
internal val LightTagPurple = Color(0xFFF3EEFA)
internal val LightTagPurpleText = Color(0xFF8B6FC4)
internal val LightTagRed = Color(0xFFFCEDEA)
internal val LightTagRedText = Color(0xFFD9534F)

// ---- 品牌色板：柠檬（主）+ 薄荷（辅）+ 叶绿 ----
val LemonCream = Color(0xFFFCFEF8)
val LemonMist = Color(0xFFFFF8DC)
val LemonSoft = Color(0xFFFFEFB0)
val LemonSlice = Color(0xFFF7D64A)
val LemonGlow = Color(0x66F0C22E)
val LeafGreen = Color(0xFF4E9B37)
val MintGreen = Color(0xFF6BCB77)
val WarmBrown = Color(0xFF33402F)      // 深绿灰（splash 主文字）
val WarmBrownSoft = Color(0xFF5B6B55)
val WarmBrownDeep = Color(0xFF435040)
val WarmHint = Color(0xFF8A9683)

// ---- 主题无关的辅助强调 ----
val MintAccent = Color(0xFF6BCB77)       // 辅助强调（位置、成功）
val SageAccent = Color(0xFF5A9E8F)       // 次级操作（编辑）用的柔和青绿

// ============================================================
// 语义色：浅色 / 深色原始值（仅供 LemonPalette 与 colorScheme 使用）
// ============================================================

// 浅色
internal val LightBackground = Color(0xFFF2F9EC)
internal val LightBackgroundWarm = Color(0xFFFAFDF6)
internal val LightBackgroundTop = Color(0xFFFAFCF4)
internal val LightCardWhite = Color(0xFFFFFFFF)
internal val LightGlassWhite = Color(0xCCFFFFFF)
internal val LightDividerSoft = Color(0xFFE6EFDD)
internal val LightTextPrimary = Color(0xFF2F3A2C)
internal val LightTextSecondary = Color(0xFF6E7B68)
internal val LightTextHint = Color(0xFF9AA694)
internal val LightSurfaceWarm = Color(0xFFF7FBEF)
internal val LightSurfaceWarmDeep = Color(0xFFEDF5E3)
internal val LightOrangeStart = Color(0xFF4E9B37)
internal val LightOrangeEnd = Color(0xFF6FBF4A)
internal val LightOrangeTint = Color(0xFFF1F8EA)
internal val LightOrangeLight = Color(0xFFE4F3DA)
internal val LightOrangeGlow = Color(0xFFCDE8A8)
internal val LightMintSoft = Color(0xFFE7F5E9)
internal val LightSageSoft = Color(0xFFE4F1EE)

// 深色（深绿黑，而非纯黑/冷灰）
val DarkBackground = Color(0xFF161F19)
val DarkCard = Color(0xFF1F2B22)
internal val DarkBackgroundWarm = Color(0xFF131B15)
internal val DarkBackgroundTop = Color(0xFF1A241C)
internal val DarkGlassWhite = Color(0xCC1F2B22)
internal val DarkDividerSoft = Color(0xFF2E3B30)
internal val DarkTextPrimary = Color(0xFFE8F0E4)
internal val DarkTextSecondary = Color(0xFFAAB8A4)
internal val DarkTextHint = Color(0xFF7E8C78)
internal val DarkSurfaceWarm = Color(0xFF243026)
internal val DarkSurfaceWarmDeep = Color(0xFF2A382C)
// 深色下的叶绿需兼顾两种用法：作为强调文字（要够亮）与作为选中态底色承载白色文字
// （不能过亮），取折中的中绿。
internal val DarkOrangeStart = Color(0xFF5AA83F)
internal val DarkOrangeEnd = Color(0xFF7CC95C)
internal val DarkOrangeTint = Color(0xFF212E1E)
internal val DarkOrangeLight = Color(0xFF29381F)
internal val DarkOrangeGlow = Color(0xFF35502A)
internal val DarkMintSoft = Color(0xFF21382A)
internal val DarkSageSoft = Color(0xFF1E332F)

// 深色标签：低亮度同色系底 + 提亮文字，避免浅底色块在夜间刺眼。
internal val DarkTagBlue = Color(0xFF1C2E2C)
internal val DarkTagBlueText = Color(0xFF7FBFB4)
internal val DarkTagGreen = Color(0xFF21331C)
internal val DarkTagGreenText = Color(0xFF8FD06A)
internal val DarkTagOrange = Color(0xFF332B14)
internal val DarkTagOrangeText = Color(0xFFE0B84A)
internal val DarkTagPurple = Color(0xFF2A2338)
internal val DarkTagPurpleText = Color(0xFFB9A0E6)
internal val DarkTagRed = Color(0xFF35211D)
internal val DarkTagRedText = Color(0xFFF08B84)

// ============================================================
// 主题感知的语义色
// ============================================================

@Immutable
data class LemonPalette(
    val background: Color,
    val backgroundWarm: Color,
    val backgroundTop: Color,
    val cardWhite: Color,
    val glassWhite: Color,
    val dividerSoft: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textHint: Color,
    val surfaceWarm: Color,
    val surfaceWarmDeep: Color,
    val orangeStart: Color,
    val orangeEnd: Color,
    val orangeTint: Color,
    val orangeLight: Color,
    val orangeGlow: Color,
    val mintSoft: Color,
    val sageSoft: Color,
    val glassTint: Color,
    val glassFallback: Color,
    val tagBlue: Color,
    val tagBlueText: Color,
    val tagGreen: Color,
    val tagGreenText: Color,
    val tagOrange: Color,
    val tagOrangeText: Color,
    val tagPurple: Color,
    val tagPurpleText: Color,
    val tagRed: Color,
    val tagRedText: Color,
)

internal val LightLemonPalette = LemonPalette(
    background = LightBackground,
    backgroundWarm = LightBackgroundWarm,
    backgroundTop = LightBackgroundTop,
    cardWhite = LightCardWhite,
    glassWhite = LightGlassWhite,
    dividerSoft = LightDividerSoft,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    textHint = LightTextHint,
    surfaceWarm = LightSurfaceWarm,
    surfaceWarmDeep = LightSurfaceWarmDeep,
    orangeStart = LightOrangeStart,
    orangeEnd = LightOrangeEnd,
    orangeTint = LightOrangeTint,
    orangeLight = LightOrangeLight,
    orangeGlow = LightOrangeGlow,
    mintSoft = LightMintSoft,
    sageSoft = LightSageSoft,
    glassTint = Color(0x1AFFFFFF),
    glassFallback = Color(0xE6FFFFFF),
    tagBlue = LightTagBlue,
    tagBlueText = LightTagBlueText,
    tagGreen = LightTagGreen,
    tagGreenText = LightTagGreenText,
    tagOrange = LightTagOrange,
    tagOrangeText = LightTagOrangeText,
    tagPurple = LightTagPurple,
    tagPurpleText = LightTagPurpleText,
    tagRed = LightTagRed,
    tagRedText = LightTagRedText,
)

internal val DarkLemonPalette = LemonPalette(
    background = DarkBackground,
    backgroundWarm = DarkBackgroundWarm,
    backgroundTop = DarkBackgroundTop,
    cardWhite = DarkCard,
    glassWhite = DarkGlassWhite,
    dividerSoft = DarkDividerSoft,
    textPrimary = DarkTextPrimary,
    textSecondary = DarkTextSecondary,
    textHint = DarkTextHint,
    surfaceWarm = DarkSurfaceWarm,
    surfaceWarmDeep = DarkSurfaceWarmDeep,
    orangeStart = DarkOrangeStart,
    orangeEnd = DarkOrangeEnd,
    orangeTint = DarkOrangeTint,
    orangeLight = DarkOrangeLight,
    orangeGlow = DarkOrangeGlow,
    mintSoft = DarkMintSoft,
    sageSoft = DarkSageSoft,
    glassTint = Color(0x14FFFFFF),
    glassFallback = Color(0xE61F2B22),
    tagBlue = DarkTagBlue,
    tagBlueText = DarkTagBlueText,
    tagGreen = DarkTagGreen,
    tagGreenText = DarkTagGreenText,
    tagOrange = DarkTagOrange,
    tagOrangeText = DarkTagOrangeText,
    tagPurple = DarkTagPurple,
    tagPurpleText = DarkTagPurpleText,
    tagRed = DarkTagRed,
    tagRedText = DarkTagRedText,
)

internal val LocalLemonPalette = staticCompositionLocalOf { LightLemonPalette }

// ---- 语义色读取入口（随主题变化，需在 @Composable 中读取）----
val Background: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.background

val BackgroundWarm: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.backgroundWarm

/** 页面顶部渐变的起始色（比 [Background] 更亮一档）。 */
val BackgroundTop: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.backgroundTop

val CardWhite: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.cardWhite

val GlassWhite: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.glassWhite

val DividerSoft: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.dividerSoft

val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.textPrimary

val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.textSecondary

val TextHint: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.textHint

val SurfaceWarm: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.surfaceWarm

val SurfaceWarmDeep: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.surfaceWarmDeep

val OrangeStart: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.orangeStart

val OrangeEnd: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.orangeEnd

val OrangeTint: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.orangeTint

val OrangeLight: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.orangeLight

val OrangeGlow: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.orangeGlow

val MintSoft: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.mintSoft

val SageSoft: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.sageSoft

// ---- 标签色（随主题变化，需在 @Composable 中读取）----
val TagBlue: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.tagBlue

val TagBlueText: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.tagBlueText

val TagGreen: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.tagGreen

val TagGreenText: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.tagGreenText

val TagOrange: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.tagOrange

val TagOrangeText: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.tagOrangeText

val TagPurple: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.tagPurple

val TagPurpleText: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.tagPurpleText

val TagRed: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.tagRed

val TagRedText: Color
    @Composable @ReadOnlyComposable get() = LocalLemonPalette.current.tagRedText