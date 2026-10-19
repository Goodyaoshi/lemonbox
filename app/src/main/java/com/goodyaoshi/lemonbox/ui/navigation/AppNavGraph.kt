package com.goodyaoshi.lemonbox.ui.navigation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.ui.components.AppSnackbarHost
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.BottomBarContentPadding
import com.goodyaoshi.lemonbox.ui.components.BottomBarOuterMargin
import com.goodyaoshi.lemonbox.ui.components.BottomBarRowMinHeight
import com.goodyaoshi.lemonbox.ui.components.GlassPanel
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.rememberBottomBarPanelClearance
import com.goodyaoshi.lemonbox.ui.screen.anniversary.AnniversaryEditScreen
import com.goodyaoshi.lemonbox.ui.screen.anniversary.AnniversaryScreen
import com.goodyaoshi.lemonbox.ui.screen.camera.CameraScreen
import com.goodyaoshi.lemonbox.ui.screen.category.CategoryScreen
import com.goodyaoshi.lemonbox.ui.screen.category.CategoryTab
import com.goodyaoshi.lemonbox.ui.screen.detail.DetailScreen
import com.goodyaoshi.lemonbox.ui.screen.edit.EditScreen
import com.goodyaoshi.lemonbox.ui.screen.expiry.ExpiryScreen
import com.goodyaoshi.lemonbox.ui.screen.forecast.RestockForecastScreen
import com.goodyaoshi.lemonbox.ui.screen.home.HomeScreen
import com.goodyaoshi.lemonbox.ui.screen.imagecloud.ImageCloudScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerAnnualReviewScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerAssetsScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerBudgetScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerCategoryDetailScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerCategoryManageScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerPlanScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerStatsScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerTrendScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.RecordEditScreen
import com.goodyaoshi.lemonbox.ui.screen.meal.MealScreen
import com.goodyaoshi.lemonbox.ui.screen.meal.RecipeLibraryScreen
import com.goodyaoshi.lemonbox.ui.screen.ocr.OcrScreen
import com.goodyaoshi.lemonbox.ui.screen.packing.PackingListScreen
import com.goodyaoshi.lemonbox.ui.screen.packing.PackingTripScreen
import com.goodyaoshi.lemonbox.ui.screen.profile.DataCenterScreen
import com.goodyaoshi.lemonbox.ui.screen.profile.ProfileScreen
import com.goodyaoshi.lemonbox.ui.screen.reminders.RemindersScreen
import com.goodyaoshi.lemonbox.ui.screen.save.SavePhotoMode
import com.goodyaoshi.lemonbox.ui.screen.save.SaveScreen
import com.goodyaoshi.lemonbox.ui.screen.scan.ScanScreen
import com.goodyaoshi.lemonbox.ui.screen.search.HouseholdScreen
import com.goodyaoshi.lemonbox.ui.screen.settings.NotificationReliabilityScreen
import com.goodyaoshi.lemonbox.ui.screen.settings.SettingsScreen
import com.goodyaoshi.lemonbox.ui.screen.sleep.SleepScreen
import com.goodyaoshi.lemonbox.ui.screen.sync.LanSyncScreen
import com.goodyaoshi.lemonbox.ui.screen.sync.SyncDiagnosticsScreen
import com.goodyaoshi.lemonbox.ui.screen.timecapsule.TimeCapsuleScreen
import com.goodyaoshi.lemonbox.ui.screen.tobuy.ToBuyScreen
import com.goodyaoshi.lemonbox.ui.screen.trash.TrashScreen
import com.goodyaoshi.lemonbox.ui.scan.EXTRA_BARCODE
import com.goodyaoshi.lemonbox.ui.scan.ScanActivity
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.GlassWhite
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.viewmodel.encodeLibraryFilter
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.io.File

private data class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val bottomNavItems = listOf(
    BottomNavItem(Screen.Home.route, "首页", Icons.Filled.Home, Icons.Outlined.Home),
    BottomNavItem(
        Screen.Ledger.route,
        "记账",
        Icons.AutoMirrored.Filled.ReceiptLong,
        Icons.AutoMirrored.Outlined.ReceiptLong
    ),
    BottomNavItem(Screen.Meal.route, "吃饭", Icons.Filled.Restaurant, Icons.Outlined.Restaurant),
    // 「家当」用独立路由与箱柜图标（I7），不再复用搜索的放大镜。
    BottomNavItem(Screen.Household.route, "家当", Icons.Filled.Inventory2, Icons.Outlined.Inventory2),
    BottomNavItem(Screen.Profile.route, "我的", Icons.Filled.Person, Icons.Outlined.Person)
)

internal const val EDIT_RESULT_IMAGE_URI = "edit_result_image_uri"
internal const val EDIT_RESULT_IMAGE_URIS = "edit_result_image_uris"
internal const val EDIT_RESULT_MODE = "edit_result_mode"
internal const val SAVE_RESULT_IMAGE_URI = "save_result_image_uri"
internal const val SAVE_RESULT_IMAGE_URIS = "save_result_image_uris"
internal const val SAVE_RESULT_MODE = "save_result_mode"
internal const val OCR_RESULT_IMAGE_URI = "ocr_result_image_uri"
internal const val OCR_RESULT_ITEM_NAME = "ocr_result_item_name"

@Composable
fun AppNavGraph(startRoute: String? = null, navRequestId: Long = 0L) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    // destination.route 返回的是「注册时的模式串」：带可选参数的目的地（家当注册为
    // household?query={query}）拿到的是整串，而非 Screen.Household.route 的 "household"。
    // 底栏的可见性与 Tab 选中态都拿它比对无参常量路由，错配会让「家当」既不高亮、
    // 又因不在底栏白名单里导致整条底栏消失——这正是「点家当后无法返回」的直接原因。
    // 故统一按 '?' 前的主干比较，两种注册形式都能对上。
    val currentRoute = navBackStackEntry?.destination?.route?.substringBefore('?')
    var cameraExitGuard by remember { mutableStateOf(false) }
    var pendingCameraReturn by remember { mutableStateOf<CameraReturnTarget?>(null) }
    val hazeState = remember { HazeState() }
    val context = LocalContext.current
    // 页面转场动效开关：跟随系统「关闭动画」设置，关闭时退化为无转场（不堆砌动效）。
    val motionEnabled = rememberMotionEnabled()

    // 快捷方式热启动导航（F13）：requestId 变化表示 App 已在前台时再次从快捷方式进入，
    // 此时跳转到目标路由；冷启动由 startDestination 直接落在目标页，无需再跳。
    // lastHandledRequestId 以初值标记「首帧已处理」，避免冷启动重复导航。
    var lastHandledNavRequestId by remember { mutableStateOf(navRequestId) }
    LaunchedEffect(navRequestId) {
        if (navRequestId != lastHandledNavRequestId && startRoute != null) {
            lastHandledNavRequestId = navRequestId
            navController.navigate(startRoute) { launchSingleTop = true }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra(EXTRA_BARCODE)
            if (!scanned.isNullOrBlank()) {
                navController.navigateSingleTop(Screen.Scan.createRoute(scanned))
            }
        }
    }
    val openScan: () -> Unit = { scanLauncher.launch(Intent(context, ScanActivity::class.java)) }

    /**
     * 切到某个一级 Tab：以图根为基准清理返回栈，并保留被弹页面的状态。
     *
     * 图根通常是首页，但桌面快捷方式冷启动会让 startDestination 直接落在二级页
     * （记一笔 / 待买 / 拍照），此时 findStartDestination() 指向的不是首页。若仍按
     * 「保留图根」清理，首页会被压在那个二级页之下——用户切到首页后按返回，退回的却是
     * 一个二级页，观感上像「点 Tab 回不到首页」。故图根不是首页时，连图根一并弹出，
     * 让返回栈回到干净状态；图根就是首页时不加 inclusive，保持原有行为逐字不变。
     */
    val navigateToTopLevel: (String) -> Unit = { route ->
        val graphStart = navController.graph.findStartDestination()
        navController.navigate(route) {
            popUpTo(graphStart.id) {
                inclusive = graphStart.route != Screen.Home.route
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val openCamera: (CameraReturnTarget) -> Unit = { target ->
        pendingCameraReturn = target
        navController.navigateSingleTop(Screen.Camera.route)
    }

    LaunchedEffect(currentRoute) {
        if (currentRoute == Screen.Camera.route) {
            cameraExitGuard = true
        }
    }

    val showBottomBar = currentRoute in listOf(
        Screen.Home.route,
        Screen.Ledger.route,
        Screen.Meal.route,
        Screen.Household.route,
        Screen.Profile.route
    ) && !cameraExitGuard

    // 宽屏断点（E1）：此前全 App 没有任何自适应，平板 / 折叠屏展开态下正文整屏铺开，
    // 行宽过长、底栏被拉成超长胶囊。这里在全局唯一壳层收口，覆盖全部页面；窄屏逐字不变。
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wideScreen = maxWidth >= WideScreenBreakpoint
        // 宽屏：内容收进居中的栏并占满高度；窄屏：铺满原行为。
        val pageModifier = if (wideScreen) {
            Modifier
                .fillMaxHeight()
                .widthIn(max = WideScreenContentMaxWidth)
                .align(Alignment.TopCenter)
        } else {
            Modifier.fillMaxSize()
        }
        Box(
            modifier = pageModifier
                .hazeSource(hazeState)
        ) {
            NavHost(
                navController = navController,
                startDestination = startRoute ?: Screen.Home.route,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { if (motionEnabled) navForwardEnter() else EnterTransition.None },
                exitTransition = { if (motionEnabled) navForwardExit() else ExitTransition.None },
                popEnterTransition = { if (motionEnabled) navBackEnter() else EnterTransition.None },
                popExitTransition = { if (motionEnabled) navBackExit() else ExitTransition.None }
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        onNavigateToDetail = { id -> navController.navigateSingleTop(Screen.Detail.createRoute(id)) },
                        onNavigateToEdit = { id -> navController.navigateSingleTop(Screen.Edit.createRoute(id)) },
                        onNavigateToExpiry = { navController.navigateSingleTop(Screen.Expiry.route) },
                        onNavigateToScan = openScan,
                        // IA-2：首页搜索回车带关键词跳家当 Tab，复用同一套筛选。
                        // 走切 Tab 的导航（popUpTo 起始页 + launchSingleTop + restoreState），
                        // 而不是往栈顶 push 一个新实例：后者会让家当与底栏 Tab 不同源，
                        // 连点还会叠出多个家当页。
                        onSearchInHousehold = { query ->
                            navController.navigate(Screen.Household.createRoute(query)) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToToBuy = { navController.navigateSingleTop(Screen.ToBuy.route) },
                        onNavigateToReminders = { navController.navigateSingleTop(Screen.Reminders.route) },
                        onNavigateToLedger = { navigateToTopLevel(Screen.Ledger.route) },
                        onNavigateToMeal = { navigateToTopLevel(Screen.Meal.route) },
                        onNavigateToAnniversaries = { navController.navigateSingleTop(Screen.Anniversaries.route) },
                        // IA-3：首页纪念日条点行直达该条目的编辑页。
                        onNavigateToAnniversaryEdit = { id ->
                            navController.navigateSingleTop(Screen.AnniversaryEdit.createRoute(id))
                        }
                    )
                }

                composable(
                    route = Screen.Scan.route,
                    arguments = listOf(
                        navArgument("barcode") {
                            type = NavType.StringType
                            defaultValue = ""
                        }
                    )
                ) { backStackEntry ->
                    val scannedBarcode = backStackEntry.arguments?.getString("barcode").orEmpty()
                    ScanScreen(
                        barcode = Uri.decode(scannedBarcode),
                        onBack = { navController.popBackStack() },
                        onNavigateToDetail = { id ->
                            navController.navigateSingleTop(Screen.Detail.createRoute(id))
                        },
                        onNavigateToSaveForBarcode = { code ->
                            navController.navigateSingleTop(Screen.Save.createRoute(barcode = code))
                        }
                    )
                }

                composable(Screen.Camera.route) {
                    CameraScreen(
                        onBack = { navController.popBackStack() },
                        onDisposed = { cameraExitGuard = false },
                        onSkipPhoto = {
                            when (val target = pendingCameraReturn) {
                                null -> {
                                    navController.navigate(Screen.Save.createRoute()) {
                                        popUpTo(Screen.Camera.route) { inclusive = true }
                                    }
                                }

                                is CameraReturnTarget.SaveDraft -> {
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set(SAVE_RESULT_MODE, target.mode.name.lowercase())
                                    navController.popBackStack()
                                }

                                is CameraReturnTarget.EditItem -> {
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set(EDIT_RESULT_MODE, target.mode.name.lowercase())
                                    navController.popBackStack()
                                }

                                CameraReturnTarget.Ocr -> navController.popBackStack()
                            }
                            pendingCameraReturn = null
                        },
                        onPhotoTaken = { uris ->
                            val encoded = uris.map { it.toString() }
                            val firstUri = encoded.firstOrNull()
                            when (val target = pendingCameraReturn) {
                                is CameraReturnTarget.EditItem -> {
                                    navController.previousBackStackEntry?.savedStateHandle?.apply {
                                        set(EDIT_RESULT_IMAGE_URI, firstUri)
                                        set(EDIT_RESULT_IMAGE_URIS, encoded)
                                        set(EDIT_RESULT_MODE, target.mode.name.lowercase())
                                    }
                                    navController.popBackStack()
                                }

                                is CameraReturnTarget.SaveDraft -> {
                                    navController.previousBackStackEntry?.savedStateHandle?.apply {
                                        set(SAVE_RESULT_IMAGE_URI, firstUri)
                                        set(SAVE_RESULT_IMAGE_URIS, encoded)
                                        set(SAVE_RESULT_MODE, target.mode.name.lowercase())
                                    }
                                    navController.popBackStack()
                                }

                                CameraReturnTarget.Ocr -> {
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set(OCR_RESULT_IMAGE_URI, firstUri)
                                    navController.popBackStack()
                                }

                                null -> {
                                    navController.navigate(Screen.Save.createRoute(firstUri, encoded)) {
                                        popUpTo(Screen.Camera.route) { inclusive = true }
                                    }
                                }
                            }
                            pendingCameraReturn = null
                        }
                    )
                }

                composable(
                    route = Screen.Save.route,
                    arguments = listOf(
                        navArgument("imageUri") {
                            type = NavType.StringType
                            defaultValue = ""
                            nullable = true
                        },
                        navArgument("imageUris") {
                            type = NavType.StringType
                            defaultValue = ""
                            nullable = true
                        },
                        navArgument("mode") {
                            type = NavType.StringType
                            defaultValue = SavePhotoMode.APPEND.name.lowercase()
                        },
                        navArgument("barcode") {
                            type = NavType.StringType
                            defaultValue = ""
                            nullable = true
                        },
                        navArgument("name") {
                            type = NavType.StringType
                            defaultValue = ""
                            nullable = true
                        }
                    )
                ) { backStackEntry ->
                    val encodedUri = backStackEntry.arguments?.getString("imageUri").orEmpty()
                    val encodedUris = backStackEntry.arguments?.getString("imageUris").orEmpty()
                    val encodedBarcode = backStackEntry.arguments?.getString("barcode").orEmpty()
                    val encodedName = backStackEntry.arguments?.getString("name").orEmpty()
                    val resultImageUri by backStackEntry.savedStateHandle
                        .getStateFlow<String?>(SAVE_RESULT_IMAGE_URI, null)
                        .collectAsState()
                    val resultImageUris by backStackEntry.savedStateHandle
                        .getStateFlow(SAVE_RESULT_IMAGE_URIS, emptyList<String>())
                        .collectAsState()
                    val resultMode by backStackEntry.savedStateHandle
                        .getStateFlow<String?>(SAVE_RESULT_MODE, null)
                        .collectAsState()
                    val uri = encodedUri.takeIf { it.isNotBlank() }?.let { Uri.parse(Uri.decode(it)) }
                    val uris = encodedUris
                        .split(",")
                        .mapNotNull { value ->
                            value.takeIf { it.isNotBlank() }?.let { Uri.parse(Uri.decode(it)) }
                        }
                    SaveScreen(
                        imageUri = uri,
                        pendingImageUris = uris,
                        resultImageUri = resultImageUri?.let(Uri::parse),
                        resultImageUris = resultImageUris.map(Uri::parse),
                        resultPhotoMode = resultMode?.toSavePhotoMode(),
                        onConsumePendingResult = {
                            backStackEntry.savedStateHandle.remove<String>(SAVE_RESULT_IMAGE_URI)
                            backStackEntry.savedStateHandle.remove<List<String>>(SAVE_RESULT_IMAGE_URIS)
                            backStackEntry.savedStateHandle.remove<String>(SAVE_RESULT_MODE)
                        },
                        onBack = { navController.popBackStack() },
                        // 保存后回到发起录入的那一页（家当 / 扫码结果 / 首页），而不是一律清栈回首页：
                        // 从家当进来录入，保存后应还能看到刚存进去的那件，来源页的筛选条件也不丢。
                        // 「连续录入」走 SaveScreen 内部的分支、不经这里，行为不变。
                        // 栈底无处可退时（理论上不可达）退回首页，避免用户停在已清空的表单上。
                        onSaved = {
                            if (!navController.popBackStack()) {
                                navController.navigateSingleTop(Screen.Home.route)
                            }
                        },
                        onOpenCamera = { photoMode ->
                            openCamera(CameraReturnTarget.SaveDraft(photoMode))
                        },
                        onOpenOcr = { navController.navigateSingleTop(Screen.Ocr.route) },
                        initialBarcode = encodedBarcode.takeIf { it.isNotBlank() }?.let { Uri.decode(it) },
                        initialName = encodedName.takeIf { it.isNotBlank() }?.let { Uri.decode(it) }
                    )
                }

                composable(
                    route = Screen.Detail.route,
                    arguments = listOf(
                        navArgument("itemId") { type = NavType.LongType },
                        navArgument("scope") { type = NavType.StringType },
                        // 家当入口携带的完整筛选条件（见 Screen.Detail）：可选参数，
                        // 缺省时详情页按 scope 过滤，与既有非家当入口完全一致。
                        navArgument("filterJson") {
                            type = NavType.StringType
                            defaultValue = ""
                            nullable = true
                        },
                        navArgument("query") {
                            type = NavType.StringType
                            defaultValue = ""
                            nullable = true
                        }
                    )
                ) { backStackEntry ->
                    val itemId = backStackEntry.arguments?.getLong("itemId") ?: return@composable
                    val scope = backStackEntry.arguments?.getString("scope") ?: Screen.Detail.ScopeAll
                    DetailScreen(
                        itemId = itemId,
                        scope = scope,
                        onBack = { navController.popBackStack() },
                        onEdit = { id -> navController.navigateSingleTop(Screen.Edit.createRoute(id)) },
                        // 「记一笔」：把物品的价格×数量作为默认金额、物品名作为备注预填进记账页（F06）。
                        onRecordPurchase = { id, amountCents, name ->
                            navController.navigateSingleTop(
                                Screen.RecordEdit.createRoute(
                                    itemId = id,
                                    amount = amountCents,
                                    remark = name
                                )
                            )
                        }
                    )
                }

                // 家当 Tab 支持携带初始关键词（IA-2）：首页搜索回车直接带词进来；
                // query 为可选参数，底栏 Tab 仍导航不带参的 "household"，缺省空串即可匹配。
                composable(
                    route = "household?query={query}",
                    arguments = listOf(
                        navArgument("query") {
                            type = NavType.StringType
                            defaultValue = ""
                        }
                    )
                ) {
                    // query 为空时不留导航参数，使本次导航与底栏 Tab 的 "household" 完全同构，
                    // launchSingleTop 才能生效，避免连点首页搜索条叠出多个家当实例。
                    val initialQuery = navBackStackEntry?.arguments?.getString("query").orEmpty()
                    // 返回箭头只在「从首页搜索带词跳入」时有意义（切 Tab 导航不留 query）；
                    // 作为 Tab 根进入则不显示，与其余一级入口一致，靠底栏切换离开。
                    val showBackArrow = initialQuery.isNotBlank()
                    HouseholdScreen(
                        initialQuery = initialQuery,
                        showBackArrow = showBackArrow,
                        onBack = { navController.popBackStack() },
                        // 带上完整筛选条件（状态 / 分类 / 位置 / 关键词），详情页左右翻页才不会
                        // 翻出当前筛选之外的物品（第 5 条）。scope 缺省为 ScopeAll：集合改由 filterJson
                        // 表达，与家当列表共用同一份 SearchEngine 语义。
                        onNavigateToDetail = { id, filter ->
                            navController.navigateSingleTop(
                                Screen.Detail.createRoute(
                                    itemId = id,
                                    filterJson = encodeLibraryFilter(filter),
                                    query = initialQuery
                                )
                            )
                        },
                        onNavigateToEdit = { id -> navController.navigateSingleTop(Screen.Edit.createRoute(id)) },
                        onNavigateToSave = { navController.navigateSingleTop(Screen.Save.createRoute()) },
                        // 家当域的子页面（家当设置 / 待买 / 到期 / 回收站）从「我的」迁回本 Tab 根（I9）。
                        onNavigateToCategory = { navController.navigateSingleTop(Screen.Category.route) },
                        onNavigateToToBuy = { navController.navigateSingleTop(Screen.ToBuy.route) },
                        onNavigateToExpiry = { navController.navigateSingleTop(Screen.Expiry.route) },
                        onNavigateToTrash = { navController.navigateSingleTop(Screen.Trash.route) }
                    )
                }

                composable(Screen.Ledger.route) {
                    LedgerScreen(
                        onNavigateToStats = { navController.navigateSingleTop(Screen.LedgerStats.route) },
                        onNavigateToBudget = { navController.navigateSingleTop(Screen.LedgerBudget.route) },
                        onNavigateToAssets = { navController.navigateSingleTop(Screen.LedgerAssets.route) },
                        // 记账分类管理原本挂在「我的」，属记账域，收拢到记账页页头（I10）。
                        onNavigateToCategories = {
                            navController.navigateSingleTop(
                                Screen.LedgerCategoryManage.createRoute(LedgerCategory.KIND_EXPENSE)
                            )
                        },
                        onNavigateToPlan = { navController.navigateSingleTop(Screen.LedgerPlan.route) },
                        onNavigateToTrend = { navController.navigateSingleTop(Screen.LedgerTrend.route) },
                        onNavigateToAnnualReview = { navController.navigateSingleTop(Screen.LedgerAnnualReview.route) },
                        onNavigateToRecordEdit = { recordId ->
                            navController.navigateSingleTop(Screen.RecordEdit.createRoute(recordId = recordId))
                        },
                        onNavigateToItemDetail = { id ->
                            navController.navigateSingleTop(Screen.Detail.createRoute(id))
                        }
                    )
                }

                composable(
                    route = Screen.RecordEdit.route,
                    arguments = listOf(
                        navArgument("recordId") {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                        navArgument("itemId") {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                        navArgument("amount") {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                        navArgument("remark") {
                            type = NavType.StringType
                            defaultValue = ""
                        }
                    )
                ) {
                    RecordEditScreen(
                        onBack = { navController.popBackStack() },
                        onOpenOcr = { navController.navigateSingleTop(Screen.Ocr.route) }
                    )
                }

                composable(Screen.LedgerStats.route) {
                    LedgerStatsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenCategory = { kind, categoryId, monthKey ->
                            navController.navigateSingleTop(
                                Screen.LedgerCategoryDetail.createRoute(kind, categoryId, monthKey)
                            )
                        },
                        // 统计页空态「记一笔」直达记账编辑（I5），与记账主页 FAB 同一跳转。
                        onNavigateToRecordEdit = { recordId ->
                            navController.navigateSingleTop(Screen.RecordEdit.createRoute(recordId = recordId))
                        }
                    )
                }

                composable(Screen.LedgerBudget.route) {
                    LedgerBudgetScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.LedgerAssets.route) {
                    LedgerAssetsScreen(
                        onBack = { navController.popBackStack() },
                        // 账户被账单占用时的「查看在用账单」（P2-15）：先进记账主页看流水。
                        onViewRelatedRecords = { navController.navigateSingleTop(Screen.Ledger.route) }
                    )
                }

                composable(
                    route = Screen.LedgerCategoryDetail.route,
                    arguments = listOf(
                        navArgument("kind") {
                            type = NavType.IntType
                            defaultValue = LedgerCategory.KIND_EXPENSE
                        },
                        navArgument("categoryId") {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                        navArgument("monthKey") {
                            type = NavType.StringType
                            defaultValue = ""
                        }
                    )
                ) {
                    LedgerCategoryDetailScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToRecordEdit = { recordId ->
                            navController.navigateSingleTop(Screen.RecordEdit.createRoute(recordId = recordId))
                        }
                    )
                }

                composable(
                    route = Screen.LedgerCategoryManage.route,
                    arguments = listOf(
                        navArgument("kind") {
                            type = NavType.IntType
                            defaultValue = LedgerCategory.KIND_EXPENSE
                        }
                    )
                ) {
                    LedgerCategoryManageScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.LedgerPlan.route) {
                    LedgerPlanScreen(
                        onBack = { navController.popBackStack() },
                        // 模板空态的「去记一笔」通道（P2）：与其它记账页同款接线。
                        onNavigateToRecordEdit = { recordId ->
                            navController.navigateSingleTop(Screen.RecordEdit.createRoute(recordId = recordId))
                        }
                    )
                }

                composable(Screen.LedgerTrend.route) {
                    LedgerTrendScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToRecordEdit = { recordId ->
                            navController.navigateSingleTop(Screen.RecordEdit.createRoute(recordId = recordId))
                        }
                    )
                }

                composable(Screen.LedgerAnnualReview.route) {
                    LedgerAnnualReviewScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToRecordEdit = { recordId ->
                            navController.navigateSingleTop(Screen.RecordEdit.createRoute(recordId = recordId))
                        }
                    )
                }

                composable(Screen.Meal.route) {
                    MealScreen(
                        onNavigateToToBuy = { navController.navigateSingleTop(Screen.ToBuy.route) },
                        onNavigateToDetail = { id ->
                            navController.navigateSingleTop(Screen.Detail.createRoute(id))
                        },
                        onNavigateToRecipeLibrary = { navController.navigateSingleTop(Screen.RecipeLibrary.route) }
                    )
                }

                composable(Screen.RecipeLibrary.route) {
                    RecipeLibraryScreen(onBack = { navController.popBackStack() })
                }

                // 深链可指定初始 Tab（P2-24）：缺省「管理分类」，设置页的「提前几天提醒」
                // 直接落到「有效期与提醒」；不带参的 "category" 仍匹配本目的地。
                composable(
                    route = "category?tab={tab}",
                    arguments = listOf(
                        navArgument("tab") {
                            type = NavType.IntType
                            defaultValue = CategoryTab.CATEGORY
                        }
                    )
                ) {
                    val initialTab = navBackStackEntry?.arguments?.getInt("tab") ?: CategoryTab.CATEGORY
                    CategoryScreen(
                        onBack = { navController.popBackStack() },
                        initialTab = initialTab
                    )
                }

                composable(Screen.Expiry.route) {
                    ExpiryScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToDetail = { id -> navController.navigateSingleTop(Screen.Detail.createRoute(id)) },
                        // 「即将到期」列表项的编辑入口（F07）。
                        onNavigateToEdit = { id -> navController.navigateSingleTop(Screen.Edit.createRoute(id)) },
                        // 空态「去家当」：切回家当 Tab 根（而不是层层 popBackStack），动线更短（I11）。
                        onNavigateToHousehold = { navigateToTopLevel(Screen.Household.route) }
                    )
                }

                composable(Screen.Reminders.route) {
                    RemindersScreen(
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.Anniversaries.route) {
                    AnniversaryScreen(
                        onBack = { navController.popBackStack() },
                        onOpenEdit = { id ->
                            navController.navigateSingleTop(Screen.AnniversaryEdit.createRoute(id))
                        }
                    )
                }

                composable(
                    route = Screen.AnniversaryEdit.route,
                    arguments = listOf(
                        navArgument("anniversaryId") {
                            type = NavType.LongType
                            defaultValue = -1L
                        }
                    )
                ) {
                    AnniversaryEditScreen(onBack = { navController.popBackStack() })
                }

                composable(
                    route = Screen.Edit.route,
                    arguments = listOf(
                        navArgument("itemId") { type = NavType.LongType },
                        navArgument("imageUri") {
                            type = NavType.StringType
                            defaultValue = ""
                            nullable = true
                        },
                        navArgument("imageUris") {
                            type = NavType.StringType
                            defaultValue = ""
                            nullable = true
                        },
                        navArgument("mode") {
                            type = NavType.StringType
                            defaultValue = SavePhotoMode.APPEND.name.lowercase()
                        }
                    )
                ) { backStackEntry ->
                    val itemId = backStackEntry.arguments?.getLong("itemId") ?: return@composable
                    val encodedUri = backStackEntry.arguments?.getString("imageUri").orEmpty()
                    val encodedUris = backStackEntry.arguments?.getString("imageUris").orEmpty()
                    val mode = backStackEntry.arguments?.getString("mode").orEmpty()
                    val resultImageUri by backStackEntry.savedStateHandle
                        .getStateFlow<String?>(EDIT_RESULT_IMAGE_URI, null)
                        .collectAsState()
                    val resultImageUris by backStackEntry.savedStateHandle
                        .getStateFlow(EDIT_RESULT_IMAGE_URIS, emptyList<String>())
                        .collectAsState()
                    val resultMode by backStackEntry.savedStateHandle
                        .getStateFlow<String?>(EDIT_RESULT_MODE, null)
                        .collectAsState()
                    // OCR 回填的品名（第 6 条）：编辑页进 OCR 识别后经这里带回来。
                    val resultItemName by backStackEntry.savedStateHandle
                        .getStateFlow<String?>(OCR_RESULT_ITEM_NAME, null)
                        .collectAsState()
                    val uri = encodedUri.takeIf { it.isNotBlank() }?.let { Uri.parse(Uri.decode(it)) }
                    val uris = encodedUris
                        .split(",")
                        .mapNotNull { value ->
                            value.takeIf { it.isNotBlank() }?.let { Uri.parse(Uri.decode(it)) }
                        }
                    EditScreen(
                        itemId = itemId,
                        pendingImageUri = uri,
                        pendingImageUris = uris,
                        pendingPhotoMode = mode.toSavePhotoMode(),
                        resultImageUri = resultImageUri?.let(Uri::parse),
                        resultImageUris = resultImageUris.map(Uri::parse),
                        resultPhotoMode = resultMode?.toSavePhotoMode(),
                        resultItemName = resultItemName,
                        onConsumeResultName = {
                            backStackEntry.savedStateHandle.remove<String>(OCR_RESULT_ITEM_NAME)
                        },
                        onConsumePendingResult = {
                            backStackEntry.savedStateHandle.remove<String>(EDIT_RESULT_IMAGE_URI)
                            backStackEntry.savedStateHandle.remove<List<String>>(EDIT_RESULT_IMAGE_URIS)
                            backStackEntry.savedStateHandle.remove<String>(EDIT_RESULT_MODE)
                        },
                        onBack = { navController.popBackStack() },
                        onSaved = { navController.popBackStack() },
                        onOpenCamera = { photoMode ->
                            openCamera(CameraReturnTarget.EditItem(itemId, photoMode))
                        },
                        // 编辑页补齐 OCR 入口（第 6 条）：与录入页一致，编辑物品时也能拍照识字。
                        onOpenOcr = { navController.navigateSingleTop(Screen.Ocr.route) }
                    )
                }

                composable(Screen.Ocr.route) { backStackEntry ->
                    // 模式文案与可用出口同源（IA-3）：都由来路路由判定，避免「文案说去录入、
                    // 动作却把人推去记账」。独立进入（快捷方式冷启动）无来路，保留两个出口自选。
                    // 与 destination.route 逐字比较即可命中：目的地都以 Screen.*.route 的
                    // 模式串注册，previousBackStackEntry 拿到的正是同一个串。
                    val previousRoute = navController.previousBackStackEntry?.destination?.route
                    val cameFromSave = previousRoute == Screen.Save.route
                    val cameFromEdit = previousRoute == Screen.Edit.route
                    val cameFromRecordEdit = previousRoute == Screen.RecordEdit.route
                    val ocrModeLabel = when {
                        cameFromSave -> "存物录入 · 识别后去填物品表单"
                        cameFromEdit -> "编辑物品 · 识别后回填品名"
                        cameFromRecordEdit -> "记账 · 识别后去填账目"
                        else -> "独立工具 · 识别完自己选去向"
                    }
                    val cameraResultUri by backStackEntry.savedStateHandle
                        .getStateFlow<String?>(OCR_RESULT_IMAGE_URI, null)
                        .collectAsState()
                    OcrScreen(
                        modeLabel = ocrModeLabel,
                        // 出口按来意收敛：录入与编辑物品都不该被建议去记账，记账页则只回记账。
                        showRecordAction = !cameFromSave && !cameFromEdit,
                        showSaveAction = !cameFromRecordEdit,
                        onBack = { navController.popBackStack() },
                        onOpenCamera = { openCamera(CameraReturnTarget.Ocr) },
                        cameraResultUri = cameraResultUri,
                        onConsumeCameraResult = {
                            backStackEntry.savedStateHandle.remove<String>(OCR_RESULT_IMAGE_URI)
                        },
                        onNavigateToRecord = { amountCents, remark ->
                            // 从记一笔页进来识别后回跳：替换栈里的旧表单，避免返回时又见空白页（IA-3）。
                            // 来路不是记一笔时栈内没有该目的地，按来路才 popUpTo，否则会静默失效并叠页。
                            navController.navigate(
                                Screen.RecordEdit.createRoute(amount = amountCents, remark = remark)
                            ) {
                                if (cameFromRecordEdit) {
                                    popUpTo(Screen.RecordEdit.route) { inclusive = true }
                                }
                            }
                        },
                        onNavigateToSave = { name, imagePath ->
                            if (cameFromEdit) {
                                // 从编辑页进来：品名回填到编辑页本身。
                                // 这里绝不能再走「新建物品」那条跳转——编辑已有物品却跳到新建，
                                // 语义相反，而且栈里没有 Save 目的地、popUpTo 会静默失效。
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set(OCR_RESULT_ITEM_NAME, name)
                                navController.popBackStack()
                            } else {
                                val imageUri = imagePath?.let { Uri.fromFile(File(it)).toString() }
                                navController.navigate(
                                    Screen.Save.createRoute(imageUri = imageUri, name = name)
                                ) {
                                    // 同理：只有从快速录入进来才替换栈里那个旧表单。
                                    if (cameFromSave) popUpTo(Screen.Save.route) { inclusive = true }
                                }
                            }
                        }
                    )
                }

                composable(Screen.RestockForecast.route) {
                    RestockForecastScreen(
                        onBack = { navController.popBackStack() },
                        // 空态「去家当用掉 1 件」（P2-20）：切回家当 Tab 根，动线最短。
                        onOpenHousehold = { navigateToTopLevel(Screen.Household.route) }
                    )
                }

                composable(Screen.TimeCapsule.route) {
                    TimeCapsuleScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.Sleep.route) {
                    SleepScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.PackingList.route) {
                    PackingListScreen(
                        onBack = { navController.popBackStack() },
                        onOpenTrip = { tripId ->
                            navController.navigateSingleTop(Screen.PackingTrip.createRoute(tripId))
                        }
                    )
                }

                composable(
                    route = Screen.PackingTrip.route,
                    arguments = listOf(
                        navArgument("tripId") {
                            type = NavType.LongType
                            defaultValue = -1L
                        }
                    )
                ) {
                    PackingTripScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.Profile.route) {
                    ProfileScreen(
                        onOpenSettings = { navController.navigateSingleTop(Screen.Settings.route) },
                        onOpenDataCenter = { navController.navigateSingleTop(Screen.DataCenter.route) },
                        onOpenReminders = { navController.navigateSingleTop(Screen.Reminders.route) },
                        onOpenAnniversaries = { navController.navigateSingleTop(Screen.Anniversaries.route) },
                        onOpenTimeCapsule = { navController.navigateSingleTop(Screen.TimeCapsule.route) },
                        onOpenPackingList = { navController.navigateSingleTop(Screen.PackingList.route) },
                        onOpenSleep = { navController.navigateSingleTop(Screen.Sleep.route) }
                    )
                }

                composable(Screen.DataCenter.route) {
                    DataCenterScreen(
                        onBack = { navController.popBackStack() },
                        onOpenLanSync = { navController.navigateSingleTop(Screen.LanSync.route) },
                        onOpenImageCloud = {
                            navController.navigateSingleTop(Screen.ImageCloud.route)
                        }
                    )
                }

                composable(Screen.ImageCloud.route) {
                    ImageCloudScreen(
                        onBack = { navController.popBackStack() },
                        onOpenSyncDiagnostics = {
                            navController.navigateSingleTop(Screen.SyncDiagnostics.route)
                        }
                    )
                }

                composable(Screen.SyncDiagnostics.route) {
                    SyncDiagnosticsScreen(
                        onBack = { navController.popBackStack() },
                        // 空态直达「云端同步」（P2-23）：未连接时给一条去登录/开开关的路。
                        onOpenCloudSync = { navController.navigateSingleTop(Screen.ImageCloud.route) }
                    )
                }

                composable(Screen.ToBuy.route) {
                    ToBuyScreen(
                        onBack = { navController.popBackStack() },
                        onRecordPurchase = { itemId, amountCents, name ->
                            navController.navigateSingleTop(
                                Screen.RecordEdit.createRoute(
                                    itemId = itemId,
                                    amount = amountCents,
                                    remark = name
                                )
                            )
                        },
                        onOpenRestockForecast = { navController.navigateSingleTop(Screen.RestockForecast.route) },
                        // 结账无账户时的「去添加账户」直达记账页（P2）。
                        onNavigateToLedger = { navController.navigateSingleTop(Screen.Ledger.route) },
                        // 分类为空时的「去家当设置」直达入口（P3-25）。
                        onNavigateToCategory = { navController.navigateSingleTop(Screen.Category.route) }
                    )
                }

                composable(Screen.Trash.route) {
                    TrashScreen(
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenNotificationReliability = {
                            navController.navigateSingleTop(Screen.NotificationReliability.route)
                        },
                        // 提醒阶梯（提前几天）在家当设置里配，设置页只放时间点。
                        onOpenHouseholdSettings = {
                            navController.navigateSingleTop(Screen.Category.route)
                        },
                        // 「提前几天提醒」直达「家当设置 › 有效期与提醒」Tab（P2-24）。
                        onOpenExpiryReminderSettings = {
                            navController.navigateSingleTop(Screen.Category.createRoute(CategoryTab.EXPIRY_REMINDER))
                        }
                    )
                }

                composable(Screen.NotificationReliability.route) {
                    NotificationReliabilityScreen(
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.LanSync.route) {
                    LanSyncScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }

        if (showBottomBar) {
            LemonBottomBar(
                currentRoute = currentRoute,
                onNavigate = navigateToTopLevel,
                hazeState = hazeState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    // 外距与占位高度共用同一常量（F7）：保证末项不会被底栏压住。
                    .padding(horizontal = 16.dp, vertical = BottomBarOuterMargin)
                    // 宽屏断点（E1）：底栏与页面内容同宽居中对齐，不铺满整块大屏。
                    .then(
                        if (wideScreen) Modifier.widthIn(max = WideScreenContentMaxWidth) else Modifier
                    )
            )
        }

        // 全局提示宿主（I10）：所有页面的成功 / 中性反馈共用这一条通道，贴底居中；
        // 有底部导航时上抬到导航栏之上，避免提示被遮住。
        LocalAppSnackbar.current?.let { snackbar ->
            AppSnackbarHost(
                state = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(
                        horizontal = 16.dp,
                        vertical = if (showBottomBar) {
                            rememberBottomBarPanelClearance(extra = 8.dp)
                        } else {
                            16.dp
                        }
                    )
            )
        }
    }
}

private fun String.toSavePhotoMode(): SavePhotoMode {
    return when (lowercase()) {
        SavePhotoMode.REPLACE_PRIMARY.name.lowercase() -> SavePhotoMode.REPLACE_PRIMARY
        else -> SavePhotoMode.APPEND
    }
}

/**
 * 单实例导航：等价于 [NavController.navigate]，但显式声明 [launchSingleTop]。
 *
 * 本文件里详情 / 编辑 / 各二级页的跳转有 50 余处，全是「一对一的页面切换」，
 * 底栏与状态栏都没有拦截连点的机制——快速双击就会把同一个目的地入栈两次，
 * 用户按返回得连按两下才退得出去。
 *
 * 之所以收敛成扩展函数而不是逐处写 builder：逐处补 `navigate(route) { launchSingleTop = true }`
 * 会把 50 余处单行跳转膨胀成三行，且后续新增跳转极易漏掉。命名明确的入口让
 * 「允不允许重复入栈」成为调用点上的一次显式选择：需要 popUpTo / restoreState 等
 * 复杂行为的跳转，仍直接调 [NavController.navigate] 并自带 builder。
 *
 * 用扩展函数而非包装 [NavController] 的持有者类，是为了不改动
 * `rememberNavController()` 的既有接线，避免在此引入额外的导航状态对象。
 */
private fun NavController.navigateSingleTop(route: String) {
    navigate(route) { launchSingleTop = true }
}

private sealed interface CameraReturnTarget {
    data class SaveDraft(val mode: SavePhotoMode) : CameraReturnTarget
    data class EditItem(val itemId: Long, val mode: SavePhotoMode) : CameraReturnTarget
    data object Ocr : CameraReturnTarget
}

/**
 * 底部导航：5 个 Tab 平铺一行；录入家当入口在「家当」页的悬浮按钮里。
 * 玻璃胶囊是本 App 的视觉标识，但实时模糊在低版本系统（无 RenderEffect）上代价高、效果差，
 * 这类设备降级为不透明表面（F15）；热区高度对齐 M3 规范（≥64dp），造型不牺牲可点性。
 */
@Composable
private fun LemonBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val glassEnabled = rememberGlassEnabled()
    val barContent: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 行高与底栏占位高度共用同一常量（F7）：两侧不会漂移。
                .sizeIn(minHeight = BottomBarRowMinHeight),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            bottomNavItems.forEach { item ->
                BottomItem(
                    item = item,
                    selected = currentRoute == item.route,
                    onClick = { onNavigate(item.route) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
    if (glassEnabled) {
        GlassPanel(
            modifier = modifier.fillMaxWidth(),
            hazeState = hazeState,
            // 圆角走形状令牌（F6）：底栏与全 App 卡片统一收敛到 extraLarge，不再内联字面量。
            shape = MaterialTheme.shapes.extraLarge,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = BottomBarContentPadding),
            containerColor = GlassWhite,
            borderColor = GlassWhite,
            shadowElevation = 20.dp,
            blurAlpha = 0.6f,
            content = barContent
        )
    } else {
        AppSurfaceCard(
            modifier = modifier.fillMaxWidth(),
            // 圆角走形状令牌（F6）：与底栏统一收敛到 extraLarge。
            shape = MaterialTheme.shapes.extraLarge,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = BottomBarContentPadding),
            containerColor = CardWhite,
            shadowElevation = 12.dp,
            content = barContent
        )
    }
}

/**
 * 是否启用页面转场动效：跟系统「关闭动画」（animator_duration_scale == 0，含开发者选项 /
 * 辅助功能里的「移除动画」）联动，关闭时退化为无转场，尊重用户的减少动效诉求。
 * 与 [rememberGlassEnabled] 分开的原因：转场不依赖 API 31，低版本同样可用。
 */
@Composable
private fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    }
}

/** 宽屏断点（E1）：600dp 是 Material 指南「中等窗口」的起点，约 7 寸平板竖屏 / 折叠屏展开态。 */
private val WideScreenBreakpoint = 600.dp

/** 宽屏时页面内容与底栏的最大宽度（E1）：限制正文行宽，避免一行文字横跨整块大屏。 */
private val WideScreenContentMaxWidth = 600.dp

/** 转场时长：轻量、不拖沓，约 260ms，避免动效喧宾夺主。 */
private const val NAV_TRANSITION_DURATION_MS = 260

/** 位移只用屏宽的 1/10，配合淡入淡出，营造「轻推一页」的层次感而不产生眩晕。 */
private const val NAV_TRANSITION_SLIDE_FRACTION = 10

/** 前进：新页自右侧轻推入并淡入。 */
private fun navForwardEnter(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(NAV_TRANSITION_DURATION_MS, easing = FastOutSlowInEasing),
        initialOffsetX = { fullWidth -> fullWidth / NAV_TRANSITION_SLIDE_FRACTION }
    ) + fadeIn(animationSpec = tween(NAV_TRANSITION_DURATION_MS))

/** 前进：旧页向左轻移并淡出。 */
private fun navForwardExit(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(NAV_TRANSITION_DURATION_MS, easing = FastOutSlowInEasing),
        targetOffsetX = { fullWidth -> -fullWidth / NAV_TRANSITION_SLIDE_FRACTION }
    ) + fadeOut(animationSpec = tween(NAV_TRANSITION_DURATION_MS))

/** 返回：上一页自左侧轻推入并淡入。 */
private fun navBackEnter(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(NAV_TRANSITION_DURATION_MS, easing = FastOutSlowInEasing),
        initialOffsetX = { fullWidth -> -fullWidth / NAV_TRANSITION_SLIDE_FRACTION }
    ) + fadeIn(animationSpec = tween(NAV_TRANSITION_DURATION_MS))

/** 返回：当前页向右轻移并淡出。 */
private fun navBackExit(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(NAV_TRANSITION_DURATION_MS, easing = FastOutSlowInEasing),
        targetOffsetX = { fullWidth -> fullWidth / NAV_TRANSITION_SLIDE_FRACTION }
    ) + fadeOut(animationSpec = tween(NAV_TRANSITION_DURATION_MS))

/**
 * 是否启用实时模糊（F15）：RenderEffect 需要 Android 12（API 31）起；
 * 当系统关闭动画（animator_duration_scale == 0，含开发者选项/辅助功能里的「移除动画」）时也一并降级，
 * 既避免无效合成，也顺带满足「减少动效」的诉求。
 */
@Composable
private fun rememberGlassEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
    }
}

@Composable
private fun BottomItem(
    item: BottomNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = if (selected) OrangeStart else TextHint
    Column(
        modifier = modifier
            // 圆角走形状令牌（F6）：选中高亮与卡片小圆角统一到 large（20dp）。
            .clip(MaterialTheme.shapes.large)
            // 命中区兜底到 48dp（F7）：视觉尺寸可以小巧，热区不能缩水。
            .minimumInteractiveComponentSize()
            // 声明为 Tab 角色，读屏可正确播报「标签页」（F16）。
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
            // 图标紧邻文字标签，语义由标签承载，此处留空避免读屏重复播报。
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = item.label,
            color = color,
            // 导航标签走主题字阶（F6）且不低于 11sp（F7）：labelMedium 为 12sp。
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
