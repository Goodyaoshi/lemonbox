package com.goodyaoshi.lemonbox.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Camera : Screen("camera")
    data object Save : Screen("save?imageUri={imageUri}&imageUris={imageUris}&mode={mode}&barcode={barcode}") {
        fun createRoute(
            imageUri: String? = null,
            imageUris: List<String> = emptyList(),
            mode: String = "append",
            barcode: String? = null
        ): String {
            val encodedUri = imageUri ?: ""
            val encodedUris = imageUris.joinToString(",") { Uri.encode(it) }
            val encodedBarcode = barcode?.let { Uri.encode(it) } ?: ""
            return "save?imageUri=$encodedUri&imageUris=$encodedUris&mode=$mode&barcode=$encodedBarcode"
        }
    }

    data object Scan : Screen("scan?barcode={barcode}") {
        fun createRoute(barcode: String): String = "scan?barcode=${Uri.encode(barcode)}"
    }

    data object Detail : Screen("detail/{itemId}/{scope}") {
        const val ScopeAll = "all"
        const val ScopeToBuy = "to_buy"
        const val ScopeUnused = "unused"
        const val ScopeUsedUp = "used_up"
        const val ScopeLentOut = "lent_out"
        const val ScopeGivenAway = "given_away"
        const val ScopeDiscarded = "discarded"

        /** 家当「在库」分组：在库且还没用完。 */
        const val ScopeInStock = "in_stock"
        const val ScopeInStockUnused = "in_stock_unused"
        const val ScopeInStockInUse = "in_stock_in_use"
        const val ScopeInStockUsedUp = "in_stock_used_up"
        const val ScopeOffHand = "off_hand"

        fun createRoute(itemId: Long, scope: String = ScopeAll): String = "detail/$itemId/$scope"
    }

    data object Search : Screen("search")
    data object Recipe : Screen("recipe")
    data object Category : Screen("category")
    data object Profile : Screen("profile")
    data object Settings : Screen("settings")
    data object LanSync : Screen("lan-sync")
    data object ToBuy : Screen("to-buy")
    data object Trash : Screen("trash")
    data object Expiry : Screen("expiry")
    data object Reminders : Screen("reminders")
    data object Edit : Screen("edit/{itemId}?imageUri={imageUri}&imageUris={imageUris}&mode={mode}") {
        fun createRoute(
            itemId: Long,
            imageUri: String? = null,
            imageUris: List<String> = emptyList(),
            mode: String = "append"
        ): String {
            val encodedUri = imageUri ?: ""
            val encodedUris = imageUris.joinToString(",") { Uri.encode(it) }
            return "edit/$itemId?imageUri=$encodedUri&imageUris=$encodedUris&mode=$mode"
        }
    }
}
