package com.goodyaoshi.lemonbox

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusCatalog
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.settings.ThemeMode
import com.goodyaoshi.lemonbox.ui.components.AppSnackbarState
import com.goodyaoshi.lemonbox.ui.components.ItemStatusCatalogState
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.LocalItemStatusOptions
import com.goodyaoshi.lemonbox.ui.navigation.AppNavGraph
import com.goodyaoshi.lemonbox.ui.screen.splash.AppSplashScreen
import com.goodyaoshi.lemonbox.ui.theme.LemonTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var itemRepository: ItemRepository

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
        requestNotificationPermission()
        setContent {
            val themeMode by appPreferences.themeMode.collectAsState()
            val systemDark = isSystemInDarkTheme()
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
                    if (showSplash) {
                        AppSplashScreen(darkTheme = darkTheme)
                    } else {
                        AppNavGraph()
                    }
                }
            }
        }
    }

    private fun requestNotificationPermission() {
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

    private companion object {
        /** 闪屏最短展示时长：避免数据就绪过快时「闪一下就没了」。 */
        const val SPLASH_MIN_MS = 400L

        /** 闪屏最长等待时长：数据层异常或不发首个值时兜底，不让用户卡在开屏。 */
        const val SPLASH_MAX_MS = 2_000L
    }
}
