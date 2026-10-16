package com.goodyaoshi.lemonbox.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Camera : Screen("camera")
    data object Save : Screen("save?imageUri={imageUri}&imageUris={imageUris}&mode={mode}&barcode={barcode}&name={name}") {
        fun createRoute(
            imageUri: String? = null,
            imageUris: List<String> = emptyList(),
            mode: String = "append",
            barcode: String? = null,
            name: String? = null
        ): String {
            val encodedUri = imageUri?.let { Uri.encode(it) } ?: ""
            val encodedUris = imageUris.joinToString(",") { Uri.encode(it) }
            val encodedBarcode = barcode?.let { Uri.encode(it) } ?: ""
            val encodedName = name?.let { Uri.encode(it) } ?: ""
            return "save?imageUri=$encodedUri&imageUris=$encodedUris&mode=$mode" +
                "&barcode=$encodedBarcode&name=$encodedName"
        }
    }

    data object Scan : Screen("scan?barcode={barcode}") {
        fun createRoute(barcode: String): String = "scan?barcode=${Uri.encode(barcode)}"
    }

    /**
     * 物品详情。[scope] 承载「从哪一类列表点进来」的翻页范围；
     * [filterJson] / [query] 则是从家当页点入时携带的**完整**筛选条件。
     *
     * 为什么要多这两个参数：家当的筛选是「状态维度 + 分类 + 位置 + 关键词」在内存里
     * 组合出来的，无法压缩成少数几个 scope 常量。只带 scope 会让详情页左右翻页时
     * 翻出筛选之外的物品。带上原始条件后，详情页能复现与来源列表完全一致的集合
     * （见 DetailViewModel 的 entryFilter 分支）。
     */
    data object Detail : Screen("detail/{itemId}/{scope}?filterJson={filterJson}&query={query}") {
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

        fun createRoute(
            itemId: Long,
            scope: String = ScopeAll,
            filterJson: String? = null,
            query: String? = null
        ): String {
            val encodedFilter = filterJson?.let { Uri.encode(it) } ?: ""
            val encodedQuery = query?.let { Uri.encode(it) } ?: ""
            return "detail/$itemId/$scope?filterJson=$encodedFilter&query=$encodedQuery"
        }
    }

    /**
     * 底栏「家当」标签的独立路由（I7）：标签、图标与路由三者语义一致。
     * 搜索与筛选已由家当页本身承载，不再单独保留「搜索」路由（IA-5：清理不可达目的地）。
     */
    data object Household : Screen("household") {
        /**
         * 首页搜索回车带词跳转（IA-2）：家当 Tab 支持携带初始关键词。
         * query 为可选参数，底栏 Tab 仍导航不带参的 [route]，两者都能匹配同一目的地。
         */
        /**
         * 带词跳入家当：query 为空时不携带可选参数，返回的串与底栏 Tab 用的 [route] 完全一致，
         * 使 launchSingleTop 能命中同一目的地，不会与 Tab 根并存两份家当页。
         */
        fun createRoute(query: String): String =
            if (query.isBlank()) route else "household?query=${Uri.encode(query)}"
    }

    data object Meal : Screen("meal")
    data object RecipeLibrary : Screen("recipe-library")
    data object Category : Screen("category")
    data object Profile : Screen("profile")
    data object Settings : Screen("settings")
    data object LanSync : Screen("lan-sync")

    /** 数据中心（IA-1）：设备同步与数据导出的统一二级页，从「我的」拆出减轻过载。 */
    data object DataCenter : Screen("data-center")

    /** 通知可靠性（SET1）：电池白名单、自启动与常驻保活的独立二级页。 */
    data object NotificationReliability : Screen("notification-reliability")

    /** 云存储（F20）：把家当图片上传到 S3 兼容对象存储，仅正式包支持。 */
    data object ImageCloud : Screen("image-cloud")

    data object ToBuy : Screen("to-buy")
    data object Trash : Screen("trash")
    data object Expiry : Screen("expiry")
    data object Reminders : Screen("reminders")
    data object Anniversaries : Screen("anniversaries")
    data object Ocr : Screen("ocr")

    /** 常买物品预测（F08）：按消耗速度推算补货时机。 */
    data object RestockForecast : Screen("restock-forecast")

    /** 时间胶囊（F14）：写给未来某天的话，到期才能打开。 */
    data object TimeCapsule : Screen("time-capsule")

    /** 睡前氛围（F15）：夜里的一处安静界面 + 就寝提醒。 */
    data object Sleep : Screen("sleep")

    /** 旅行打包清单列表（F16）：按行程管理要带的东西。 */
    data object PackingList : Screen("packing-list")

    /** 单个行程的打包清单（F16）。 */
    data object PackingTrip : Screen("packing-trip?tripId={tripId}") {
        fun createRoute(tripId: Long): String = "packing-trip?tripId=$tripId"
    }

    data object AnniversaryEdit : Screen("anniversary-edit?anniversaryId={anniversaryId}") {
        fun createRoute(anniversaryId: Long? = null): String =
            "anniversary-edit?anniversaryId=${anniversaryId ?: -1L}"
    }

    // ---- 记账模块 ----
    data object Ledger : Screen("ledger")
    data object LedgerStats : Screen("ledger-stats")
    data object LedgerBudget : Screen("ledger-budget")
    data object LedgerAssets : Screen("ledger-assets")
    /** 记账模板与周期记账规则管理（F05）。 */
    data object LedgerPlan : Screen("ledger-plan")

    /** 收支趋势图（F10）：近 6 个月折线与月度对比柱状，Canvas 自绘。 */
    data object LedgerTrend : Screen("ledger-trend")

    /** 年度回顾（F09）：某年的收支汇总、最高项与同比。 */
    data object LedgerAnnualReview : Screen("ledger-annual-review")

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
            val encodedUri = imageUri?.let { Uri.encode(it) } ?: ""
            val encodedUris = imageUris.joinToString(",") { Uri.encode(it) }
            return "edit/$itemId?imageUri=$encodedUri&imageUris=$encodedUris&mode=$mode"
        }
    }
}
