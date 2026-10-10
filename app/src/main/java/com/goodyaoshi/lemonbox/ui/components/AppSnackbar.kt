package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
class AppSnackbarState internal constructor(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope
) {

    /** 展示一条成功 / 中性提示：非阻塞、不打断操作，尤其适合连续录入这类连续动作。 */
    suspend fun showMessage(message: String) {
        hostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
    }

    /**
     * 展示一条成功 / 中性提示，但不阻塞调用方（在应用级作用域中执行）。
     * 适用于「提示后立即离开当前页面」的场景：若用 [showMessage] 在页面作用域里展示，
     * 页面退栈会把协程一起取消、提示随之消失；此处交给应用级作用域即可正常显示。
     */
    fun postMessage(message: String) {
        scope.launch {
            hostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }

    /**
     * 展示一条带动作按钮的提示，并返回用户是否点了该动作。
     *
     * 主要用于「删除后撤销」这类需要给用户一次反悔机会的场景：默认停留更久（[duration] 为
     * [SnackbarDuration.Long]），让用户来得及看到并点击。返回值为 [SnackbarResult.ActionPerformed]
     * 时表示用户点了动作按钮，调用方据此执行撤销；[SnackbarResult.Dismissed] 表示超时或被滑走，无需处理。
     *
     * 失败与需要决策的提示仍应走对话框 / [InlineNotice]，此处只承载可撤销的成功结果（I10）。
     */
    suspend fun showActionMessage(
        message: String,
        actionLabel: String,
        duration: SnackbarDuration = SnackbarDuration.Long
    ): SnackbarResult {
        return hostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            duration = duration
        )
    }

    /**
     * 展示一条「可撤销」的成功提示：调用后立即返回、不阻塞调用方，提示与撤销回调都在应用级
     * 作用域中执行。因此即便发起删除的页面随即退栈（如详情页删除后返回），提示依然可见可点。
     * 用户点了动作按钮才执行 [onUndo]；超时或滑掉则什么都不做。
     */
    fun showUndo(message: String, actionLabel: String, onUndo: () -> Unit) {
        scope.launch {
            val result = hostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
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
