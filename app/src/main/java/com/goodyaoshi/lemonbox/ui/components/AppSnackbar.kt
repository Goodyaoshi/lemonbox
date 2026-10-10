package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary

/**
 * 应用级统一提示通道（I10）。
 *
 * 背景：此前「操作成功」类反馈散落各处、各走各的通道——连续录入完成、扫码入库成功用系统 Toast，
 * 备份结果又用对话框。同一类结果在不同页面表现不一致，Toast 在应用前台还容易与系统提示互相顶掉、
 * 被用户忽略，重要结果就此错过。
 *
 * 规范：成功 / 中性结果统一走底部 Snackbar（本类承载）；失败与需要用户决策的提示走对话框或页面
 * 内联提示（见 [InlineNotice]）。这样一个 App 只有两种反馈语言，醒目且可预期。
 */
class AppSnackbarState internal constructor(val hostState: SnackbarHostState) {

    /** 展示一条成功 / 中性提示：非阻塞、不打断操作，尤其适合连续录入这类连续动作。 */
    suspend fun showMessage(message: String) {
        hostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
    }
}

/**
 * 全局提示通道。由 MainActivity 在应用根部提供，页面通过 `LocalAppSnackbar.current` 取用。
 * 默认值为 null：未接入时（如 Compose 预览）静默降级，不抛异常。
 */
val LocalAppSnackbar = staticCompositionLocalOf<AppSnackbarState?> { null }

/**
 * 全局 Snackbar 宿主：放在导航容器最外层、底部导航之上，保证任何页面都能看到同一条提示。
 * 颜色只引用语义色板，自动跟随深浅主题（I10 / F5，无字面量颜色）。
 */
@Composable
fun AppSnackbarHost(
    state: AppSnackbarState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(hostState = state.hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = SurfaceWarmDeep,
            contentColor = TextPrimary,
            actionColor = OrangeStart
        )
    }
}
