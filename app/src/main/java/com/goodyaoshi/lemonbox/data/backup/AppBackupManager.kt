package com.goodyaoshi.lemonbox.data.backup

import android.content.Context
import android.net.Uri
import com.goodyaoshi.lemonbox.data.repository.AnniversaryRepository
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.data.repository.LocationRepository
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.TodoReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
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
    /** 纪念日，v5 起导出。 */
    val anniversaryAdded: Int = 0,
    val anniversaryUpdated: Int = 0,
    /** 用户自建菜谱新增数，v5 起导出。 */
    val recipeAdded: Int = 0,
    /** 周菜单/已做日期/自定义状态/提醒设置是否有应用。 */
    val prefsApplied: Boolean = false
) {
    val hasChanges: Boolean
        get() = itemAdded + itemUpdated + categoryAdded + categoryUpdated +
            locationAdded + locationUpdated + ledgerAdded + ledgerUpdated +
            reminderAdded + reminderUpdated + anniversaryAdded + anniversaryUpdated +
            recipeAdded > 0 || prefsApplied
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
        if (recipeAdded > 0) add("新增菜谱 $recipeAdded 道")
        if (prefsApplied) add("同步了菜单与提醒设置")
        if (itemKept > 0) add("保留本地 $itemKept 件")
    }
    return "合并完成：" + parts.joinToString("、")
}

/** 一次备份导出的产物：临时 ZIP 文件 + 生成时刻（增量同步以 exportedAt 作为对方的水位线）。 */
data class BackupArchive(
    val file: File,
    val exportedAt: Long
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
    internal val anniversaryRepository: AnniversaryRepository,
    internal val reminderRepository: ReminderRepository,
    private val todoReminderScheduler: TodoReminderScheduler,
    private val appPreferences: AppPreferences
) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    suspend fun exportBackup(targetUri: Uri) {
        val archive = createBackupZip()
        try {
            context.contentResolver.openOutputStream(targetUri)?.use { output ->
                archive.file.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法打开导出目标")
        } finally {
            archive.file.delete()
        }
    }

    /**
     * 合并导入：不再整库替换。以 syncId 对齐同一条记录，按 updatedAt「较新者胜」，
     * 删除通过软删除时间戳传递。这样双方各自编辑后互相导入不会丢数据。
     */
    suspend fun importBackup(sourceUri: Uri): BackupMergeResult {
        val workingDir = File(context.cacheDir, "backup-import-${System.currentTimeMillis()}.apply").apply {
            mkdirs()
        }
        val backupZip = File(workingDir, "backup.zip")
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                backupZip.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取备份文件")

            unzip(backupZip, workingDir)
            val payload = File(workingDir, BACKUP_MANIFEST_NAME).readText()
            val backup = json.decodeFromString<AppBackupPayload>(payload)
            val useSyncIds = backup.syncVersion >= SYNC_ID_VERSION

            val categoryOutcome = mergeCategories(backup.categories, useSyncIds)
            val locationOutcome = mergeLocations(backup.locations, useSyncIds)
            val itemOutcome = mergeItems(
                snapshots = backup.items,
                workingDir = workingDir,
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
            val reminderOutcome = mergeReminders(backup.reminders, useSyncIds)
            // v5 起：菜谱/周菜单/自定义状态/提醒设置等偏好内容。
            val prefs = backup.preferences
            val recipeAdded = prefs?.let { appPreferences.applySyncedRecipes(it.recipes) } ?: 0
            var prefsApplied = false
            if (prefs != null) {
                prefsApplied = prefsApplied ||
                    appPreferences.applySyncedWeeklyMenu(prefs.weeklyMenu) > 0 ||
                    appPreferences.applySyncedCookedDates(prefs.cookedMenuDates) > 0 ||
                    appPreferences.applySyncedCustomStatuses(
                        prefs.customStatuses.mapNotNull { it.toOption() }
                    ) > 0 ||
                    appPreferences.applySyncedReminderSettings(
                        reminderLadder = prefs.reminderLadder,
                        reminderTimes = prefs.reminderTimes,
                        customReminderTimes = prefs.customReminderTimes,
                        mealPrepDayShift = prefs.mealPrepDayShift,
                        mealPrepFireTime = prefs.mealPrepFireTime
                    )
            }
            if (reminderOutcome.added + reminderOutcome.updated > 0) {
                todoReminderScheduler.reschedule()
            }

            // 两台设备各自种子化的默认分类/位置/记账分类/账户 syncId 不同，按 syncId 对齐后
            // 会各留一份，合并完统一按「同名同父级路径」收敛，避免选择器出现多个一样的项。
            locationRepository.deduplicateLocations()
            categoryRepository.deduplicateCategories()
            ledgerRepository.deduplicateCategories()
            ledgerRepository.deduplicateAssets()
            anniversaryRepository.deduplicateAnniversaries()

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
                recipeAdded = recipeAdded,
                prefsApplied = prefsApplied
            )
        } finally {
            workingDir.deleteRecursively()
        }
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

        val categorySyncIdById = allCategories.associate { it.id to it.syncId }
        val locationSyncIdById = allLocations.associate { it.id to it.syncId }
        val itemSyncIdById = allItems.associate { it.id to it.syncId }
        val ledgerCategorySyncIdById = allLedgerCategories.associate { it.id to it.syncId }
        val ledgerAssetSyncIdById = allLedgerAssets.associate { it.id to it.syncId }

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

        val workingFile = File(context.cacheDir, "lemonbox-backup-${System.currentTimeMillis()}.zip")
        val imageEntries = mutableListOf<ImageEntry>()
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
                val images = item.imagePathList().mapIndexedNotNull { index, path ->
                    val file = File(path)
                    if (!file.exists()) {
                        null
                    } else {
                        val relativePath = "images/${item.id}_${index}_${file.name}"
                        imageEntries += ImageEntry(relativePath, file)
                        relativePath
                    }
                }
                ItemSnapshot.fromEntity(
                    item = item,
                    imagePaths = images,
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
            reminders = reminders.map { ReminderSnapshot.fromEntity(it) },
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
                mealPrepFireTime = appPreferences.mealPrepFireTime.value
            )
        )

        ZipOutputStream(FileOutputStream(workingFile)).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_MANIFEST_NAME))
            zip.write(json.encodeToString(backup).toByteArray())
            zip.closeEntry()

            imageEntries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.relativePath))
                entry.file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }

        return BackupArchive(file = workingFile, exportedAt = exportedAt)
    }

    private data class ImageEntry(
        val relativePath: String,
        val file: File
    )

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

    companion object {
        private const val BACKUP_MANIFEST_NAME = "backup.json"

        /** 支持合并的备份格式版本；缺失该字段的旧备份按 v1（仅数字 id 对齐）处理。
         * v7：纪念日类型 type（倒数日/正数日/生日），不再导出 v5 的 repeatYearly 旧字段。 */
        const val SYNC_VERSION = 7

        /** 从该版本起备份用 syncId 对齐；更早的备份退回数字 id。 */
        private const val SYNC_ID_VERSION = 2

        // 合并用的对齐键与新旧比较已随合并策略拆分到 BackupMergeStrategy.kt（F9）。
    }
}
