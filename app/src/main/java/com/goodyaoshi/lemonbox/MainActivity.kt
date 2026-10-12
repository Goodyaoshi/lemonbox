package com.goodyaoshi.lemonbox

import android.Manifest
import android.app.KeyguardManager
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusCatalog
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.settings.ThemeMode
import com.goodyaoshi.lemonbox.ui.components.AppLockOverlay
import com.goodyaoshi.lemonbox.ui.components.AppSnackbarState
import com.goodyaoshi.lemonbox.ui.components.ItemStatusCatalogState
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.LocalItemStatusOptions
import com.goodyaoshi.lemonbox.ui.navigation.AppNavGraph
import com.goodyaoshi.lemonbox.ui.navigation.Screen
import com.goodyaoshi.lemonbox.ui.screen.splash.AppSplashScreen
import com.goodyaoshi.lemonbox.ui.theme.LemonTheme
import com.goodyaoshi.lemonbox.util.BrandCopy
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

    /** 桌面快捷方式发起的导航请求（F13）：冷启动落在起始页，热启动触发一次导航。 */
    private val navRequest = MutableStateFlow(ShortcutNavRequest(route = null, requestId = 0L))

    /** 应用锁状态（F19）：true 表示当前锁定，需要验证后才展示内容。 */
    private val locked = MutableStateFlow(false)

    /** 正在走系统凭据验证：期间退到后台不应重新上锁，避免验证弹窗被门禁挡住。 */
    private var authenticating = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    /** 系统密码 / 指纹验证结果（F19）。 */
    private val credentialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        authenticating = false
        if (result.resultCode == RESULT_OK) {
            locked.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 在 super.onCreate 之前按偏好切换启动主题，避免手动固定深色时开屏闪白。
        if (AppPreferences.shouldStartDark(this)) {
            setTheme(R.style.Theme_Lemon_Launcher_Dark)
        }
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShortcutIntent(intent)
        requestNotificationPermission()
        startKeepAliveIfEnabled()
        // 应用锁（F19）：冷启动若已开启则先上锁。
        locked.value = appPreferences.appLockEnabled.value
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
                val appLockEnabled by appPreferences.appLockEnabled.collectAsState()
                val appLockPinHash by appPreferences.appLockPinHash.collectAsState()
                val lockedState by locked.collectAsState()
                val statusCatalog = remember(customStatuses) {
                    ItemStatusCatalogState(
                        usage = ItemStatusCatalog.builtInUsage +
                            customStatuses.filter { it.dimension == StatusDimension.USAGE },
                        disposition = ItemStatusCatalog.builtInDisposition +
                            customStatuses.filter { it.dimension == StatusDimension.DISPOSITION }
                    )
                }

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
                    LocalAppSnackbar provides appSnackbar
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
                        // 应用锁门禁（F19）：闪屏结束后、已开启且有 PIN 时遮住内容。
                        val pinHash = appLockPinHash
                        if (!showSplash && lockedState && appLockEnabled && pinHash != null) {
                            AppLockOverlay(
                                pinHash = pinHash,
                                credentialAvailable = hasDeviceCredential(),
                                onUnlocked = { locked.value = false },
                                onUseDeviceCredential = { startCredentialAuth() }
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

    override fun onStop() {
        super.onStop()
        // 应用锁（F19）：退到后台后重新上锁（走系统凭据验证期间除外）。
        if (!authenticating && !isChangingConfigurations && appPreferences.appLockEnabled.value) {
            locked.value = true
        }
    }

    /** 设备是否设置了系统锁屏密码（可用作应用锁的后备验证手段）。 */
    private fun hasDeviceCredential(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardSecure == true

    /** 拉起系统密码 / 指纹验证（F19）。 */
    private fun startCredentialAuth() {
        val keyguard = getSystemService(KeyguardManager::class.java) ?: return
        if (!keyguard.isKeyguardSecure) return
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            "${BrandCopy.APP_NAME}已锁定",
            "验证以继续使用"
        ) ?: return
        authenticating = true
        credentialLauncher.launch(intent)
    }

    private fun requestNotificationPermission() {
        // POST_NOTIFICATIONS 仅在 Android 13（API 33）及以上存在，低版本无需申请。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
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
