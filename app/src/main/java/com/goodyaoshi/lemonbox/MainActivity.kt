package com.goodyaoshi.lemonbox

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.goodyaoshi.lemonbox.data.imagecloud.ImageUrlResolver
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusCatalog
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.settings.ThemeMode
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSnackbarState
import com.goodyaoshi.lemonbox.ui.components.ItemStatusCatalogState
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.LocalImageUrlResolver
import com.goodyaoshi.lemonbox.ui.components.LocalItemStatusOptions
import com.goodyaoshi.lemonbox.ui.navigation.AppNavGraph
import com.goodyaoshi.lemonbox.ui.navigation.Screen
import com.goodyaoshi.lemonbox.ui.screen.splash.AppSplashScreen
import com.goodyaoshi.lemonbox.ui.theme.LemonTheme
import com.goodyaoshi.lemonbox.util.TodoKeepAliveService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * 快捷方式导航请求（F13）：route 为 null 表示无需跳转；
 * requestId 每次自增，用于在 App 已运行时驱动一次热启动跳转。
 */
private data class ShortcutNavRequest(val route: String?, val requestId: Long)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var itemRepository: ItemRepository

    /**
     * 图片来源解析（F20）：把「本地路径 + 云端对象键」解析成图片加载器可用的 model。
     * 在根部注入一次、经 CompositionLocal 下发，避免每个用到图的地方各自持有。
     */
    @Inject
    lateinit var imageUrlResolver: ImageUrlResolver

    /** 桌面快捷方式发起的导航请求（F13）：冷启动落在起始页，热启动触发一次导航。 */
    private val navRequest = MutableStateFlow(ShortcutNavRequest(route = null, requestId = 0L))

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 在 super.onCreate 之前按偏好切换启动主题，避免手动固定深色时开屏闪白。
        if (AppPreferences.shouldStartDark(this)) {
            setTheme(R.style.Theme_Lemon_Launcher_Dark)
        }
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShortcutIntent(intent)
        startKeepAliveIfEnabled()
        setContent {
            val themeMode by appPreferences.themeMode.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val navRequestState by navRequest.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            LemonTheme(darkTheme = darkTheme) {
                var showSplash by remember { mutableStateOf(true) }
                // 全局提示通道（I10）：成功 / 中性结果统一走底部 Snackbar，宿主挂在导航容器最外层。
                // 作用域取 Activity 级（随整个会话存活），使「撤销」提示在页面退栈后依然有效。
                val appSnackbarScope = rememberCoroutineScope()
                val appSnackbar = remember { AppSnackbarState(SnackbarHostState(), appSnackbarScope) }
                val customStatuses by appPreferences.customStatuses.collectAsState()
                val statusCatalog = remember(customStatuses) {
                    ItemStatusCatalogState(
                        usage = ItemStatusCatalog.builtInUsage +
                            customStatuses.filter { it.dimension == StatusDimension.USAGE },
                        disposition = ItemStatusCatalog.builtInDisposition +
                            customStatuses.filter { it.dimension == StatusDimension.DISPOSITION }
                    )
                }

                // 通知权限不再冷启动即弹系统框（SET1）：先判断是否尚未授权，
                // 再由下面的说明弹窗在用户点「允许」后才触发系统申请，先说明用途再索取。
                var notificationIntroHandled by rememberSaveable { mutableStateOf(false) }
                val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED

                LaunchedEffect(Unit) {
                    // 闪屏不再写死时长（F13）：等首个核心数据流（首页依赖的家当列表）
                    // 跑出第一帧即关闭，冷启动快时不必白等、慢时也不会提前切走。
                    val startedAt = SystemClock.elapsedRealtime()
                    withTimeoutOrNull(SPLASH_MAX_MS) {
                        itemRepository.getAllItems().first()
                    }
                    // 下限避免「闪一下就没了」的突兀，上限兜底避免数据层异常时卡在开屏。
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    if (elapsed < SPLASH_MIN_MS) delay(SPLASH_MIN_MS - elapsed)
                    showSplash = false
                }

                CompositionLocalProvider(
                    LocalItemStatusOptions provides statusCatalog,
                    LocalAppSnackbar provides appSnackbar,
                    // 图片一律经解析器取：本地有就用本地（离线可看），本地没了才按对象键从云端取。
                    LocalImageUrlResolver provides imageUrlResolver
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (showSplash) {
                            AppSplashScreen(darkTheme = darkTheme)
                        } else {
                            AppNavGraph(
                                startRoute = navRequestState.route,
                                navRequestId = navRequestState.requestId
                            )
                        }
                        // 通知权限前置说明（SET1）：闪屏结束后、尚未授权时，先在应用内
                        // 说清用途，点「允许」才发系统弹窗；选「取消」也记为已处理，
                        // 同一会话内不再反复打扰。
                        if (!showSplash && !notificationIntroHandled && needsNotificationPermission) {
                            AppDialog(
                                title = "允许发送通知？",
                                subtitle = "用于待买清单与记账的到点提醒；不开启，提醒就不会按时响。"
                                    + "之后随时可在系统设置里更改。",
                                confirmText = "允许",
                                onConfirm = {
                                    notificationIntroHandled = true
                                    notificationPermissionLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                },
                                onDismissRequest = { notificationIntroHandled = true }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 单例复用（singleTop）：App 已在前台时再次从快捷方式进入走这里，
        // 通过 requestId 自增驱动导航容器跳转到目标路由（F13）。
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    /** 解析桌面快捷方式携带的 action 并转成一次导航请求（F13）；非快捷方式忽略。 */
    private fun handleShortcutIntent(intent: Intent?) {
        val route = intent?.action.toShortcutRoute() ?: return
        navRequest.value = ShortcutNavRequest(
            route = route,
            requestId = navRequest.value.requestId + 1
        )
    }

    /** 快捷方式 action → 应用内路由（F13），与 res/xml/shortcuts.xml 一一对应。 */
    private fun String?.toShortcutRoute(): String? = when (this) {
        ACTION_SHORTCUT_RECORD -> Screen.RecordEdit.createRoute()
        ACTION_SHORTCUT_TO_BUY -> Screen.ToBuy.route
        ACTION_SHORTCUT_CAMERA -> Screen.Camera.route
        else -> null
    }

    /**
     * 仅在用户主动打开应用时拉起前台保活服务（问题9）：把常驻服务启动时机从
     * Application.onCreate 收敛到「用户进入前台」，从而降低后台常驻内存占用。
     */
    private fun startKeepAliveIfEnabled() {
        if (!appPreferences.keepAliveEnabled.value) return
        val intent = Intent(this, TodoKeepAliveService::class.java)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (_: Exception) {
            // 系统限制拉起前台服务时忽略；下次进入前台再尝试。
        }
    }

    private companion object {
        /** 闪屏最短展示时长：避免数据就绪过快时「闪一下就没了」。 */
        const val SPLASH_MIN_MS = 400L

        /** 闪屏最长等待时长：数据层异常或不发首个值时兜底，不让用户卡在开屏。 */
        const val SPLASH_MAX_MS = 2_000L

        // 桌面快捷方式 action（F13），须与 res/xml/shortcuts.xml 保持一致。
        const val ACTION_SHORTCUT_RECORD = "com.goodyaoshi.lemonbox.shortcut.RECORD"
        const val ACTION_SHORTCUT_TO_BUY = "com.goodyaoshi.lemonbox.shortcut.TO_BUY"
        const val ACTION_SHORTCUT_CAMERA = "com.goodyaoshi.lemonbox.shortcut.CAMERA"
    }
}
