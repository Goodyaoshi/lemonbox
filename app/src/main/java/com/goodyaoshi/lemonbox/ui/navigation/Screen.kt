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
    data object Meal : Screen("meal")
    data object RecipeLibrary : Screen("recipe-library")
    data object Category : Screen("category")
    data object Profile : Screen("profile")
    data object Settings : Screen("settings")
    data object LanSync : Screen("lan-sync")
    data object ToBuy : Screen("to-buy")
    data object Trash : Screen("trash")
    data object Expiry : Screen("expiry")
    data object Reminders : Screen("reminders")
    data object Anniversaries : Screen("anniversaries")
    data object AnniversaryEdit : Screen("anniversary-edit?anniversaryId={anniversaryId}") {
        fun createRoute(anniversaryId: Long? = null): String =
            "anniversary-edit?anniversaryId=${anniversaryId ?: -1L}"
    }

    // ---- 记账模块 ----
    data object Ledger : Screen("ledger")
    data object LedgerStats : Screen("ledger-stats")
    data object LedgerBudget : Screen("ledger-budget")
    data object LedgerAssets : Screen("ledger-assets")
    data object LedgerCategoryManage : Screen("ledger-categories?kind={kind}") {
        fun createRoute(kind: Int): String = "ledger-categories?kind=$kind"
    }

    /**
     * 统计分类明细：某账期某分类的账单流水。
     * kind 取 [com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory] 的 KIND_ 值；
     * categoryId 为 null 时传 -1，对应统计页的「未分类」；monthKey 为 yyyy-MM。
     */
    data object LedgerCategoryDetail : Screen(
        "ledger-category-detail?kind={kind}&categoryId={categoryId}&monthKey={monthKey}"
    ) {
        fun createRoute(kind: Int, categoryId: Long?, monthKey: String): String =
            "ledger-category-detail?kind=$kind&categoryId=${categoryId ?: -1L}&monthKey=$monthKey"
    }

    /**
     * 记一笔 / 编辑账单。recordId > 0 为编辑既有账单；itemId > 0 表示由家当
     * 「已买到」联动进入，amount（分）与 remark 用于预填。
     */
    data object RecordEdit : Screen(
        "record-edit?recordId={recordId}&itemId={itemId}&amount={amount}&remark={remark}"
    ) {
        fun createRoute(
            recordId: Long? = null,
            itemId: Long? = null,
            amount: Long? = null,
            remark: String? = null
        ): String {
            return "record-edit?recordId=${recordId ?: -1L}&itemId=${itemId ?: -1L}" +
                "&amount=${amount ?: -1L}&remark=${Uri.encode(remark.orEmpty())}"
        }
    }

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
