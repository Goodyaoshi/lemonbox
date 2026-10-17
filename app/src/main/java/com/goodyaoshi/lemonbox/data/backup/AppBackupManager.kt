package com.goodyaoshi.lemonbox.data.backup

import android.content.Context
import android.net.Uri
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncManager
import com.goodyaoshi.lemonbox.data.local.database.AppDatabase
import com.goodyaoshi.lemonbox.data.local.database.SeedDataProvider
import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.repository.AnniversaryRepository
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.data.repository.LedgerPlanningRepository
import com.goodyaoshi.lemonbox.data.repository.LocationRepository
import com.goodyaoshi.lemonbox.data.repository.PackingRepository
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import com.goodyaoshi.lemonbox.data.repository.TimeCapsuleRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath
import com.goodyaoshi.lemonbox.util.RecurringClock
import com.goodyaoshi.lemonbox.util.TodoReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 合并导入的结果统计，用于向用户展示本次到底发生了什么。 */
@Serializable
data class BackupMergeResult(
    val itemAdded: Int,
    val itemUpdated: Int,
    val itemKept: Int,
    val categoryAdded: Int,
    val categoryUpdated: Int,
    val locationAdded: Int,
    val locationUpdated: Int,
    val fromLegacyBackup: Boolean,
    /** 记账模块四张表（账单/分类/账户/预算）合计的新增数，v4 起导出。 */
    val ledgerAdded: Int = 0,
    val ledgerUpdated: Int = 0,
    /** 待办提醒，v5 起导出。 */
    val reminderAdded: Int = 0,
    val reminderUpdated: Int = 0,
    /** 周年纪念，v5 起导出。 */
    val anniversaryAdded: Int = 0,
    val anniversaryUpdated: Int = 0,
    /** 记账模板与周期记账规则，v10 起导出。 */
    val ledgerTemplateAdded: Int = 0,
    val ledgerTemplateUpdated: Int = 0,
    val recurringRuleAdded: Int = 0,
    val recurringRuleUpdated: Int = 0,
    /** 用户自建菜谱新增数，v5 起导出。 */
    val recipeAdded: Int = 0,
    /** 周菜单/已做日期/自定义状态/提醒设置是否有应用。 */
    val prefsApplied: Boolean = false,
    /** 变更列表里被用户跳过（未勾选）的条数；同步据此决定水位线是否前进。 */
    val userSkipped: Int = 0
) {
    val hasChanges: Boolean
        get() = itemAdded + itemUpdated + categoryAdded + categoryUpdated +
            locationAdded + locationUpdated + ledgerAdded + ledgerUpdated +
            reminderAdded + reminderUpdated + anniversaryAdded + anniversaryUpdated +
            ledgerTemplateAdded + ledgerTemplateUpdated +
            recurringRuleAdded + recurringRuleUpdated +
            recipeAdded > 0 || prefsApplied

    /** 全实体合计的新增条数（同步历史用）。 */
    val totalAdded: Int
        get() = itemAdded + categoryAdded + locationAdded + ledgerAdded +
            reminderAdded + anniversaryAdded + recipeAdded +
            ledgerTemplateAdded + recurringRuleAdded

    /** 全实体合计的更新条数（同步历史用）。 */
    val totalUpdated: Int
        get() = itemUpdated + categoryUpdated + locationUpdated + ledgerUpdated +
            reminderUpdated + anniversaryUpdated +
            ledgerTemplateUpdated + recurringRuleUpdated
}

/** 把合并结果转成一句用户可读的说明；手动导入与局域网同步共用。 */
fun BackupMergeResult.toUserMessage(): String {
    if (!hasChanges) {
        return "合并完成：双方数据已是最新，无需改动"
    }
    val parts = buildList {
        if (itemAdded > 0) add("新增物品 $itemAdded 件")
        if (itemUpdated > 0) add("更新物品 $itemUpdated 件")
        if (categoryAdded > 0) add("新增分类 $categoryAdded 个")
        if (categoryUpdated > 0) add("更新分类 $categoryUpdated 个")
        if (locationAdded > 0) add("新增位置 $locationAdded 个")
        if (locationUpdated > 0) add("更新位置 $locationUpdated 个")
        if (ledgerAdded > 0) add("新增账目 $ledgerAdded 条")
        if (ledgerUpdated > 0) add("更新账目 $ledgerUpdated 条")
        if (reminderAdded > 0) add("新增提醒 $reminderAdded 条")
        if (reminderUpdated > 0) add("更新提醒 $reminderUpdated 条")
        if (anniversaryAdded > 0) add("新增纪念日 $anniversaryAdded 个")
        if (anniversaryUpdated > 0) add("更新纪念日 $anniversaryUpdated 个")
        if (ledgerTemplateAdded > 0) add("新增记账模板 $ledgerTemplateAdded 个")
        if (ledgerTemplateUpdated > 0) add("更新记账模板 $ledgerTemplateUpdated 个")
        if (recurringRuleAdded > 0) add("新增周期记账 $recurringRuleAdded 条")
        if (recurringRuleUpdated > 0) add("更新周期记账 $recurringRuleUpdated 条")
        if (recipeAdded > 0) add("新增菜谱 $recipeAdded 道")
        if (prefsApplied) add("同步了菜单与提醒设置")
        if (userSkipped > 0) add("按你的勾选跳过 $userSkipped 条")
        if (itemKept > 0) add("保留本地 $itemKept 件")
    }
    return "合并完成：" + parts.joinToString("、")
}

/** 一次备份导出的产物：临时 ZIP 文件 + 生成时刻（增量同步以 exportedAt 作为对方的水位线）。 */
data class BackupArchive(
    val file: File,
    val exportedAt: Long,
    /**
     * 增量导出时是否什么都没变。云同步据此跳过上传——否则每次打开 App 都会
     * 往存储上堆一个内容相同的对象，白白占用空间与操作次数。
     * 全量导出恒为 false（发问方另有权衡）。
     */
    val isEmpty: Boolean = false
)

/**
 * 同步范围：让用户自己决定这次把对方数据合并进来时做到什么程度。
 *  - [ALL] 全部同步：新增、修改、删除都接受；
 *  - [ADD_ONLY] 只同步新增：只在本地没有对应记录时插入，忽略对方的修改与删除；
 *  - [NO_DELETE] 同步但不删除：接受新增与修改，忽略对方的删除墓碑。
 */
enum class SyncScope(val label: String) {
    ALL("全部同步"),
    ADD_ONLY("只同步新增"),
    NO_DELETE("同步但不删除")
}

/** 变更种类：对方带来的这条记录是新增、修改，还是删除（软删墓碑）。 */
enum class SyncChangeKind(val label: String) {
    ADDED("新增"),
    MODIFIED("修改"),
    DELETED("删除")
}

/** 变更所属的实体域：变更列表按它分组展示。 */
enum class SyncEntityKind(val label: String) {
    ITEM("家当"),
    CATEGORY("分类"),
    LOCATION("位置"),
    LEDGER_RECORD("账目"),
    LEDGER_CATEGORY("记账分类"),
    LEDGER_ASSET("账户"),
    LEDGER_BUDGET("预算"),
    ANNIVERSARY("纪念日"),
    REMINDER("待办提醒"),
    TIME_CAPSULE("时间胶囊"),
    PACKING_TRIP("打包行程"),
    PACKING_ITEM("打包条目"),
    LEDGER_TEMPLATE("记账模板"),
    LEDGER_RECURRING_RULE("周期记账")
}

/** 一个字段的本地值 vs 对方值对照（S3）：修改条目展开详情时逐行展示。 */
@Serializable
data class FieldDiff(
    /** 字段原始名（JSON 键）。 */
    val field: String,
    /** 字段的中文标签。 */
    val label: String,
    val localValue: String,
    val remoteValue: String
)

/** 一条待同步变更：合并前逐条列出，用户勾选后按 [key] 白名单落地。 */
@Serializable
data class SyncChange(
    val entity: SyncEntityKind,
    val kind: SyncChangeKind,
    /** 对齐键（syncId 或旧版数字 id），勾选结果按它传回合并。 */
    val key: String,
    val title: String,
    val subtitle: String? = null,
    /** 字段级差异，仅修改条目有值（S3）。 */
    val diffs: List<FieldDiff> = emptyList(),
    /**
     * 真冲突（S4）：双方都在上次同步后改过这条记录。勾选=采用对方，不勾=保留本机
     * （下次同步还会再问一次），由用户逐条裁决，不再被 LWW 静默吃掉。
     */
    val conflict: Boolean = false
)

/**
 * 合并预览：逐条变更列表 + 汇总计数。局域网同步与手动导入共用，
 * 替代旧的「只有三个数字」的 [BackupPreview]（S1：变更可见性从汇总升级到逐条）。
 */
@Serializable
data class BackupChanges(
    val changes: List<SyncChange> = emptyList()
) {
    val added: Int get() = changes.count { it.kind == SyncChangeKind.ADDED }
    val updated: Int get() = changes.count { it.kind == SyncChangeKind.MODIFIED }
    val deleted: Int get() = changes.count { it.kind == SyncChangeKind.DELETED }
    val conflicts: Int get() = changes.count { it.conflict }
    val hasChanges: Boolean get() = changes.isNotEmpty()
}

/** 预览用的行视图：只保留计数需要的最小字段。 */
private data class PreviewRow(
    val key: String,
    val updatedAt: Long?,
    val deletedAt: Long?,
    val protected: Boolean = false
)

/**
 * 备份导出与局域网合并导入的入口。
 *
 * 自身只保留「导出 / 导入编排 + ZIP 打包解包」（F9）：合并策略见 `BackupMergeStrategy.kt`，
 * 快照数据结构见 `BackupSnapshots.kt`。这样单文件不再逼近千行，
 * 改动合并规则时也不必在装配代码里翻找。
 */
@Singleton
class AppBackupManager @Inject constructor(
    @ApplicationContext internal val context: Context,
    internal val itemRepository: ItemRepository,
    internal val categoryRepository: CategoryRepository,
    internal val locationRepository: LocationRepository,
    internal val ledgerRepository: LedgerRepository,
    /** 记账模板与周期记账规则（F05）：v10 起纳入备份/同步。 */
    internal val ledgerPlanningRepository: LedgerPlanningRepository,
    internal val anniversaryRepository: AnniversaryRepository,
    internal val reminderRepository: ReminderRepository,
    internal val timeCapsuleRepository: TimeCapsuleRepository,
    internal val packingRepository: PackingRepository,
    private val todoReminderScheduler: TodoReminderScheduler,
    private val appPreferences: AppPreferences,
    internal val appDatabase: AppDatabase,
    /**
     * 图片云端同步：导入完成后用它把本机图片补传一次（见 [importBackup]）。
     * 依赖方向安全——同步器不依赖本类，无循环。
     */
    private val imageCloudSync: ImageCloudSyncManager
) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * 补种受保护的内置分类与位置（食品食材子树、零食、冰箱）。可重复执行：
     * 精确命中则补保护位并清墓碑，孤立项重新挂回父级，缺失项插入兜底。
     */
    private suspend fun seedProtectedData() {
        withContext(Dispatchers.IO) {
            SeedDataProvider.ensureProtectedSeedData(appDatabase.openHelper.writableDatabase)
        }
    }

    /**
     * 恢复内置分类：手动入口用。把内置食材/零食分类补齐、复活被误删项、修复孤立父级，
     * 再统一去重收敛，让「同步后少了的食材分类」能一键找回来。
     */
    suspend fun restoreBuiltInData() {
        seedProtectedData()
        categoryRepository.deduplicateCategories()
        locationRepository.deduplicateLocations()
    }

    /**
     * 预演「恢复内置分类」会新增哪些分类（G-6）：与 [restoreBuiltInData] 的补种同口径——
     * 内置种子分类按名称与现有分类全量快照（含软删墓碑，补种命中墓碑只会复活而非插入）
     * 去重，剩下的就是会真正插入的名单。预览据此让用户先看清再确认，不往整理过的
     * 分类树里硬塞回一堆不用的分类。
     */
    suspend fun previewRestoreBuiltIn(): List<String> {
        val existingNames = categoryRepository.getAllCategoriesSnapshot()
            .map { it.name }
            .toSet()
        return SeedDataProvider.protectedSeedCategoryNames.filterNot { it in existingNames }
    }

    /**
     * 合并导入：不再整库替换。以 syncId 对齐同一条记录，按 updatedAt「较新者胜」，
     * 删除通过软删除时间戳传递。这样双方各自编辑后互相导入不会丢数据。
     *
     * [scope] 允许调用方限定本次合并的范围（全部 / 只新增 / 不删除）：
     * 在合并前先把远端快照按范围过滤，因此无需改动各实体的合并规则。
     * [keepKeys] 是逐条模式的白名单（来自变更列表的勾选结果）：非 null 时只落地
     * 这些对齐键对应的记录，范围枚举不再参与——用户勾了删除墓碑就执行删除，没勾就不动。
     */
    suspend fun importBackup(
        sourceUri: Uri,
        scope: SyncScope = SyncScope.ALL,
        keepKeys: Set<String>? = null
    ): BackupMergeResult {
        val workingDir = File(context.cacheDir, "backup-import-${System.currentTimeMillis()}.apply").apply {
            mkdirs()
        }
        val backupZip = File(workingDir, "backup.zip")
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                backupZip.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取备份文件")

            unzip(backupZip, workingDir)
            val raw = readVerifiedPayload(workingDir)
            val useSyncIds = raw.syncVersion >= SYNC_ID_VERSION
            val backup = filterByScope(raw, scope, useSyncIds, keepKeys)

            val categoryOutcome = mergeCategories(backup.categories, useSyncIds)
            val locationOutcome = mergeLocations(backup.locations, useSyncIds)
            val itemOutcome = mergeItems(
                snapshots = backup.items,
                useSyncIds = useSyncIds,
                categoryIdByKey = categoryOutcome.idByKey,
                locationIdByKey = locationOutcome.idByKey
            )
            val ledgerCategoryOutcome = mergeLedgerCategories(backup.ledgerCategories, useSyncIds)
            val ledgerAssetOutcome = mergeLedgerAssets(backup.ledgerAssets, useSyncIds)
            val ledgerRecordOutcome = mergeLedgerRecords(
                snapshots = backup.ledgerRecords,
                useSyncIds = useSyncIds,
                categoryIdByKey = ledgerCategoryOutcome.idByKey,
                assetIdByKey = ledgerAssetOutcome.idByKey,
                itemKeyById = itemOutcome.idByKey
            )
            val ledgerBudgetOutcome = mergeLedgerBudgets(
                snapshots = backup.ledgerBudgets,
                useSyncIds = useSyncIds,
                categoryIdByKey = ledgerCategoryOutcome.idByKey
            )
            val ledgerAdded = ledgerCategoryOutcome.added + ledgerAssetOutcome.added +
                ledgerRecordOutcome.added + ledgerBudgetOutcome.added
            val ledgerUpdated = ledgerCategoryOutcome.updated + ledgerAssetOutcome.updated +
                ledgerRecordOutcome.updated + ledgerBudgetOutcome.updated

            // v5 起：纪念日与待办提醒进备份（含软删墓碑），LWW 合并。
            val anniversaryOutcome = mergeAnniversaries(backup.anniversaries, useSyncIds)
            val reminderOutcome = mergeReminders(backup.reminders, useSyncIds, itemOutcome.idByKey)
            // v9 起：时间胶囊与打包清单进备份。行程先合、拿到本地 id 映射后再合条目，
            // 因为条目的 tripId 是本地主键、跨设备对不上，必须靠行程 syncId 重新解析。
            val capsuleOutcome = mergeTimeCapsules(backup.timeCapsules, useSyncIds)
            val tripOutcome = mergePackingTrips(backup.packingTrips, useSyncIds)
            val packingItemOutcome = mergePackingItems(
                snapshots = backup.packingItems,
                useSyncIds = useSyncIds,
                tripIdByKey = tripOutcome.idByKey
            )
            // v10 起：记账模板与周期记账规则进备份。两者都引用记账分类与账户，
            // 复用上面已算好的 id 映射（模板/规则的 categoryId/assetId 是本地主键）。
            val ledgerTemplateOutcome = mergeLedgerTemplates(
                snapshots = backup.ledgerTemplates,
                useSyncIds = useSyncIds,
                categoryIdByKey = ledgerCategoryOutcome.idByKey,
                assetIdByKey = ledgerAssetOutcome.idByKey
            )
            val recurringRuleOutcome = mergeLedgerRecurringRules(
                snapshots = backup.ledgerRecurringRules,
                useSyncIds = useSyncIds,
                categoryIdByKey = ledgerCategoryOutcome.idByKey,
                assetIdByKey = ledgerAssetOutcome.idByKey
            )
            // v5 起：菜谱/周菜单/自定义状态/提醒设置等偏好内容。
            val prefs = backup.preferences
            val recipeAdded = prefs?.let { appPreferences.applySyncedRecipes(it.recipes) } ?: 0
            var prefsApplied = false
            if (prefs != null) {
                // 各项先独立求值再合并，避免用 || 短路导致后面的偏好被跳过。
                val weeklyMenuApplied = appPreferences.applySyncedWeeklyMenu(prefs.weeklyMenu) > 0
                val cookedApplied = appPreferences.applySyncedCookedDates(prefs.cookedMenuDates) > 0
                val statusesApplied = appPreferences.applySyncedCustomStatuses(
                    prefs.customStatuses.mapNotNull { it.toOption() }
                ) > 0
                val reminderApplied = appPreferences.applySyncedReminderSettings(
                    reminderLadder = prefs.reminderLadder,
                    reminderTimes = prefs.reminderTimes,
                    customReminderTimes = prefs.customReminderTimes,
                    mealPrepDayShift = prefs.mealPrepDayShift,
                    mealPrepFireTime = prefs.mealPrepFireTime
                )
                val togetherApplied = appPreferences.applySyncedTogetherStartDate(
                    prefs.togetherStartDate, prefs.togetherStartDateUpdatedAt
                ) || appPreferences.applySyncedTogetherLabelName(prefs.togetherLabelName)
                // v12 起：昵称、记账月起始日、预算提醒开关、菜单天数也随偏好同步。
                val miscApplied = appPreferences.applySyncedNickname(prefs.nickname) or
                    appPreferences.applySyncedLedgerMonthStartDay(prefs.ledgerMonthStartDay) or
                    appPreferences.applySyncedBudgetReminderEnabled(prefs.budgetReminderEnabled) or
                    appPreferences.applySyncedMealPlanDays(prefs.mealPlanDays)
                prefsApplied = weeklyMenuApplied || cookedApplied || statusesApplied ||
                    reminderApplied || togetherApplied || miscApplied
            }
            if (reminderOutcome.added + reminderOutcome.updated > 0) {
                todoReminderScheduler.reschedule()
            }

            // 内置受保护分类/位置（食材子树、零食、冰箱）是菜谱与「今天吃什么」的基础规则：
            // 无论本次合并怎么改写，合并后都无条件补齐一次，保证丢失的内置分类一定能回来，
            // 随后再按路径统一去重收敛重复项。
            seedProtectedData()
            // 两台设备各自种子化的默认分类/位置/记账分类/账户 syncId 不同，按 syncId 对齐后
            // 会各留一份，合并完统一按「同名同父级路径」收敛，避免选择器出现多个一样的项。
            locationRepository.deduplicateLocations()
            categoryRepository.deduplicateCategories()
            ledgerRepository.deduplicateCategories()
            ledgerRepository.deduplicateAssets()
            anniversaryRepository.deduplicateAnniversaries()

            // 导入进来的图片槽位是内容哈希，本机可能还没有对应文件（图片只在云端）。
            // 交给云端同步补传一次：它按内容哈希定键、已存在的跳过，并在云端确认后
            // 清理本地副本。放到返回前触发，三个调用方（手动导入、局域网同步的两端）
            // 都自动覆盖，不必各自记得补一次。
            // 未配置云端时它内部静默跳过，不影响导入本身。
            imageCloudSync.syncAll()

            return BackupMergeResult(
                itemAdded = itemOutcome.added,
                itemUpdated = itemOutcome.updated,
                itemKept = itemOutcome.kept,
                categoryAdded = categoryOutcome.added,
                categoryUpdated = categoryOutcome.updated,
                locationAdded = locationOutcome.added,
                locationUpdated = locationOutcome.updated,
                fromLegacyBackup = !useSyncIds,
                ledgerAdded = ledgerAdded,
                ledgerUpdated = ledgerUpdated,
                reminderAdded = reminderOutcome.added,
                reminderUpdated = reminderOutcome.updated,
                anniversaryAdded = anniversaryOutcome.added,
                anniversaryUpdated = anniversaryOutcome.updated,
                ledgerTemplateAdded = ledgerTemplateOutcome.added,
                ledgerTemplateUpdated = ledgerTemplateOutcome.updated,
                recurringRuleAdded = recurringRuleOutcome.added,
                recurringRuleUpdated = recurringRuleOutcome.updated,
                recipeAdded = recipeAdded,
                prefsApplied = prefsApplied
            )
        } finally {
            workingDir.deleteRecursively()
        }
    }

    /**
     * 预演合并：逐条列出「对方数据合并到本机」将带来的新增 / 修改 / 删除，不改动数据库。
     * 局域网同步与手动导入共用：用户据此逐条勾选要落地的内容。
     *
     * [syncedAfter] 是本端上次同步完成的时间戳（局域网同步路径传入，手动导入传 null）：
     * 本地与对方都在它之后改过的条目会被标记为真冲突，由用户裁决而非 LWW 静默覆盖（S4）。
     */
    suspend fun previewBackup(sourceUri: Uri, syncedAfter: Long? = null): BackupChanges {
        val workingDir = File(context.cacheDir, "backup-preview-${System.currentTimeMillis()}.apply").apply {
            mkdirs()
        }
        val backupZip = File(workingDir, "backup.zip")
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                backupZip.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取备份文件")

            unzip(backupZip, workingDir)
            val backup = readVerifiedPayload(workingDir)
            return computeChanges(
                backup = backup,
                useSyncIds = backup.syncVersion >= SYNC_ID_VERSION,
                syncedAfter = syncedAfter
            )
        } finally {
            workingDir.deleteRecursively()
        }
    }

    /**
     * 按范围过滤远端快照：只新增会在合并前丢弃已存在记录与墓碑，不删除只丢弃墓碑。
     * [keepKeys] 非 null 时走逐条模式：白名单就是最终裁决，直接按对齐键过滤。
     */
    private suspend fun filterByScope(
        backup: AppBackupPayload,
        scope: SyncScope,
        useSyncIds: Boolean,
        keepKeys: Set<String>? = null
    ): AppBackupPayload {
        if (keepKeys != null) {
            // 白名单键带实体前缀：旧备份按数字 id 对齐时不同实体可能出现相同的裸键（如 "id:3"），
            // 前缀保证勾选互不串扰。
            fun qualified(entity: SyncEntityKind, syncId: String?, id: Long): String =
                "${entity.name}:${keyOf(syncId, id, useSyncIds)}"
            return backup.copy(
                categories = backup.categories.filter { qualified(SyncEntityKind.CATEGORY, it.syncId, it.id) in keepKeys },
                locations = backup.locations.filter { qualified(SyncEntityKind.LOCATION, it.syncId, it.id) in keepKeys },
                items = backup.items.filter { qualified(SyncEntityKind.ITEM, it.syncId, it.id) in keepKeys },
                ledgerAssets = backup.ledgerAssets.filter { qualified(SyncEntityKind.LEDGER_ASSET, it.syncId, it.id) in keepKeys },
                ledgerCategories = backup.ledgerCategories.filter { qualified(SyncEntityKind.LEDGER_CATEGORY, it.syncId, it.id) in keepKeys },
                ledgerRecords = backup.ledgerRecords.filter { qualified(SyncEntityKind.LEDGER_RECORD, it.syncId, it.id) in keepKeys },
                ledgerBudgets = backup.ledgerBudgets.filter { qualified(SyncEntityKind.LEDGER_BUDGET, it.syncId, it.id) in keepKeys },
                anniversaries = backup.anniversaries.filter { qualified(SyncEntityKind.ANNIVERSARY, it.syncId, it.id) in keepKeys },
                reminders = backup.reminders.filter { qualified(SyncEntityKind.REMINDER, it.syncId, it.id) in keepKeys },
                timeCapsules = backup.timeCapsules.filter {
                    qualified(SyncEntityKind.TIME_CAPSULE, it.syncId, it.id) in keepKeys
                },
                packingTrips = backup.packingTrips.filter {
                    qualified(SyncEntityKind.PACKING_TRIP, it.syncId, it.id) in keepKeys
                },
                packingItems = backup.packingItems.filter {
                    qualified(SyncEntityKind.PACKING_ITEM, it.syncId, it.id) in keepKeys
                },
                ledgerTemplates = backup.ledgerTemplates.filter {
                    qualified(SyncEntityKind.LEDGER_TEMPLATE, it.syncId, it.id) in keepKeys
                },
                ledgerRecurringRules = backup.ledgerRecurringRules.filter {
                    qualified(SyncEntityKind.LEDGER_RECURRING_RULE, it.syncId, it.id) in keepKeys
                }
            )
        }
        if (scope == SyncScope.ALL) return backup
        val categoryKeys = categoryRepository.getAllCategoriesSnapshot()
            .map { keyOf(it.syncId, it.id, useSyncIds) }.toSet()
        val locationKeys = locationRepository.getAllLocationsSnapshot()
            .map { keyOf(it.syncId, it.id, useSyncIds) }.toSet()
        val itemKeys = itemRepository.getAllItemsSnapshot()
            .map { keyOf(it.syncId, it.id, useSyncIds) }.toSet()
        val ledgerAssetKeys = ledgerRepository.getAllAssetsSnapshot()
            .map { keyOf(it.syncId, it.id, useSyncIds) }.toSet()
        val ledgerCategoryKeys = ledgerRepository.getAllCategoriesSnapshot()
            .map { keyOf(it.syncId, it.id, useSyncIds) }.toSet()
        val ledgerRecordKeys = ledgerRepository.getAllRecordsSnapshot()
            .map { keyOf(it.syncId, it.id, useSyncIds) }.toSet()
        val ledgerBudgetKeys = ledgerRepository.getAllBudgetsSnapshot()
            .map { keyOf(it.syncId, it.id, useSyncIds) }.toSet()
        val anniversaryKeys = anniversaryRepository.getAllSnapshot()
            .map { keyOf(it.syncId, it.id, useSyncIds) }.toSet()
        val reminderKeys = reminderRepository.getAllSnapshot()
            .map { keyOf(it.syncId, it.id, useSyncIds) }.toSet()
        val timeCapsuleKeys = timeCapsuleRepository.getAllSnapshot()
            .map { keyOf(it.syncId, it.id, useSyncIds) }.toSet()
        val packingTripKeys = packingRepository.getAllTripsSnapshot()
            .map { keyOf(it.syncId, it.id, useSyncIds) }.toSet()
        val packingItemKeys = packingRepository.getAllItemsSnapshot()
            .map { keyOf(it.syncId, it.id, useSyncIds) }.toSet()
        val ledgerTemplateKeys = ledgerPlanningRepository.getAllTemplatesSnapshot()
            .map { keyOf(it.syncId, it.id, useSyncIds) }.toSet()
        val recurringRuleKeys = ledgerPlanningRepository.getAllRulesSnapshot()
            .map { keyOf(it.syncId, it.id, useSyncIds) }.toSet()

        fun keep(key: String, deletedAt: Long?, localKeys: Set<String>): Boolean = when (scope) {
            SyncScope.ADD_ONLY -> key !in localKeys && deletedAt == null
            SyncScope.NO_DELETE -> deletedAt == null
            SyncScope.ALL -> true
        }

        return backup.copy(
            categories = backup.categories.filter {
                keep(keyOf(it.syncId, it.id, useSyncIds), it.deletedAt, categoryKeys)
            },
            locations = backup.locations.filter {
                keep(keyOf(it.syncId, it.id, useSyncIds), it.deletedAt, locationKeys)
            },
            items = backup.items.filter {
                keep(keyOf(it.syncId, it.id, useSyncIds), it.deletedAt, itemKeys)
            },
            ledgerAssets = backup.ledgerAssets.filter {
                keep(keyOf(it.syncId, it.id, useSyncIds), it.deletedAt, ledgerAssetKeys)
            },
            ledgerCategories = backup.ledgerCategories.filter {
                keep(keyOf(it.syncId, it.id, useSyncIds), it.deletedAt, ledgerCategoryKeys)
            },
            ledgerRecords = backup.ledgerRecords.filter {
                keep(keyOf(it.syncId, it.id, useSyncIds), it.deletedAt, ledgerRecordKeys)
            },
            ledgerBudgets = backup.ledgerBudgets.filter {
                keep(keyOf(it.syncId, it.id, useSyncIds), it.deletedAt, ledgerBudgetKeys)
            },
            anniversaries = backup.anniversaries.filter {
                keep(keyOf(it.syncId, it.id, useSyncIds), it.deletedAt, anniversaryKeys)
            },
            reminders = backup.reminders.filter {
                keep(keyOf(it.syncId, it.id, useSyncIds), it.deletedAt, reminderKeys)
            },
            // v9/v10 新增的实体此前漏在 scope 分支外：选「只同步新增」或「同步但不删除」时
            // 它们会被整体丢弃，等于静默不同步。这里补齐，与 keepKeys 分支保持同一份实体清单。
            timeCapsules = backup.timeCapsules.filter {
                keep(keyOf(it.syncId, it.id, useSyncIds), it.deletedAt, timeCapsuleKeys)
            },
            packingTrips = backup.packingTrips.filter {
                keep(keyOf(it.syncId, it.id, useSyncIds), it.deletedAt, packingTripKeys)
            },
            packingItems = backup.packingItems.filter {
                keep(keyOf(it.syncId, it.id, useSyncIds), it.deletedAt, packingItemKeys)
            },
            ledgerTemplates = backup.ledgerTemplates.filter {
                keep(keyOf(it.syncId, it.id, useSyncIds), it.deletedAt, ledgerTemplateKeys)
            },
            ledgerRecurringRules = backup.ledgerRecurringRules.filter {
                keep(keyOf(it.syncId, it.id, useSyncIds), it.deletedAt, recurringRuleKeys)
            }
        )
    }

    /**
     * 逐条预演合并：与合并策略同口径（受保护记录不改、远端较新才动），但不再只留计数——
     * 每条变更带上实体域、种类与标题，供用户在变更列表里逐条勾选（S1/S2）；
     * 修改条目额外生成字段级 diff（S3），双方都改过的条目标记为真冲突（S4）。
     */
    private suspend fun computeChanges(
        backup: AppBackupPayload,
        useSyncIds: Boolean,
        syncedAfter: Long? = null
    ): BackupChanges {
        val changes = mutableListOf<SyncChange>()

        val localItems = itemRepository.getAllItemsSnapshot()
        val localCategories = categoryRepository.getAllCategoriesSnapshot()
        val localLocations = locationRepository.getAllLocationsSnapshot()
        val localRecords = ledgerRepository.getAllRecordsSnapshot()
        val localLedgerCategories = ledgerRepository.getAllCategoriesSnapshot()
        val localAssets = ledgerRepository.getAllAssetsSnapshot()

        // 引用字段的 id→syncId 映射：把本地实体转成与对方快照同构的 JSON 时需要。
        val categorySyncIdById = localCategories.associate { it.id to it.syncId }
        val locationSyncIdById = localLocations.associate { it.id to it.syncId }
        val ledgerCategorySyncIdById = localLedgerCategories.associate { it.id to it.syncId }
        val ledgerAssetSyncIdById = localAssets.associate { it.id to it.syncId }
        val itemSyncIdById = localItems.associate { it.id to it.syncId }
        // 引用字段的 syncId→名字：diff 里的 UUID 值翻成用户认得出的名字。
        val referenceNames = buildMap {
            localCategories.forEach { it.syncId?.let { s -> put(s, it.name) } }
            localLocations.forEach { it.syncId?.let { s -> put(s, it.name) } }
            localLedgerCategories.forEach { it.syncId?.let { s -> put(s, it.name) } }
            localAssets.forEach { it.syncId?.let { s -> put(s, it.name) } }
            localItems.forEach { it.syncId?.let { s -> put(s, it.name) } }
        }

        changes += changeList(
            entity = SyncEntityKind.ITEM,
            snapshots = backup.items,
            local = previewRows(
                localItems, useSyncIds,
                { it.syncId }, { it.id }, { it.updatedAt ?: it.createdAt }, { it.deletedAt }
            ).associateBy { it.key },
            localSnapshots = snapshotJsonMap(localItems, useSyncIds, { it.syncId }, { it.id }) {
                ItemSnapshot.fromEntity(
                    item = it,
                    // 变更清单只比字段、不看图片（imageHashes 在 DIFF_EXCLUDED_FIELDS 里），
                    // 因此图片槽位传空，避免把无关信息反复读进来。
                    imageHashes = emptyList(),
                    categorySyncId = it.categoryId?.let { id -> categorySyncIdById[id] },
                    locationSyncId = it.locationId?.let { id -> locationSyncIdById[id] }
                )
            },
            referenceNames = referenceNames,
            syncedAfter = syncedAfter,
            useSyncIds = useSyncIds,
            syncId = { it.syncId },
            id = { it.id },
            updatedAt = { it.updatedAt ?: it.createdAt },
            deletedAt = { it.deletedAt },
            title = { it.name },
            subtitle = { "${it.quantity}${it.unit}" }
        )

        changes += changeList(
            entity = SyncEntityKind.CATEGORY,
            snapshots = backup.categories,
            local = previewRows(
                localCategories, useSyncIds,
                { it.syncId }, { it.id }, { it.updatedAt }, { it.deletedAt }, { it.isProtected }
            ).associateBy { it.key },
            localSnapshots = snapshotJsonMap(localCategories, useSyncIds, { it.syncId }, { it.id }) {
                CategorySnapshot.fromEntity(
                    category = it,
                    parentSyncId = it.parentId?.let { id -> categorySyncIdById[id] }
                )
            },
            referenceNames = referenceNames,
            syncedAfter = syncedAfter,
            useSyncIds = useSyncIds,
            syncId = { it.syncId },
            id = { it.id },
            updatedAt = { it.updatedAt },
            deletedAt = { it.deletedAt },
            title = { it.name }
        )

        changes += changeList(
            entity = SyncEntityKind.LOCATION,
            snapshots = backup.locations,
            local = previewRows(
                localLocations, useSyncIds,
                { it.syncId }, { it.id }, { it.updatedAt }, { it.deletedAt }, { it.isProtected }
            ).associateBy { it.key },
            localSnapshots = snapshotJsonMap(localLocations, useSyncIds, { it.syncId }, { it.id }) {
                LocationSnapshot.fromEntity(
                    location = it,
                    parentSyncId = it.parentId?.let { id -> locationSyncIdById[id] }
                )
            },
            referenceNames = referenceNames,
            syncedAfter = syncedAfter,
            useSyncIds = useSyncIds,
            syncId = { it.syncId },
            id = { it.id },
            updatedAt = { it.updatedAt },
            deletedAt = { it.deletedAt },
            title = { it.name }
        )

        changes += changeList(
            entity = SyncEntityKind.LEDGER_RECORD,
            snapshots = backup.ledgerRecords,
            local = previewRows(
                localRecords, useSyncIds,
                { it.syncId }, { it.id }, { it.updatedAt ?: it.createdAt }, { it.deletedAt }
            ).associateBy { it.key },
            localSnapshots = snapshotJsonMap(localRecords, useSyncIds, { it.syncId }, { it.id }) {
                LedgerRecordSnapshot.fromEntity(
                    record = it,
                    categorySyncId = it.categoryId?.let { id -> ledgerCategorySyncIdById[id] },
                    assetSyncId = it.assetId?.let { id -> ledgerAssetSyncIdById[id] },
                    targetAssetSyncId = it.targetAssetId?.let { id -> ledgerAssetSyncIdById[id] },
                    itemSyncId = it.itemId?.let { id -> itemSyncIdById[id] }
                )
            },
            referenceNames = referenceNames,
            syncedAfter = syncedAfter,
            useSyncIds = useSyncIds,
            syncId = { it.syncId },
            id = { it.id },
            updatedAt = { it.updatedAt ?: it.createdAt },
            deletedAt = { it.deletedAt },
            title = { it.remark.ifBlank { recordTypeLabel(it.type) } },
            subtitle = {
                "${DateUtil.formatCurrency(LedgerMath.centsToYuan(it.amount))} · ${DateUtil.formatDate(it.recordTime)}"
            },
            specialValue = { field, value ->
                if (field == "type") recordTypeLabel(value.toIntOrNull() ?: -1) else null
            }
        )

        changes += changeList(
            entity = SyncEntityKind.LEDGER_CATEGORY,
            snapshots = backup.ledgerCategories,
            local = previewRows(
                localLedgerCategories, useSyncIds,
                { it.syncId }, { it.id }, { it.updatedAt }, { it.deletedAt }, { it.isProtected }
            ).associateBy { it.key },
            localSnapshots = snapshotJsonMap(localLedgerCategories, useSyncIds, { it.syncId }, { it.id }) {
                LedgerCategorySnapshot.fromEntity(it)
            },
            referenceNames = referenceNames,
            syncedAfter = syncedAfter,
            useSyncIds = useSyncIds,
            syncId = { it.syncId },
            id = { it.id },
            updatedAt = { it.updatedAt },
            deletedAt = { it.deletedAt },
            title = { it.name }
        )

        changes += changeList(
            entity = SyncEntityKind.LEDGER_ASSET,
            snapshots = backup.ledgerAssets,
            local = previewRows(
                localAssets, useSyncIds,
                { it.syncId }, { it.id }, { it.updatedAt }, { it.deletedAt }
            ).associateBy { it.key },
            localSnapshots = snapshotJsonMap(localAssets, useSyncIds, { it.syncId }, { it.id }) {
                LedgerAssetSnapshot.fromEntity(it)
            },
            referenceNames = referenceNames,
            syncedAfter = syncedAfter,
            useSyncIds = useSyncIds,
            syncId = { it.syncId },
            id = { it.id },
            updatedAt = { it.updatedAt },
            deletedAt = { it.deletedAt },
            title = { it.name }
        )

        // 预算标题带上所属记账分类的名字（sync 与旧版数字 id 两种键都登记），方便认出「这是哪笔预算」。
        val ledgerCategoryNameByKey = buildMap {
            localLedgerCategories.forEach { category ->
                put(keyOf(category.syncId, category.id, useSyncIds), category.name)
                category.syncId.orBlank()?.let { syncId -> put("sync:$syncId", category.name) }
            }
        }
        changes += changeList(
            entity = SyncEntityKind.LEDGER_BUDGET,
            snapshots = backup.ledgerBudgets,
            local = previewRows(
                ledgerRepository.getAllBudgetsSnapshot(), useSyncIds,
                { it.syncId }, { it.id }, { it.updatedAt }, { it.deletedAt }
            ).associateBy { it.key },
            localSnapshots = snapshotJsonMap(
                ledgerRepository.getAllBudgetsSnapshot(), useSyncIds, { it.syncId }, { it.id }
            ) {
                LedgerBudgetSnapshot.fromEntity(
                    budget = it,
                    categorySyncId = it.categoryId?.let { id -> ledgerCategorySyncIdById[id] }
                )
            },
            referenceNames = referenceNames,
            syncedAfter = syncedAfter,
            useSyncIds = useSyncIds,
            syncId = { it.syncId },
            id = { it.id },
            updatedAt = { it.updatedAt },
            deletedAt = { it.deletedAt },
            title = { snapshot ->
                snapshot.categorySyncId?.let { ledgerCategoryNameByKey["sync:$it"] }
                    ?: snapshot.categoryId?.let { ledgerCategoryNameByKey["id:$it"] }
                    ?: "预算"
            },
            subtitle = { DateUtil.formatCurrency(LedgerMath.centsToYuan(it.amount)) }
        )

        changes += changeList(
            entity = SyncEntityKind.ANNIVERSARY,
            snapshots = backup.anniversaries,
            local = previewRows(
                anniversaryRepository.getAllSnapshot(), useSyncIds,
                { it.syncId }, { it.id }, { it.updatedAt ?: it.createdAt }, { it.deletedAt }
            ).associateBy { it.key },
            localSnapshots = snapshotJsonMap(
                anniversaryRepository.getAllSnapshot(), useSyncIds, { it.syncId }, { it.id }
            ) { AnniversarySnapshot.fromEntity(it) },
            referenceNames = referenceNames,
            syncedAfter = syncedAfter,
            useSyncIds = useSyncIds,
            syncId = { it.syncId },
            id = { it.id },
            updatedAt = { it.updatedAt ?: it.createdAt },
            deletedAt = { it.deletedAt },
            title = { it.name },
            subtitle = { it.date },
            specialValue = { field, value ->
                if (field == "type") {
                    when (value.toIntOrNull()) {
                        Anniversary.TYPE_COUNTDOWN -> "倒数日"
                        Anniversary.TYPE_COUNTUP -> "正数日"
                        Anniversary.TYPE_BIRTHDAY -> "生日"
                        else -> null
                    }
                } else {
                    null
                }
            }
        )

        changes += changeList(
            entity = SyncEntityKind.REMINDER,
            snapshots = backup.reminders,
            local = previewRows(
                reminderRepository.getAllSnapshot(), useSyncIds,
                { it.syncId }, { it.id }, { it.updatedAt ?: it.createdAt }, { it.deletedAt }
            ).associateBy { it.key },
            localSnapshots = snapshotJsonMap(
                reminderRepository.getAllSnapshot(), useSyncIds, { it.syncId }, { it.id }
            ) { reminder ->
                ReminderSnapshot.fromEntity(
                    reminder = reminder,
                    itemSyncId = reminder.linkedItemId?.let { itemSyncIdById[it] }
                )
            },
            referenceNames = referenceNames,
            syncedAfter = syncedAfter,
            useSyncIds = useSyncIds,
            syncId = { it.syncId },
            id = { it.id },
            updatedAt = { it.updatedAt ?: it.createdAt },
            deletedAt = { it.deletedAt },
            title = { it.title }
        )

        changes += changeList(
            entity = SyncEntityKind.TIME_CAPSULE,
            snapshots = backup.timeCapsules,
            local = previewRows(
                timeCapsuleRepository.getAllSnapshot(), useSyncIds,
                { it.syncId }, { it.id }, { it.updatedAt ?: it.createdAt }, { it.deletedAt }
            ).associateBy { it.key },
            localSnapshots = snapshotJsonMap(
                timeCapsuleRepository.getAllSnapshot(), useSyncIds, { it.syncId }, { it.id }
            ) { TimeCapsuleSnapshot.fromEntity(it) },
            referenceNames = referenceNames,
            syncedAfter = syncedAfter,
            useSyncIds = useSyncIds,
            syncId = { it.syncId },
            id = { it.id },
            updatedAt = { it.updatedAt ?: it.createdAt },
            deletedAt = { it.deletedAt },
            title = { it.title }
        )

        changes += changeList(
            entity = SyncEntityKind.PACKING_TRIP,
            snapshots = backup.packingTrips,
            local = previewRows(
                packingRepository.getAllTripsSnapshot(), useSyncIds,
                { it.syncId }, { it.id }, { it.updatedAt ?: it.createdAt }, { it.deletedAt }
            ).associateBy { it.key },
            localSnapshots = snapshotJsonMap(
                packingRepository.getAllTripsSnapshot(), useSyncIds, { it.syncId }, { it.id }
            ) { PackingTripSnapshot.fromEntity(it) },
            referenceNames = referenceNames,
            syncedAfter = syncedAfter,
            useSyncIds = useSyncIds,
            syncId = { it.syncId },
            id = { it.id },
            updatedAt = { it.updatedAt ?: it.createdAt },
            deletedAt = { it.deletedAt },
            title = { it.name },
            subtitle = { it.destination }
        )

        changes += changeList(
            entity = SyncEntityKind.PACKING_ITEM,
            snapshots = backup.packingItems,
            local = previewRows(
                packingRepository.getAllItemsSnapshot(), useSyncIds,
                { it.syncId }, { it.id }, { it.updatedAt ?: it.createdAt }, { it.deletedAt }
            ).associateBy { it.key },
            localSnapshots = snapshotJsonMap(
                packingRepository.getAllItemsSnapshot(), useSyncIds, { it.syncId }, { it.id }
            ) { item ->
                // 预览标题只需条目自身信息，归属行程名不参与判定，故 tripSyncId 可空。
                PackingItemSnapshot.fromEntity(item = item, tripSyncId = null)
            },
            referenceNames = referenceNames,
            syncedAfter = syncedAfter,
            useSyncIds = useSyncIds,
            syncId = { it.syncId },
            id = { it.id },
            updatedAt = { it.updatedAt ?: it.createdAt },
            deletedAt = { it.deletedAt },
            title = { it.name },
            subtitle = { it.category }
        )

        // 记账模板（v10）：标题用模板名，副标题用金额与类型，便于在变更列表里认出来。
        changes += changeList(
            entity = SyncEntityKind.LEDGER_TEMPLATE,
            snapshots = backup.ledgerTemplates,
            local = previewRows(
                ledgerPlanningRepository.getAllTemplatesSnapshot(), useSyncIds,
                { it.syncId }, { it.id }, { it.updatedAt ?: it.createdAt }, { it.deletedAt }
            ).associateBy { it.key },
            localSnapshots = snapshotJsonMap(
                ledgerPlanningRepository.getAllTemplatesSnapshot(), useSyncIds, { it.syncId }, { it.id }
            ) { template ->
                LedgerTemplateSnapshot.fromEntity(
                    template = template,
                    categorySyncId = template.categoryId?.let { ledgerCategorySyncIdById[it] },
                    assetSyncId = template.assetId?.let { ledgerAssetSyncIdById[it] },
                    targetAssetSyncId = template.targetAssetId?.let { ledgerAssetSyncIdById[it] }
                )
            },
            referenceNames = referenceNames,
            syncedAfter = syncedAfter,
            useSyncIds = useSyncIds,
            syncId = { it.syncId },
            id = { it.id },
            updatedAt = { it.updatedAt ?: it.createdAt },
            deletedAt = { it.deletedAt },
            title = { it.name },
            subtitle = {
                "${recordTypeLabel(it.type)} · ${DateUtil.formatCurrency(LedgerMath.centsToYuan(it.amount))}"
            },
            specialValue = { field, value ->
                if (field == "type") recordTypeLabel(value.toIntOrNull() ?: -1) else null
            }
        )

        // 周期记账规则（v10）：副标题给出周期与下次生成日期。
        changes += changeList(
            entity = SyncEntityKind.LEDGER_RECURRING_RULE,
            snapshots = backup.ledgerRecurringRules,
            local = previewRows(
                ledgerPlanningRepository.getAllRulesSnapshot(), useSyncIds,
                { it.syncId }, { it.id }, { it.updatedAt ?: it.createdAt }, { it.deletedAt }
            ).associateBy { it.key },
            localSnapshots = snapshotJsonMap(
                ledgerPlanningRepository.getAllRulesSnapshot(), useSyncIds, { it.syncId }, { it.id }
            ) { rule ->
                LedgerRecurringRuleSnapshot.fromEntity(
                    rule = rule,
                    categorySyncId = rule.categoryId?.let { ledgerCategorySyncIdById[it] },
                    assetSyncId = rule.assetId?.let { ledgerAssetSyncIdById[it] },
                    targetAssetSyncId = rule.targetAssetId?.let { ledgerAssetSyncIdById[it] }
                )
            },
            referenceNames = referenceNames,
            syncedAfter = syncedAfter,
            useSyncIds = useSyncIds,
            syncId = { it.syncId },
            id = { it.id },
            updatedAt = { it.updatedAt ?: it.createdAt },
            deletedAt = { it.deletedAt },
            title = { it.name },
            subtitle = {
                "每 ${it.repeatInterval}${RecurringClock.unitLabel(it.unit)} · " +
                    DateUtil.formatCurrency(LedgerMath.centsToYuan(it.amount))
            },
            specialValue = { field, value ->
                if (field == "type") recordTypeLabel(value.toIntOrNull() ?: -1) else null
            }
        )

        return BackupChanges(changes = changes)
    }

    /** 账目的兜底标题：没写备注时按类型给一个可读的名字。 */
    private fun recordTypeLabel(type: Int): String = when (type) {
        LedgerRecord.TYPE_INCOME -> "收入"
        LedgerRecord.TYPE_TRANSFER -> "转账"
        else -> "支出"
    }

    /**
     * 把一个实体的远端快照与本地逐条对照，产出带标题的变更列表。
     * 判定与合并策略同口径：受保护记录不动、远端较新才算修改或删除；
     * 本地已删除、远端也删除的墓碑重叠按「修改」处理（合并时是无害的重复落地）。
     * 修改条目同时生成字段级 diff（S3）；[syncedAfter] 非 null 时，
     * 本地与远端都在它之后改过的条目标记为真冲突（S4），由用户裁决。
     */
    private inline fun <reified T> changeList(
        entity: SyncEntityKind,
        snapshots: List<T>,
        local: Map<String, PreviewRow>,
        localSnapshots: Map<String, JsonObject> = emptyMap(),
        referenceNames: Map<String, String> = emptyMap(),
        syncedAfter: Long? = null,
        useSyncIds: Boolean,
        syncId: (T) -> String?,
        id: (T) -> Long,
        updatedAt: (T) -> Long?,
        deletedAt: (T) -> Long?,
        title: (T) -> String,
        subtitle: (T) -> String? = { null },
        noinline specialValue: (field: String, value: String) -> String? = { _, _ -> null }
    ): List<SyncChange> = snapshots.mapNotNull { snapshot ->
        val key = "${entity.name}:${keyOf(syncId(snapshot), id(snapshot), useSyncIds)}"
        val existing = local[key.substringAfter(':')]
        val kind = when {
            existing == null ->
                if (deletedAt(snapshot) == null) SyncChangeKind.ADDED else null
            existing.protected -> null
            !isRemoteNewer(updatedAt(snapshot), existing.updatedAt) -> null
            deletedAt(snapshot) != null && existing.deletedAt == null -> SyncChangeKind.DELETED
            else -> SyncChangeKind.MODIFIED
        } ?: return@mapNotNull null
        // S3：修改条目把本地行转成同构快照 JSON，与对方快照逐字段对照。
        val diffs = if (kind == SyncChangeKind.MODIFIED) {
            localSnapshots[existing?.key]?.let { localJson ->
                diffFields(
                    local = localJson,
                    remote = diffJson.encodeToJsonElement(snapshot).jsonObject,
                    referenceNames = referenceNames,
                    specialValue = specialValue
                )
            }.orEmpty()
        } else {
            emptyList()
        }
        // S4：双方都在上次同步后改过 → 真冲突。勾选=采用对方，不勾=保留本机。
        val conflict = kind == SyncChangeKind.MODIFIED && syncedAfter != null &&
            (existing?.updatedAt ?: 0L) > syncedAfter && (updatedAt(snapshot) ?: 0L) > syncedAfter
        SyncChange(
            entity = entity,
            kind = kind,
            key = key,
            title = title(snapshot),
            subtitle = subtitle(snapshot),
            diffs = diffs,
            conflict = conflict
        )
    }

    /** 把本地实体列表转成「对齐键 → 快照 JSON」：与远端快照同构，供字段级 diff 用（S3）。 */
    private inline fun <T, reified S> snapshotJsonMap(
        items: List<T>,
        useSyncIds: Boolean,
        syncId: (T) -> String?,
        id: (T) -> Long,
        toSnapshot: (T) -> S
    ): Map<String, JsonObject> = items.associate { entity ->
        val snapshot = toSnapshot(entity)
        keyOf(syncId(entity), id(entity), useSyncIds) to
            diffJson.encodeToJsonElement(snapshot).jsonObject
    }

    /** diff 编码专用：encodeDefaults 保证两侧键集合一致（空值/默认值也写出来），才能逐字段对照。 */
    private val diffJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun <T> previewRows(
        items: List<T>,
        useSyncIds: Boolean,
        syncId: (T) -> String?,
        id: (T) -> Long,
        updatedAt: (T) -> Long?,
        deletedAt: (T) -> Long?,
        protected: (T) -> Boolean = { false }
    ): List<PreviewRow> = items.map {
        PreviewRow(
            key = keyOf(syncId(it), id(it), useSyncIds),
            updatedAt = updatedAt(it),
            deletedAt = deletedAt(it),
            protected = protected(it)
        )
    }

    /**
     * 生成备份 ZIP。
     *
     * [since] 为增量同步的推送水位线：为 null 时导出全量（手动导出即走全量）；
     * 非 null 时只导出 `updatedAt` 晚于它的记录。注意 exportedAt 必须在读取快照
     * 之前取，这样「读取期间被修改」的记录其 updatedAt 会晚于 exportedAt，
     * 下次同步会被重新带上（重复传输由 LWW 合并吸收，不会出错）。
     *
     * 引用（分类、位置的 syncId）始终基于全量数据解析，否则增量包里未变更的
     * 父级/所属分类不在包内，导入端会解析不到而丢失关联。
     */
    suspend fun createBackupZip(since: Long? = null): BackupArchive {
        val built = buildPayload(since)
        val workingFile = File(context.cacheDir, "lemonbox-backup-${System.currentTimeMillis()}.zip")
        writePayloadZipTo(built.payload, workingFile)
        return BackupArchive(file = workingFile, exportedAt = built.exportedAt, isEmpty = built.isEmpty)
    }

    /**
     * 逐条列出本机全部记录（**不打包**），供服务端按记录同步推送。
     *
     * 与 [createBackupZip] 同一套读库与映射：载荷仍是 `BackupSnapshots` 的 DTO，
     * 只是从「一个 ZIP」改成「一批 (集合, syncId, 载荷)」，让服务端能按条合并。
     */
    suspend fun snapshotRecords(): List<SyncRecord> =
        buildPayload(since = null).payload.toSyncRecords()

    /**
     * 把一份清单写成备份 ZIP（清单 + 完整性校验条目），供服务端拉取重组后走既有导入通道。
     */
    fun writePayloadZipTo(payload: AppBackupPayload, target: File) {
        val manifestBytes = json.encodeToString(payload).toByteArray()
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_MANIFEST_NAME))
            zip.write(manifestBytes)
            zip.closeEntry()

            // 完整性校验（F21）：把清单的 SHA-256 单独存一条，导入/预览时比对，
            // 便于发现文件损坏或被外部改动。旧备份没有该条目，校验时按缺失跳过。
            zip.putNextEntry(ZipEntry(BACKUP_CHECKSUM_NAME))
            zip.write(sha256Hex(manifestBytes).toByteArray())
            zip.closeEntry()
        }
    }

    /**
     * 读全库并组装成一份清单；[since] 的过滤语义见 [createBackupZip]。
     */
    private suspend fun buildPayload(since: Long?): BuiltPayload {
        val exportedAt = System.currentTimeMillis()

        val allCategories = categoryRepository.getAllCategoriesSnapshot()
        val allLocations = locationRepository.getAllLocationsSnapshot()
        val allItems = itemRepository.getAllItemsSnapshot()
        val allLedgerAssets = ledgerRepository.getAllAssetsSnapshot()
        val allLedgerCategories = ledgerRepository.getAllCategoriesSnapshot()
        val allLedgerRecords = ledgerRepository.getAllRecordsSnapshot()
        val allLedgerBudgets = ledgerRepository.getAllBudgetsSnapshot()
        val allAnniversaries = anniversaryRepository.getAllSnapshot()
        val allReminders = reminderRepository.getAllSnapshot()
        val allTimeCapsules = timeCapsuleRepository.getAllSnapshot()
        val allPackingTrips = packingRepository.getAllTripsSnapshot()
        val allPackingItems = packingRepository.getAllItemsSnapshot()
        val allLedgerTemplates = ledgerPlanningRepository.getAllTemplatesSnapshot()
        val allLedgerRules = ledgerPlanningRepository.getAllRulesSnapshot()

        val categorySyncIdById = allCategories.associate { it.id to it.syncId }
        val locationSyncIdById = allLocations.associate { it.id to it.syncId }
        val itemSyncIdById = allItems.associate { it.id to it.syncId }
        val ledgerCategorySyncIdById = allLedgerCategories.associate { it.id to it.syncId }
        val ledgerAssetSyncIdById = allLedgerAssets.associate { it.id to it.syncId }
        val packingTripSyncIdById = allPackingTrips.associate { it.id to it.syncId }

        val categories = if (since == null) {
            allCategories
        } else {
            allCategories.filter { (it.updatedAt ?: 0L) > since }
        }
        val locations = if (since == null) {
            allLocations
        } else {
            allLocations.filter { (it.updatedAt ?: 0L) > since }
        }
        val items = if (since == null) {
            allItems
        } else {
            allItems.filter { (it.updatedAt ?: it.createdAt) > since }
        }
        val ledgerAssets = if (since == null) {
            allLedgerAssets
        } else {
            allLedgerAssets.filter { (it.updatedAt ?: 0L) > since }
        }
        val ledgerCategories = if (since == null) {
            allLedgerCategories
        } else {
            allLedgerCategories.filter { (it.updatedAt ?: 0L) > since }
        }
        val ledgerRecords = if (since == null) {
            allLedgerRecords
        } else {
            allLedgerRecords.filter { (it.updatedAt ?: it.createdAt) > since }
        }
        val ledgerBudgets = if (since == null) {
            allLedgerBudgets
        } else {
            allLedgerBudgets.filter { (it.updatedAt ?: 0L) > since }
        }
        val anniversaries = if (since == null) {
            allAnniversaries
        } else {
            allAnniversaries.filter { (it.updatedAt ?: it.createdAt) > since }
        }
        val reminders = if (since == null) {
            allReminders
        } else {
            allReminders.filter { (it.updatedAt ?: it.createdAt) > since }
        }
        val timeCapsules = if (since == null) {
            allTimeCapsules
        } else {
            allTimeCapsules.filter { (it.updatedAt ?: it.createdAt) > since }
        }
        val packingTrips = if (since == null) {
            allPackingTrips
        } else {
            allPackingTrips.filter { (it.updatedAt ?: it.createdAt) > since }
        }
        val packingItems = if (since == null) {
            allPackingItems
        } else {
            allPackingItems.filter { (it.updatedAt ?: it.createdAt) > since }
        }
        val ledgerTemplates = if (since == null) {
            allLedgerTemplates
        } else {
            allLedgerTemplates.filter { (it.updatedAt ?: it.createdAt) > since }
        }
        val ledgerRecurringRules = if (since == null) {
            allLedgerRules
        } else {
            allLedgerRules.filter { (it.updatedAt ?: it.createdAt) > since }
        }

        // 增量导出且过滤后一条不剩，说明自上次水位线以来本机没改过任何东西。
        // 全量导出恒为 false：即便库是空的，那份包也是「完整状态」的代表，不是"无变更"。
        val isEmpty = since != null &&
            categories.isEmpty() && locations.isEmpty() && items.isEmpty() &&
            ledgerAssets.isEmpty() && ledgerCategories.isEmpty() && ledgerRecords.isEmpty() &&
            ledgerBudgets.isEmpty() && anniversaries.isEmpty() && reminders.isEmpty() &&
            timeCapsules.isEmpty() && packingTrips.isEmpty() && packingItems.isEmpty() &&
            ledgerTemplates.isEmpty() && ledgerRecurringRules.isEmpty()
        val backup = AppBackupPayload(
            exportedAt = exportedAt,
            syncVersion = SYNC_VERSION,
            deviceId = appPreferences.deviceId,
            categories = categories.map {
                CategorySnapshot.fromEntity(
                    category = it,
                    parentSyncId = it.parentId?.let { parentId -> categorySyncIdById[parentId] }
                )
            },
            locations = locations.map {
                LocationSnapshot.fromEntity(
                    location = it,
                    parentSyncId = it.parentId?.let { parentId -> locationSyncIdById[parentId] }
                )
            },
            items = items.map { item ->
                // 图片不再打包进备份包：图片本体存在对象存储上，两台设备指向同一个桶，
                // 局域网同步只需传「元数据 + 内容哈希」，图片由对方按哈希推导对象键后取回。
                // 尚未上传的槽位也照传哈希——对方据此知道该位置有图，两端各自补传后收敛。
                ItemSnapshot.fromEntity(
                    item = item,
                    imageHashes = item.imageHashList(),
                    categorySyncId = item.categoryId?.let { categorySyncIdById[it] },
                    locationSyncId = item.locationId?.let { locationSyncIdById[it] }
                )
            },
            ledgerAssets = ledgerAssets.map { LedgerAssetSnapshot.fromEntity(it) },
            ledgerCategories = ledgerCategories.map { LedgerCategorySnapshot.fromEntity(it) },
            ledgerRecords = ledgerRecords.map { record ->
                LedgerRecordSnapshot.fromEntity(
                    record = record,
                    categorySyncId = record.categoryId?.let { ledgerCategorySyncIdById[it] },
                    assetSyncId = record.assetId?.let { ledgerAssetSyncIdById[it] },
                    targetAssetSyncId = record.targetAssetId?.let { ledgerAssetSyncIdById[it] },
                    itemSyncId = record.itemId?.let { itemSyncIdById[it] }
                )
            },
            ledgerBudgets = ledgerBudgets.map { budget ->
                LedgerBudgetSnapshot.fromEntity(
                    budget = budget,
                    categorySyncId = budget.categoryId?.let { ledgerCategorySyncIdById[it] }
                )
            },
            anniversaries = anniversaries.map { AnniversarySnapshot.fromEntity(it) },
            reminders = reminders.map { reminder ->
                ReminderSnapshot.fromEntity(
                    reminder = reminder,
                    itemSyncId = reminder.linkedItemId?.let { itemSyncIdById[it] }
                )
            },
            timeCapsules = timeCapsules.map { TimeCapsuleSnapshot.fromEntity(it) },
            packingTrips = packingTrips.map { PackingTripSnapshot.fromEntity(it) },
            packingItems = packingItems.map {
                PackingItemSnapshot.fromEntity(
                    item = it,
                    tripSyncId = it.tripId.let { tripId -> packingTripSyncIdById[tripId] }
                )
            },
            ledgerTemplates = ledgerTemplates.map { template ->
                LedgerTemplateSnapshot.fromEntity(
                    template = template,
                    categorySyncId = template.categoryId?.let { ledgerCategorySyncIdById[it] },
                    assetSyncId = template.assetId?.let { ledgerAssetSyncIdById[it] },
                    targetAssetSyncId = template.targetAssetId?.let { ledgerAssetSyncIdById[it] }
                )
            },
            ledgerRecurringRules = ledgerRecurringRules.map { rule ->
                LedgerRecurringRuleSnapshot.fromEntity(
                    rule = rule,
                    categorySyncId = rule.categoryId?.let { ledgerCategorySyncIdById[it] },
                    assetSyncId = rule.assetId?.let { ledgerAssetSyncIdById[it] },
                    targetAssetSyncId = rule.targetAssetId?.let { ledgerAssetSyncIdById[it] }
                )
            },
            preferences = PreferencesSnapshot(
                recipes = appPreferences.recipesSnapshot(),
                weeklyMenu = appPreferences.weeklyMenuSnapshot(),
                cookedMenuDates = appPreferences.cookedMenuDatesSnapshot(),
                customStatuses = appPreferences.customStatusesSnapshot()
                    .map { CustomStatusSnapshot.fromOption(it) },
                reminderLadder = appPreferences.reminderLadder.value,
                reminderTimes = appPreferences.reminderTimes.value,
                customReminderTimes = appPreferences.customReminderTimes.value,
                mealPrepDayShift = appPreferences.mealPrepDayShift.value,
                mealPrepFireTime = appPreferences.mealPrepFireTime.value,
                togetherStartDate = appPreferences.togetherStartDateSnapshot(),
                togetherStartDateUpdatedAt = appPreferences.togetherUpdatedAt.value,
                togetherLabelName = appPreferences.togetherLabelName.value,
                nickname = appPreferences.nickname.value,
                ledgerMonthStartDay = appPreferences.ledgerMonthStartDay.value,
                budgetReminderEnabled = appPreferences.budgetReminderEnabled.value,
                mealPlanDays = appPreferences.mealPlanDays.value
            )
        )

        return BuiltPayload(payload = backup, exportedAt = exportedAt, isEmpty = isEmpty)
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
    }

    /**
     * 读取并校验 backup.json（F21）：若 ZIP 内含校验和条目，则比对清单的 SHA-256，
     * 不匹配说明文件损坏或被外部改动，直接抛错中止导入，避免把坏数据写进库里。
     * 旧备份（无校验和条目）按缺失跳过，保持向后兼容。
     */
    private fun readVerifiedPayload(workingDir: File): AppBackupPayload {
        val manifestBytes = File(workingDir, BACKUP_MANIFEST_NAME).readBytes()
        val checksumFile = File(workingDir, BACKUP_CHECKSUM_NAME)
        if (checksumFile.exists()) {
            val expected = checksumFile.readText().trim()
            if (!expected.equals(sha256Hex(manifestBytes), ignoreCase = true)) {
                throw IllegalStateException("备份文件完整性校验未通过：内容可能已损坏，已中止导入。")
            }
        }
        return json.decodeFromString<AppBackupPayload>(String(manifestBytes, Charsets.UTF_8))
    }

    /** 计算字节数组的 SHA-256，返回小写十六进制字符串。 */
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val BACKUP_MANIFEST_NAME = "backup.json"

        /** 完整性校验条目名（F21）：内容为 backup.json 的 SHA-256 十六进制串。 */
        private const val BACKUP_CHECKSUM_NAME = "backup.sha256"

        /** 支持合并的备份格式版本；缺失该字段的旧备份按 v1（仅数字 id 对齐）处理。
         * v7：纪念日类型 type（倒数日/正数日/生日），不再导出 v5 的 repeatYearly 旧字段。
         * v8：待买项新增可选归属 forWhom（self/partner/shared）。
         * v9：时间胶囊与旅行打包清单（行程 + 条目）纳入备份；旧版本读新备份时按空列表忽略。 */
        const val SYNC_VERSION = 12

        /** 从该版本起备份用 syncId 对齐；更早的备份退回数字 id。 */
        private const val SYNC_ID_VERSION = 2

        // 合并用的对齐键与新旧比较已随合并策略拆分到 BackupMergeStrategy.kt（F9）。
    }
}

/** [AppBackupManager.buildPayload] 的产物：清单本体 + 导出时刻 + 「过滤后是否一条不剩」。 */
private data class BuiltPayload(
    val payload: AppBackupPayload,
    val exportedAt: Long,
    val isEmpty: Boolean
)

/** diff 里不展示的字段：设备本地行 id、对齐键、图片槽位（哈希），旧版单值状态。 */
private val DIFF_EXCLUDED_FIELDS = setOf("id", "syncId", "imageHashes", "status")

/** 字段名 → 中文标签（S3 diff 展示用）。 */
private val FIELD_LABELS = mapOf(
    "name" to "名称",
    "barcode" to "条码",
    "quantity" to "数量",
    "consumedQuantity" to "已消耗",
    "unit" to "单位",
    "price" to "价格",
    "expireTime" to "保质期",
    "usageStatus" to "使用进度",
    "disposition" to "去向",
    "needRestock" to "需要补货",
    "purchaseDate" to "购买日期",
    "productionDate" to "生产日期",
    "startUseTime" to "开始使用",
    "usageEndedAt" to "用完时间",
    "trackMode" to "计量方式",
    "forWhom" to "待买归属",
    "rating" to "评分",
    "ratedAt" to "评分时间",
    "note" to "备注",
    "createdAt" to "创建时间",
    "updatedAt" to "修改时间",
    "deletedAt" to "删除时间",
    "icon" to "图标",
    "parentSyncId" to "上级",
    "isProtected" to "内置保护",
    "type" to "类型",
    "amount" to "金额",
    "recordTime" to "记账时间",
    "remark" to "备注",
    "period" to "周期",
    "date" to "日期",
    "isLunar" to "农历",
    "lunarMonth" to "农历月",
    "lunarDay" to "农历日",
    "repeatUnit" to "重复单位",
    "repeatInterval" to "重复间隔",
    "remindDays" to "提前提醒天数",
    "enabled" to "启用",
    "repeatType" to "重复方式",
    "intervalDays" to "间隔天数",
    "weekdays" to "每周",
    "fireTime" to "提醒时刻",
    "targetDate" to "目标日期",
    "nextFireAt" to "下次提醒",
    "completedAt" to "完成时间",
    "source" to "来源",
    "sourceKey" to "来源键",
    "sort" to "排序",
    "initialBalance" to "期初余额",
    "kind" to "收支类别",
    "categorySyncId" to "所属分类",
    "assetSyncId" to "所属账户",
    "targetAssetSyncId" to "转入账户",
    "itemSyncId" to "关联家当"
)

/** 家当三维状态的码值 → 文案（S3 diff 展示用）。 */
private val ITEM_VALUE_LABELS = mapOf(
    "usageStatus" to mapOf(
        Item.USAGE_UNUSED.toLong() to "未使用",
        Item.USAGE_IN_USE.toLong() to "使用中",
        Item.USAGE_USED_UP.toLong() to "已用完"
    ),
    "disposition" to mapOf(
        Item.DISPOSITION_IN_STOCK.toLong() to "在库",
        Item.DISPOSITION_LENT_OUT.toLong() to "借出",
        Item.DISPOSITION_GIVEN_AWAY.toLong() to "送人",
        Item.DISPOSITION_DISCARDED.toLong() to "丢弃"
    ),
    "trackMode" to mapOf(
        Item.TRACK_CONSUMABLE.toLong() to "按件消耗",
        Item.TRACK_DURABLE.toLong() to "持续使用"
    )
)

/**
 * 逐字段对照本地与远端的同构快照 JSON（S3）：两侧都是同一 schema 编出来的 JsonObject，
 * 键集合一致，凡是值不同的字段都进 diff，展示为「本地值 → 对方值」。
 */
private fun diffFields(
    local: JsonObject,
    remote: JsonObject,
    referenceNames: Map<String, String>,
    specialValue: (field: String, value: String) -> String?
): List<FieldDiff> = buildList {
    remote.forEach { (field, remoteElement) ->
        if (field in DIFF_EXCLUDED_FIELDS) return@forEach
        val localElement = local[field] ?: return@forEach
        if (localElement == remoteElement) return@forEach
        add(
            FieldDiff(
                field = field,
                label = FIELD_LABELS[field] ?: field,
                localValue = formatDiffValue(field, localElement, referenceNames, specialValue),
                remoteValue = formatDiffValue(field, remoteElement, referenceNames, specialValue)
            )
        )
    }
}

/** 把一个 JSON 值翻成用户读得懂的短文案：时间戳转日期、分转金额、码值转中文、UUID 转名字。 */
private fun formatDiffValue(
    field: String,
    element: JsonElement,
    referenceNames: Map<String, String>,
    specialValue: (field: String, value: String) -> String?
): String {
    if (element is JsonNull) return "（空）"
    val primitive = element as? JsonPrimitive ?: return element.toString()
    if (primitive.isString) {
        val value = primitive.content
        specialValue(field, value)?.let { return it }
        return when {
            field == "forWhom" -> when (value) {
                "self" -> "自己"
                "partner" -> "对方"
                "shared" -> "共用"
                else -> value
            }
            field.endsWith("SyncId") -> referenceNames[value] ?: value
            else -> value
        }
    }
    primitive.longOrNull?.let { value ->
        specialValue(field, value.toString())?.let { return it }
        ITEM_VALUE_LABELS[field]?.get(value)?.let { return it }
        return when {
            field == "amount" || field == "initialBalance" ->
                DateUtil.formatCurrency(LedgerMath.centsToYuan(value))
            field.endsWith("At") || field.endsWith("Time") || field.endsWith("Date") ->
                DateUtil.formatDate(value)
            else -> value.toString()
        }
    }
    primitive.booleanOrNull?.let { return if (it) "是" else "否" }
    primitive.doubleOrNull?.let { return DateUtil.formatCurrency(it) }
    return primitive.content
}
