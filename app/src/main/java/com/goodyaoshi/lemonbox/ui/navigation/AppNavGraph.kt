package com.goodyaoshi.lemonbox.ui.navigation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.ui.components.GlassPanel
import com.goodyaoshi.lemonbox.ui.components.appGlassStyle
import com.goodyaoshi.lemonbox.ui.screen.camera.CameraScreen
import com.goodyaoshi.lemonbox.ui.screen.category.CategoryScreen
import com.goodyaoshi.lemonbox.ui.screen.detail.DetailScreen
import com.goodyaoshi.lemonbox.ui.screen.edit.EditScreen
import com.goodyaoshi.lemonbox.ui.screen.expiry.ExpiryScreen
import com.goodyaoshi.lemonbox.ui.screen.home.HomeScreen
import com.goodyaoshi.lemonbox.ui.screen.profile.ProfileScreen
import com.goodyaoshi.lemonbox.ui.screen.recipe.RecipeScreen
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
import com.goodyaoshi.lemonbox.ui.theme.GlassWhite
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.viewmodel.LibraryFilter
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
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
        Screen.Recipe.route,
        "菜谱",
        Icons.AutoMirrored.Filled.MenuBook,
        Icons.AutoMirrored.Outlined.MenuBook
    ),
    BottomNavItem(Screen.Search.route, "家当", Icons.Filled.Search, Icons.Outlined.Search),
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
        Screen.Recipe.route,
        Screen.Search.route,
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
                        onNavigateToLibrary = { navigateToTopLevel(Screen.Search.route) },
                        onNavigateToExpiry = { navController.navigate(Screen.Expiry.route) },
                        onNavigateToScan = openScan,
                        onNavigateToToBuy = { navController.navigate(Screen.ToBuy.route) },
                        onNavigateToReminders = { navController.navigate(Screen.Reminders.route) }
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

                composable(Screen.Search.route) {
                    SearchScreen(
                        onNavigateToDetail = { id, filter ->
                            navController.navigate(Screen.Detail.createRoute(id, filter.toDetailScope()))
                        },
                        onNavigateToEdit = { id -> navController.navigate(Screen.Edit.createRoute(id)) }
                    )
                }

                composable(Screen.Recipe.route) {
                    RecipeScreen()
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
                        onOpenExpiry = { navController.navigate(Screen.Expiry.route) },
                        onOpenTrash = { navController.navigate(Screen.Trash.route) },
                        onOpenSettings = { navController.navigate(Screen.Settings.route) },
                        onOpenLanSync = { navController.navigate(Screen.LanSync.route) },
                        onOpenToBuy = { navController.navigate(Screen.ToBuy.route) },
                        onOpenCategory = { navController.navigate(Screen.Category.route) },
                        onOpenReminders = { navController.navigate(Screen.Reminders.route) },
                        onOpenAllItems = { navigateToTopLevel(Screen.Search.route) }
                    )
                }

                composable(Screen.ToBuy.route) {
                    ToBuyScreen(
                        onBack = { navController.popBackStack() }
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
                onCameraClick = { navController.navigate(Screen.Save.createRoute()) },
                hazeState = hazeState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
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

@Composable
private fun LemonBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onCameraClick: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(98.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        GlassPanel(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(72.dp),
            hazeState = hazeState,
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
            containerColor = GlassWhite,
            borderColor = GlassWhite,
            shadowElevation = 20.dp,
            blurAlpha = 0.6f
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                bottomNavItems.take(2).forEach { item ->
                    BottomItem(
                        item = item,
                        selected = currentRoute == item.route,
                        onClick = { onNavigate(item.route) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Box(modifier = Modifier.size(70.dp))
                bottomNavItems.takeLast(2).forEach { item ->
                    BottomItem(
                        item = item,
                        selected = currentRoute == item.route,
                        onClick = { onNavigate(item.route) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 15.dp)
                .size(width = 126.dp, height = 34.dp)
                .clip(RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp))
                .background(Color.White.copy(alpha = 0f))
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 4.dp)
                .size(72.dp)
                .clip(CircleShape)
                .hazeEffect(
                    state = hazeState,
                    style = appGlassStyle(blurAlpha = 0.6f)
                )
                .background(Color.White.copy(alpha = 0.04f))
                .border(width = 1.dp, color = Color.White.copy(alpha = 0.68f), shape = CircleShape)
                .clickable(onClick = onCameraClick),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(LemonStart, LemonEnd)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "录入",
                    tint = OnLemon,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
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
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
            contentDescription = item.label,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = item.label,
            color = color,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
