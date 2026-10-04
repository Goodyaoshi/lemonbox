package com.goodyaoshi.lemonbox

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusCatalog
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.data.settings.ThemeMode
import com.goodyaoshi.lemonbox.ui.components.ItemStatusCatalogState
import com.goodyaoshi.lemonbox.ui.components.LocalItemStatusOptions
import com.goodyaoshi.lemonbox.ui.navigation.AppNavGraph
import com.goodyaoshi.lemonbox.ui.screen.splash.AppSplashScreen
import com.goodyaoshi.lemonbox.ui.theme.LemonTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appPreferences: AppPreferences

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
                    delay(900)
                    showSplash = false
                }

                CompositionLocalProvider(LocalItemStatusOptions provides statusCatalog) {
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
}
