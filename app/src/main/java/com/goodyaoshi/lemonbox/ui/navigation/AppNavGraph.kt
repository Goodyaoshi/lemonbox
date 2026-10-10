package com.goodyaoshi.lemonbox.ui.navigation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ReceiptLong
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.ui.components.AppSnackbarHost
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.GlassPanel
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.screen.anniversary.AnniversaryEditScreen
import com.goodyaoshi.lemonbox.ui.screen.anniversary.AnniversaryScreen
import com.goodyaoshi.lemonbox.ui.screen.camera.CameraScreen
import com.goodyaoshi.lemonbox.ui.screen.category.CategoryScreen
import com.goodyaoshi.lemonbox.ui.screen.detail.DetailScreen
import com.goodyaoshi.lemonbox.ui.screen.edit.EditScreen
import com.goodyaoshi.lemonbox.ui.screen.expiry.ExpiryScreen
import com.goodyaoshi.lemonbox.ui.screen.home.HomeScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerAssetsScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerBudgetScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerCategoryDetailScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerCategoryManageScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.LedgerStatsScreen
import com.goodyaoshi.lemonbox.ui.screen.ledger.RecordEditScreen
import com.goodyaoshi.lemonbox.ui.screen.meal.MealScreen
import com.goodyaoshi.lemonbox.ui.screen.meal.RecipeLibraryScreen
import com.goodyaoshi.lemonbox.ui.screen.profile.ProfileScreen
import com.goodyaoshi.lemonbox.ui.screen.reminders.RemindersScreen
import com.goodyaoshi.lemonbox.ui.screen.save.SavePhotoMode
import com.goodyaoshi.lemonbox.ui.screen.save.SaveScreen
import com.goodyaoshi.lemonbox.ui.screen.scan.ScanScreen
import com.goodyaoshi.lemonbox.ui.screen.search.SearchScreen
import com.goodyaoshi.lemonbox.ui.screen.settings.SettingsScreen
import com.goodyaoshi.lemonbox.ui.screen.sync.LanSyncScreen
import com.goodyaoshi.lemonbox.ui.screen.tobuy.ToBuyScreen
import com.goodyaoshi.lemonbox.ui.screen.trash.TrashScreen
import com.goodyaoshi.lemonbox.ui.scan.EXTRA_BARCODE
import com.goodyaoshi.lemonbox.ui.scan.ScanActivity
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.GlassWhite
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.viewmodel.LibraryFilter
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

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
        Icons.Filled.ReceiptLong,
        Icons.Outlined.ReceiptLong
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

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var cameraExitGuard by remember { mutableStateOf(false) }
    var pendingCameraReturn by remember { mutableStateOf<CameraReturnTarget?>(null) }
    val hazeState = remember { HazeState() }
    val context = LocalContext.current

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra(EXTRA_BARCODE)
            if (!scanned.isNullOrBlank()) {
                navController.navigate(Screen.Scan.createRoute(scanned))
            }
        }
    }
    val openScan: () -> Unit = { scanLauncher.launch(Intent(context, ScanActivity::class.java)) }

    val navigateToTopLevel: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val openCamera: (CameraReturnTarget) -> Unit = { target ->
        pendingCameraReturn = target
        navController.navigate(Screen.Camera.route)
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

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        onNavigateToDetail = { id -> navController.navigate(Screen.Detail.createRoute(id)) },
                        onNavigateToEdit = { id -> navController.navigate(Screen.Edit.createRoute(id)) },
                        onNavigateToExpiry = { navController.navigate(Screen.Expiry.route) },
                        onNavigateToScan = openScan,
                        onNavigateToToBuy = { navController.navigate(Screen.ToBuy.route) },
                        onNavigateToReminders = { navController.navigate(Screen.Reminders.route) },
                        onNavigateToLedger = { navigateToTopLevel(Screen.Ledger.route) },
                        onNavigateToMeal = { navigateToTopLevel(Screen.Meal.route) },
                        onNavigateToAnniversaries = { navController.navigate(Screen.Anniversaries.route) }
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
                            navController.navigate(Screen.Detail.createRoute(id))
                        },
                        onNavigateToSaveForBarcode = { code ->
                            navController.navigate(Screen.Save.createRoute(barcode = code))
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
                        }
                    )
                ) { backStackEntry ->
                    val encodedUri = backStackEntry.arguments?.getString("imageUri").orEmpty()
                    val encodedUris = backStackEntry.arguments?.getString("imageUris").orEmpty()
                    val encodedBarcode = backStackEntry.arguments?.getString("barcode").orEmpty()
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
                        onSaved = {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Home.route) { inclusive = true }
                            }
                        },
                        onOpenCamera = { photoMode ->
                            openCamera(CameraReturnTarget.SaveDraft(photoMode))
                        },
                        initialBarcode = encodedBarcode.takeIf { it.isNotBlank() }?.let { Uri.decode(it) }
                    )
                }

                composable(
                    route = Screen.Detail.route,
                    arguments = listOf(
                        navArgument("itemId") { type = NavType.LongType },
                        navArgument("scope") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val itemId = backStackEntry.arguments?.getLong("itemId") ?: return@composable
                    val scope = backStackEntry.arguments?.getString("scope") ?: Screen.Detail.ScopeAll
                    DetailScreen(
                        itemId = itemId,
                        scope = scope,
                        onBack = { navController.popBackStack() },
                        onEdit = { id -> navController.navigate(Screen.Edit.createRoute(id)) }
                    )
                }

                composable(Screen.Household.route) {
                    SearchScreen(
                        onNavigateToDetail = { id, filter ->
                            navController.navigate(Screen.Detail.createRoute(id, filter.toDetailScope()))
                        },
                        onNavigateToEdit = { id -> navController.navigate(Screen.Edit.createRoute(id)) },
                        onNavigateToSave = { navController.navigate(Screen.Save.createRoute()) }
                    )
                }

                composable(Screen.Ledger.route) {
                    LedgerScreen(
                        onNavigateToStats = { navController.navigate(Screen.LedgerStats.route) },
                        onNavigateToBudget = { navController.navigate(Screen.LedgerBudget.route) },
                        onNavigateToAssets = { navController.navigate(Screen.LedgerAssets.route) },
                        onNavigateToRecordEdit = { recordId ->
                            navController.navigate(Screen.RecordEdit.createRoute(recordId = recordId))
                        },
                        onNavigateToItemDetail = { id ->
                            navController.navigate(Screen.Detail.createRoute(id))
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
                    RecordEditScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.LedgerStats.route) {
                    LedgerStatsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenCategory = { kind, categoryId, monthKey ->
                            navController.navigate(
                                Screen.LedgerCategoryDetail.createRoute(kind, categoryId, monthKey)
                            )
                        },
                        // 统计页空态「记一笔」直达记账编辑（I5），与记账主页 FAB 同一跳转。
                        onNavigateToRecordEdit = { recordId ->
                            navController.navigate(Screen.RecordEdit.createRoute(recordId = recordId))
                        }
                    )
                }

                composable(Screen.LedgerBudget.route) {
                    LedgerBudgetScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.LedgerAssets.route) {
                    LedgerAssetsScreen(onBack = { navController.popBackStack() })
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
                            navController.navigate(Screen.RecordEdit.createRoute(recordId = recordId))
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

                composable(Screen.Meal.route) {
                    MealScreen(
                        onNavigateToToBuy = { navController.navigate(Screen.ToBuy.route) },
                        onNavigateToDetail = { id ->
                            navController.navigate(Screen.Detail.createRoute(id))
                        },
                        onNavigateToRecipeLibrary = { navController.navigate(Screen.RecipeLibrary.route) }
                    )
                }

                composable(Screen.RecipeLibrary.route) {
                    RecipeLibraryScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.Category.route) {
                    CategoryScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.Expiry.route) {
                    ExpiryScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToDetail = { id -> navController.navigate(Screen.Detail.createRoute(id)) }
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
                            navController.navigate(Screen.AnniversaryEdit.createRoute(id))
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
                        onConsumePendingResult = {
                            backStackEntry.savedStateHandle.remove<String>(EDIT_RESULT_IMAGE_URI)
                            backStackEntry.savedStateHandle.remove<List<String>>(EDIT_RESULT_IMAGE_URIS)
                            backStackEntry.savedStateHandle.remove<String>(EDIT_RESULT_MODE)
                        },
                        onBack = { navController.popBackStack() },
                        onSaved = { navController.popBackStack() },
                        onOpenCamera = { photoMode ->
                            openCamera(CameraReturnTarget.EditItem(itemId, photoMode))
                        }
                    )
                }

                composable(Screen.Profile.route) {
                    ProfileScreen(
                        onOpenTrash = { navController.navigate(Screen.Trash.route) },
                        onOpenSettings = { navController.navigate(Screen.Settings.route) },
                        onOpenLanSync = { navController.navigate(Screen.LanSync.route) },
                        onOpenCategory = { navController.navigate(Screen.Category.route) },
                        onOpenLedgerCategories = {
                            navController.navigate(
                                Screen.LedgerCategoryManage.createRoute(LedgerCategory.KIND_EXPENSE)
                            )
                        },
                        onOpenReminders = { navController.navigate(Screen.Reminders.route) },
                        onOpenToBuy = { navController.navigate(Screen.ToBuy.route) },
                        onOpenExpiry = { navController.navigate(Screen.Expiry.route) },
                        onOpenAnniversaries = { navController.navigate(Screen.Anniversaries.route) }
                    )
                }

                composable(Screen.ToBuy.route) {
                    ToBuyScreen(
                        onBack = { navController.popBackStack() },
                        onRecordPurchase = { itemId, amountCents, name ->
                            navController.navigate(
                                Screen.RecordEdit.createRoute(
                                    itemId = itemId,
                                    amount = amountCents,
                                    remark = name
                                )
                            )
                        }
                    )
                }

                composable(Screen.Trash.route) {
                    TrashScreen(
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
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
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // 全局提示宿主（I10）：所有页面的成功 / 中性反馈共用这一条通道，贴底居中；
        // 有底部导航时上抬（> 导航栏高度），避免提示被导航栏遮住。
        LocalAppSnackbar.current?.let { snackbar ->
            AppSnackbarHost(
                state = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(
                        horizontal = 16.dp,
                        vertical = if (showBottomBar) 92.dp else 16.dp
                    )
            )
        }
    }
}

/**
 * 把家当的组合筛选条件映射成详情页的翻页范围。
 * 详情页的 scope 只按状态维度过滤，因此这里只取「能唯一判定」的常见组合，
 * 其余（例如叠加了分类/位置等条件）回退到 [Screen.Detail.ScopeAll]，不影响浏览。
 */
private fun LibraryFilter.toDetailScope(): String {
    val inStockOnly = dispositions == setOf(Item.DISPOSITION_IN_STOCK)
    return when {
        needRestockOnly -> Screen.Detail.ScopeToBuy
        dispositions == setOf(Item.DISPOSITION_LENT_OUT) -> Screen.Detail.ScopeLentOut
        dispositions == setOf(Item.DISPOSITION_GIVEN_AWAY) -> Screen.Detail.ScopeGivenAway
        dispositions == setOf(Item.DISPOSITION_DISCARDED) -> Screen.Detail.ScopeDiscarded
        dispositions == OFF_HAND_DISPOSITIONS && usageStatuses.isEmpty() -> Screen.Detail.ScopeOffHand
        inStockOnly && usageStatuses == setOf(Item.USAGE_UNUSED) -> Screen.Detail.ScopeInStockUnused
        inStockOnly && usageStatuses == setOf(Item.USAGE_IN_USE) -> Screen.Detail.ScopeInStockInUse
        inStockOnly && usageStatuses == setOf(Item.USAGE_USED_UP) -> Screen.Detail.ScopeInStockUsedUp
        inStockOnly && (usageStatuses.isEmpty() ||
            usageStatuses == setOf(Item.USAGE_UNUSED, Item.USAGE_IN_USE)) -> Screen.Detail.ScopeInStock
        else -> Screen.Detail.ScopeAll
    }
}

private val OFF_HAND_DISPOSITIONS = setOf(
    Item.DISPOSITION_LENT_OUT,
    Item.DISPOSITION_GIVEN_AWAY,
    Item.DISPOSITION_DISCARDED
)

private fun String.toSavePhotoMode(): SavePhotoMode {
    return when (lowercase()) {
        SavePhotoMode.REPLACE_PRIMARY.name.lowercase() -> SavePhotoMode.REPLACE_PRIMARY
        else -> SavePhotoMode.APPEND
    }
}

private sealed interface CameraReturnTarget {
    data class SaveDraft(val mode: SavePhotoMode) : CameraReturnTarget
    data class EditItem(val itemId: Long, val mode: SavePhotoMode) : CameraReturnTarget
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
                .sizeIn(minHeight = 64.dp),
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
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
            containerColor = GlassWhite,
            borderColor = GlassWhite,
            shadowElevation = 20.dp,
            blurAlpha = 0.6f,
            content = barContent
        )
    } else {
        AppSurfaceCard(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
            containerColor = CardWhite,
            shadowElevation = 12.dp,
            content = barContent
        )
    }
}

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
            .clip(RoundedCornerShape(20.dp))
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
