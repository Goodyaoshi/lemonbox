package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    /**
     * 展示一条带**两个**动作按钮的提示（如待买勾选后的「记一笔 + 撤销」）。
     * M3 Snackbar 的动作槽位只有一个，这里借 [SnackbarVisuals] 把双动作交给
     * [AppSnackbarHost] 用自定义内容渲染；与 [showUndo] 一样不阻塞调用方、
     * 在应用级作用域执行。用户点了哪个按钮就回调哪个；超时或滑走则都不回调。
     */
    fun showDualAction(
        message: String,
        primaryLabel: String,
        secondaryLabel: String,
        onPrimary: () -> Unit,
        onSecondary: () -> Unit
    ) {
        scope.launch {
            val visuals = DualActionVisuals(message, listOf(primaryLabel, secondaryLabel))
            val result = hostState.showSnackbar(visuals)
            if (result == SnackbarResult.ActionPerformed) {
                when (visuals.clickedIndex) {
                    0 -> onPrimary()
                    1 -> onSecondary()
                }
            }
        }
    }
}

/**
 * 双动作提示的 visuals：点任意按钮都走 performAction()（结果为 ActionPerformed），
 * 具体点了哪个记录在 [clickedIndex]，由 [AppSnackbarState.showDualAction] 分发回调。
 */
private class DualActionVisuals(
    override val message: String,
    val actionLabels: List<String>
) : SnackbarVisuals {
    override val actionLabel: String get() = actionLabels.first()
    override val withDismissAction: Boolean get() = false
    override val duration: SnackbarDuration get() = SnackbarDuration.Long

    /** 用户点的是第几个按钮（-1 = 没点，超时或滑走）。 */
    var clickedIndex: Int = -1
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
        val dual = data.visuals as? DualActionVisuals
        if (dual == null) {
            Snackbar(
                snackbarData = data,
                containerColor = SurfaceWarmDeep,
                contentColor = TextPrimary,
                actionColor = OrangeStart
            )
        } else {
            DualActionSnackbar(dual) { index ->
                dual.clickedIndex = index
                data.performAction()
            }
        }
    }
}

/**
 * 双动作提示条：外观对齐 M3 Snackbar（暖底 + 品牌色按钮），但并排放两个动作按钮。
 * 只在 [DualActionVisuals] 消息时使用；排队、时长、滑走语义仍由 SnackbarHostState 托管。
 */
@Composable
private fun DualActionSnackbar(
    visuals: DualActionVisuals,
    onAction: (Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // 读屏可达（F8）：双动作提示是自定义 Surface，补 liveRegion 让读屏播报
            // 「操作已完成 + 可撤销」，否则视障用户既不知已成功、更不知能撤销。
            .semantics { liveRegion = LiveRegionMode.Polite },
        // 圆角走形状令牌（F6）：收敛到 medium（16dp）。
        shape = MaterialTheme.shapes.medium,
        color = SurfaceWarmDeep,
        contentColor = TextPrimary,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            visuals.actionLabels.forEachIndexed { index, label ->
                TextButton(onClick = { onAction(index) }) {
                    Text(text = label, color = OrangeStart, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
