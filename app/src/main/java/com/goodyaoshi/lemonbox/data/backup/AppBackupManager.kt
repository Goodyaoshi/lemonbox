package com.goodyaoshi.lemonbox.data.backup

import android.content.Context
import android.net.Uri
import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset
import com.goodyaoshi.lemonbox.data.local.entity.LedgerBudget
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.local.entity.LegacyStatusMapping
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusOption
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.data.meal.MealSpec
import com.goodyaoshi.lemonbox.data.meal.Recipe
import com.goodyaoshi.lemonbox.data.repository.AnniversaryRepository
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LedgerRepository
import com.goodyaoshi.lemonbox.data.repository.LocationRepository
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.ImageUtil
import com.goodyaoshi.lemonbox.util.ReminderClock
import com.goodyaoshi.lemonbox.util.TodoReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
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

@Singleton
class AppBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val itemRepository: ItemRepository,
    private val categoryRepository: CategoryRepository,
    private val locationRepository: LocationRepository,
    private val ledgerRepository: LedgerRepository,
    private val anniversaryRepository: AnniversaryRepository,
    private val reminderRepository: ReminderRepository,
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

    private suspend fun mergeCategories(
        snapshots: List<CategorySnapshot>,
        useSyncIds: Boolean
    ): MergeOutcome {
        val localCategories = categoryRepository.getAllCategoriesSnapshot()
        val localByKey = localCategories.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
        val localById = localCategories.associateBy { it.id }.toMutableMap()
        // 预填充本地已有记录：增量包可能不含未变更的分类，物品的 categorySyncId 仍要能解析。
        val idByKey = HashMap(localByKey.mapValues { it.value.id })
        val insertedKeys = HashSet<String>()
        var added = 0
        var updated = 0

        // 第一遍：把本地缺失的分类先以「一级分类」插入，拿到本地 id 后再修正父子关系。
        snapshots.forEach { snapshot ->
            val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
            val local = localByKey[key]
            if (local == null) {
                val newId = categoryRepository.insertSynced(
                    Category(
                        id = 0,
                        name = snapshot.name,
                        icon = snapshot.icon,
                        parentId = null,
                        syncId = snapshot.syncId.orNewSyncId(),
                        updatedAt = snapshot.updatedAt ?: System.currentTimeMillis(),
                        deletedAt = snapshot.deletedAt,
                        isProtected = snapshot.isProtected
                    )
                )
                idByKey[key] = newId
                insertedKeys += key
                categoryRepository.getCategoryById(newId)?.let { localById[newId] = it }
                added++
            } else {
                idByKey[key] = local.id
            }
        }

        // 第二遍：修正名称、图标与 parentId（parent 也需要按 syncId 重映射到本地 id）。
        snapshots.forEach { snapshot ->
            val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
            val localId = idByKey[key] ?: return@forEach
            val local = localById[localId] ?: return@forEach
            val isNew = key in insertedKeys
            if (!isNew && !isRemoteNewer(snapshot.updatedAt, local.updatedAt)) {
                return@forEach
            }
            val parentId = resolveParentKey(
                parentSyncId = snapshot.parentSyncId,
                parentId = snapshot.parentId,
                idByKey = idByKey,
                useSyncIds = useSyncIds
            )
            categoryRepository.updateSynced(
                local.copy(
                    name = snapshot.name,
                    icon = snapshot.icon,
                    parentId = parentId,
                    updatedAt = snapshot.updatedAt ?: local.updatedAt,
                    deletedAt = snapshot.deletedAt,
                    // 保护位只增不减：远端标记保护后，本地同步过来的同一条也不能解除。
                    isProtected = local.isProtected || snapshot.isProtected
                )
            )
            if (!isNew) updated++
        }

        return MergeOutcome(added, updated, idByKey)
    }

    private suspend fun mergeLocations(
        snapshots: List<LocationSnapshot>,
        useSyncIds: Boolean
    ): MergeOutcome {
        val localLocations = locationRepository.getAllLocationsSnapshot()
        val localByKey = localLocations.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
        val localById = localLocations.associateBy { it.id }.toMutableMap()
        // 预填充本地已有记录：增量包可能不含未变更的父级地点，否则 parentSyncId 会被解析成 null。
        val idByKey = HashMap(localByKey.mapValues { it.value.id })
        val insertedKeys = HashSet<String>()
        var added = 0
        var updated = 0

        // 第一遍：把本地缺失的地点先以「根节点」插入，拿到本地 id 后再修正父子关系。
        snapshots.forEach { snapshot ->
            val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
            val local = localByKey[key]
            if (local == null) {
                val newId = locationRepository.insertSynced(
                    Location(
                        id = 0,
                        name = snapshot.name,
                        parentId = null,
                        syncId = snapshot.syncId.orNewSyncId(),
                        updatedAt = snapshot.updatedAt ?: System.currentTimeMillis(),
                        deletedAt = snapshot.deletedAt,
                        isProtected = snapshot.isProtected
                    )
                )
                idByKey[key] = newId
                insertedKeys += key
                locationRepository.getLocationById(newId)?.let { localById[newId] = it }
                added++
            } else {
                idByKey[key] = local.id
            }
        }

        // 第二遍：修正名称与 parentId（parent 也需要按 syncId 重映射到本地 id）。
        snapshots.forEach { snapshot ->
            val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
            val localId = idByKey[key] ?: return@forEach
            val local = localById[localId] ?: return@forEach
            val isNew = key in insertedKeys
            if (!isNew && !isRemoteNewer(snapshot.updatedAt, local.updatedAt)) {
                return@forEach
            }
            val parentId = resolveParentKey(
                parentSyncId = snapshot.parentSyncId,
                parentId = snapshot.parentId,
                idByKey = idByKey,
                useSyncIds = useSyncIds
            )
            locationRepository.updateSynced(
                local.copy(
                    name = snapshot.name,
                    parentId = parentId,
                    updatedAt = snapshot.updatedAt ?: local.updatedAt,
                    deletedAt = snapshot.deletedAt,
                    // 保护位只增不减：远端标记保护后，本地同步过来的同一条也不能解除。
                    isProtected = local.isProtected || snapshot.isProtected
                )
            )
            if (!isNew) updated++
        }

        return MergeOutcome(added, updated, idByKey)
    }

    private suspend fun mergeItems(
        snapshots: List<ItemSnapshot>,
        workingDir: File,
        useSyncIds: Boolean,
        categoryIdByKey: Map<String, Long>,
        locationIdByKey: Map<String, Long>
    ): ItemMergeOutcome {
        val localItems = itemRepository.getAllItemsSnapshot()
        val localByKey = localItems.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
        // 预填充本地记录，保证 idByKey 对增量包中未出现的记录也能解析。
        val idByKey = HashMap(localByKey.mapValues { it.value.id })
        var added = 0
        var updated = 0
        var kept = 0

        snapshots.forEach { snapshot ->
            val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
            val local = localByKey[key]
            if (local == null) {
                val images = resolveImages(snapshot.imagePaths, workingDir)
                val newId = itemRepository.insertSynced(
                    snapshot.toItem(
                        targetId = 0,
                        resolvedImages = images,
                        categoryId = resolveReferenceId(
                            syncId = snapshot.categorySyncId,
                            fallbackId = snapshot.categoryId,
                            idByKey = categoryIdByKey,
                            useSyncIds = useSyncIds
                        ),
                        locationId = resolveReferenceId(
                            syncId = snapshot.locationSyncId,
                            fallbackId = snapshot.locationId,
                            idByKey = locationIdByKey,
                            useSyncIds = useSyncIds
                        )
                    )
                )
                idByKey[key] = newId
                added++
            } else {
                idByKey[key] = local.id
                val remoteUpdated = snapshot.updatedAt ?: snapshot.createdAt
                val localUpdated = local.updatedAt ?: local.createdAt
                if (!isRemoteNewer(remoteUpdated, localUpdated)) {
                    kept++
                    return@forEach
                }
                val images = resolveImages(snapshot.imagePaths, workingDir)
                itemRepository.updateSynced(
                    snapshot.toItem(
                        targetId = local.id,
                        resolvedImages = images,
                        categoryId = resolveReferenceId(
                            syncId = snapshot.categorySyncId,
                            fallbackId = snapshot.categoryId,
                            idByKey = categoryIdByKey,
                            useSyncIds = useSyncIds
                        ),
                        locationId = resolveReferenceId(
                            syncId = snapshot.locationSyncId,
                            fallbackId = snapshot.locationId,
                            idByKey = locationIdByKey,
                            useSyncIds = useSyncIds
                        )
                    ).copy(syncId = local.syncId ?: snapshot.syncId.orNewSyncId())
                )
                updated++
            }
        }

        return ItemMergeOutcome(added, updated, kept, idByKey)
    }

    /** 记账分类合并：扁平结构（parentId 恒为 null），单遍即可；保护位只增不减。 */
    private suspend fun mergeLedgerCategories(
        snapshots: List<LedgerCategorySnapshot>,
        useSyncIds: Boolean
    ): MergeOutcome {
        val localCategories = ledgerRepository.getAllCategoriesSnapshot()
        val localByKey = localCategories.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
        val idByKey = HashMap(localByKey.mapValues { it.value.id })
        var added = 0
        var updated = 0

        snapshots.forEach { snapshot ->
            val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
            val local = localByKey[key]
            if (local == null) {
                val newId = ledgerRepository.insertSyncedCategory(
                    LedgerCategory(
                        id = 0,
                        name = snapshot.name,
                        icon = snapshot.icon,
                        kind = snapshot.kind,
                        sort = snapshot.sort,
                        parentId = null,
                        isProtected = snapshot.isProtected,
                        syncId = snapshot.syncId.orNewSyncId(),
                        updatedAt = snapshot.updatedAt ?: System.currentTimeMillis(),
                        deletedAt = snapshot.deletedAt
                    )
                )
                idByKey[key] = newId
                added++
            } else {
                idByKey[key] = local.id
                if (!isRemoteNewer(snapshot.updatedAt, local.updatedAt)) return@forEach
                ledgerRepository.updateSyncedCategory(
                    local.copy(
                        name = snapshot.name,
                        icon = snapshot.icon,
                        kind = snapshot.kind,
                        sort = snapshot.sort,
                        updatedAt = snapshot.updatedAt ?: local.updatedAt,
                        deletedAt = snapshot.deletedAt,
                        isProtected = local.isProtected || snapshot.isProtected
                    )
                )
                updated++
            }
        }
        return MergeOutcome(added, updated, idByKey)
    }

    /** 记账账户合并：无引用字段，单遍 LWW。 */
    private suspend fun mergeLedgerAssets(
        snapshots: List<LedgerAssetSnapshot>,
        useSyncIds: Boolean
    ): MergeOutcome {
        val localAssets = ledgerRepository.getAllAssetsSnapshot()
        val localByKey = localAssets.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
        val idByKey = HashMap(localByKey.mapValues { it.value.id })
        var added = 0
        var updated = 0

        snapshots.forEach { snapshot ->
            val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
            val local = localByKey[key]
            if (local == null) {
                val newId = ledgerRepository.insertSyncedAsset(
                    LedgerAsset(
                        id = 0,
                        name = snapshot.name,
                        icon = snapshot.icon,
                        sort = snapshot.sort,
                        initialBalance = snapshot.initialBalance,
                        type = snapshot.type,
                        syncId = snapshot.syncId.orNewSyncId(),
                        updatedAt = snapshot.updatedAt ?: System.currentTimeMillis(),
                        deletedAt = snapshot.deletedAt
                    )
                )
                idByKey[key] = newId
                added++
            } else {
                idByKey[key] = local.id
                if (!isRemoteNewer(snapshot.updatedAt, local.updatedAt)) return@forEach
                ledgerRepository.updateSyncedAsset(
                    local.copy(
                        name = snapshot.name,
                        icon = snapshot.icon,
                        sort = snapshot.sort,
                        initialBalance = snapshot.initialBalance,
                        type = snapshot.type,
                        updatedAt = snapshot.updatedAt ?: local.updatedAt,
                        deletedAt = snapshot.deletedAt
                    )
                )
                updated++
            }
        }
        return MergeOutcome(added, updated, idByKey)
    }

    /**
     * 账单合并：分类/账户/关联物品按 syncId 重映射到本地 id。
     * [itemKeyById] 来自物品合并的结果（已预填充本地全量），
     * 记账分类与账户的 idByKey 同样包含本地全量，增量包也能解析。
     */
    private suspend fun mergeLedgerRecords(
        snapshots: List<LedgerRecordSnapshot>,
        useSyncIds: Boolean,
        categoryIdByKey: Map<String, Long>,
        assetIdByKey: Map<String, Long>,
        itemKeyById: Map<String, Long>
    ): MergeOutcome {
        val localRecords = ledgerRepository.getAllRecordsSnapshot()
        val localByKey = localRecords.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
        val idByKey = HashMap(localByKey.mapValues { it.value.id })
        var added = 0
        var updated = 0

        snapshots.forEach { snapshot ->
            val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
            val local = localByKey[key]
            val categoryId = resolveReferenceId(
                syncId = snapshot.categorySyncId,
                fallbackId = snapshot.categoryId,
                idByKey = categoryIdByKey,
                useSyncIds = useSyncIds
            )
            val assetId = resolveReferenceId(
                syncId = snapshot.assetSyncId,
                fallbackId = snapshot.assetId,
                idByKey = assetIdByKey,
                useSyncIds = useSyncIds
            )
            val targetAssetId = resolveReferenceId(
                syncId = snapshot.targetAssetSyncId,
                fallbackId = snapshot.targetAssetId,
                idByKey = assetIdByKey,
                useSyncIds = useSyncIds
            )
            val itemId = resolveReferenceId(
                syncId = snapshot.itemSyncId,
                fallbackId = snapshot.itemId,
                idByKey = itemKeyById,
                useSyncIds = useSyncIds
            )
            if (local == null) {
                ledgerRepository.insertSyncedRecord(
                    LedgerRecord(
                        id = 0,
                        type = snapshot.type,
                        amount = snapshot.amount,
                        categoryId = categoryId,
                        assetId = assetId,
                        targetAssetId = targetAssetId,
                        recordTime = snapshot.recordTime,
                        remark = snapshot.remark,
                        itemId = itemId,
                        createdAt = snapshot.createdAt,
                        syncId = snapshot.syncId.orNewSyncId(),
                        updatedAt = snapshot.updatedAt ?: snapshot.createdAt,
                        deletedAt = snapshot.deletedAt
                    )
                )
                added++
            } else {
                idByKey[key] = local.id
                if (!isRemoteNewer(snapshot.updatedAt, local.updatedAt)) return@forEach
                ledgerRepository.updateSyncedRecord(
                    local.copy(
                        type = snapshot.type,
                        amount = snapshot.amount,
                        categoryId = categoryId,
                        assetId = assetId,
                        targetAssetId = targetAssetId,
                        recordTime = snapshot.recordTime,
                        remark = snapshot.remark,
                        itemId = itemId,
                        updatedAt = snapshot.updatedAt ?: local.updatedAt,
                        deletedAt = snapshot.deletedAt
                    )
                )
                updated++
            }
        }
        return MergeOutcome(added, updated, idByKey)
    }

    /** 预算合并：LWW 覆盖式更新；categoryId 冲突由 REPLACE 兜底（远端胜出）。 */
    private suspend fun mergeLedgerBudgets(
        snapshots: List<LedgerBudgetSnapshot>,
        useSyncIds: Boolean,
        categoryIdByKey: Map<String, Long>
    ): MergeOutcome {
        val localBudgets = ledgerRepository.getAllBudgetsSnapshot()
        val localByKey = localBudgets.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
        var added = 0
        var updated = 0

        snapshots.forEach { snapshot ->
            val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
            val local = localByKey[key]
            val categoryId = resolveReferenceId(
                syncId = snapshot.categorySyncId,
                fallbackId = snapshot.categoryId,
                idByKey = categoryIdByKey,
                useSyncIds = useSyncIds
            )
            if (local == null) {
                ledgerRepository.insertSyncedBudget(
                    LedgerBudget(
                        id = 0,
                        categoryId = categoryId,
                        amount = snapshot.amount,
                        period = snapshot.period,
                        syncId = snapshot.syncId.orNewSyncId(),
                        updatedAt = snapshot.updatedAt ?: System.currentTimeMillis(),
                        deletedAt = null
                    )
                )
                added++
            } else {
                if (!isRemoteNewer(snapshot.updatedAt, local.updatedAt)) return@forEach
                ledgerRepository.updateSyncedBudget(
                    local.copy(
                        categoryId = categoryId,
                        amount = snapshot.amount,
                        period = snapshot.period,
                        updatedAt = snapshot.updatedAt ?: local.updatedAt,
                        deletedAt = null
                    )
                )
                updated++
            }
        }
        return MergeOutcome(added, updated, emptyMap())
    }

    /**
     * 纪念日合并：无引用字段，单遍 LWW；通知去重标记 lastNotifiedDate 是设备本地状态，
     * 不进快照、不参与合并。
     */
    private suspend fun mergeAnniversaries(
        snapshots: List<AnniversarySnapshot>,
        useSyncIds: Boolean
    ): MergeOutcome {
        val localAnniversaries = anniversaryRepository.getAllSnapshot()
        val localByKey = localAnniversaries.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
        var added = 0
        var updated = 0

        snapshots.forEach { snapshot ->
            val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
            val local = localByKey[key]
            if (local == null) {
                anniversaryRepository.insertSynced(
                    Anniversary(
                        id = 0,
                        name = snapshot.name,
                        note = snapshot.note,
                        date = snapshot.date,
                        isLunar = snapshot.isLunar,
                        lunarMonth = snapshot.lunarMonth,
                        lunarDay = snapshot.lunarDay,
                        type = snapshot.type,
                        repeatUnit = Anniversary.normalizeRepeatUnit(snapshot.repeatUnit),
                        repeatInterval = (snapshot.repeatInterval ?: 1).coerceAtLeast(1),
                        remindDays = snapshot.remindDays,
                        enabled = snapshot.enabled,
                        createdAt = snapshot.createdAt,
                        syncId = snapshot.syncId.orNewSyncId(),
                        updatedAt = snapshot.updatedAt ?: snapshot.createdAt,
                        deletedAt = snapshot.deletedAt
                    )
                )
                added++
            } else {
                if (!isRemoteNewer(snapshot.updatedAt, local.updatedAt)) return@forEach
                anniversaryRepository.updateSynced(
                    local.copy(
                        name = snapshot.name,
                        note = snapshot.note,
                        date = snapshot.date,
                        isLunar = snapshot.isLunar,
                        lunarMonth = snapshot.lunarMonth,
                        lunarDay = snapshot.lunarDay,
                        type = snapshot.type,
                        repeatUnit = Anniversary.normalizeRepeatUnit(snapshot.repeatUnit),
                        repeatInterval = (snapshot.repeatInterval ?: 1).coerceAtLeast(1),
                        remindDays = snapshot.remindDays,
                        enabled = snapshot.enabled,
                        updatedAt = snapshot.updatedAt ?: local.updatedAt,
                        deletedAt = snapshot.deletedAt
                    )
                )
                updated++
            }
        }
        return MergeOutcome(added, updated, emptyMap())
    }

    /**
     * 待办提醒合并：单遍 LWW。nextFireAt 是调度状态，导入时若原值不可用则按提醒规则重算；
     * notifiedAt 是设备本地的通知状态，不进快照。
     */
    private suspend fun mergeReminders(
        snapshots: List<ReminderSnapshot>,
        useSyncIds: Boolean
    ): MergeOutcome {
        val localReminders = reminderRepository.getAllSnapshot()
        val localByKey = localReminders.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
        var added = 0
        var updated = 0

        snapshots.forEach { snapshot ->
            val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
            val local = localByKey[key]
            if (local == null) {
                val base = snapshot.toReminder(targetId = 0)
                val fireAt = base.nextFireAt.takeIf { it > 0 }
                    ?: ReminderClock.firstFireAt(base)
                    ?: 0L
                reminderRepository.insertSynced(base.copy(nextFireAt = fireAt))
                added++
            } else {
                if (!isRemoteNewer(snapshot.updatedAt, local.updatedAt)) return@forEach
                reminderRepository.updateSynced(
                    snapshot.toReminder(targetId = local.id).copy(
                        syncId = local.syncId ?: snapshot.syncId.orNewSyncId(),
                        nextFireAt = snapshot.nextFireAt.takeIf { it > 0 } ?: local.nextFireAt
                    )
                )
                updated++
            }
        }
        return MergeOutcome(added, updated, emptyMap())
    }

    private fun resolveImages(relativePaths: List<String>, workingDir: File): List<String> {
        return relativePaths.mapNotNull { relativePath ->
            val sourceFile = File(workingDir, relativePath)
            if (!sourceFile.exists()) {
                null
            } else {
                ImageUtil.copyImageInto(
                    context = context,
                    sourcePath = sourceFile.absolutePath,
                    targetName = "import_${UUID.randomUUID()}.jpg"
                )
            }
        }
    }

    private fun resolveParentKey(
        parentSyncId: String?,
        parentId: Long?,
        idByKey: Map<String, Long>,
        useSyncIds: Boolean
    ): Long? {
        val key = if (useSyncIds) {
            parentSyncId.orBlank()?.let { "sync:$it" }
        } else {
            parentId?.let { "id:$it" }
        }
        return key?.let { idByKey[it] }
    }

    private fun resolveReferenceId(
        syncId: String?,
        fallbackId: Long?,
        idByKey: Map<String, Long>,
        useSyncIds: Boolean
    ): Long? {
        val key = if (useSyncIds) {
            syncId.orBlank()?.let { "sync:$it" } ?: fallbackId?.let { "id:$it" }
        } else {
            fallbackId?.let { "id:$it" }
        }
        return key?.let { idByKey[it] }
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

    private data class MergeOutcome(
        val added: Int,
        val updated: Int,
        val idByKey: Map<String, Long>,
        val kept: Int = 0
    )

    private data class ItemMergeOutcome(
        val added: Int,
        val updated: Int,
        val kept: Int,
        val idByKey: Map<String, Long>
    )

    private data class ImageEntry(
        val relativePath: String,
        val file: File
    )

    companion object {
        private const val BACKUP_MANIFEST_NAME = "backup.json"

        /** 支持合并的备份格式版本；缺失该字段的旧备份按 v1（仅数字 id 对齐）处理。
         * v7：纪念日类型 type（倒数日/正数日/生日），不再导出 v5 的 repeatYearly 旧字段。 */
        const val SYNC_VERSION = 7

        /** 从该版本起备份用 syncId 对齐；更早的备份退回数字 id。 */
        private const val SYNC_ID_VERSION = 2

        /** 对齐键：新版优先用 syncId，旧备份退回数字 id。 */
        private fun keyOf(syncId: String?, id: Long, useSyncIds: Boolean): String {
            val normalized = syncId.orBlank()
            return if (useSyncIds && normalized != null) "sync:$normalized" else "id:$id"
        }

        private fun isRemoteNewer(remote: Long?, local: Long?): Boolean {
            if (remote == null) return false
            if (local == null) return true
            return remote > local
        }

        private fun String?.orBlank(): String? = this?.takeIf { it.isNotBlank() }

        private fun String?.orNewSyncId(): String = orBlank() ?: UUID.randomUUID().toString()
    }
}

/**
 * 备份清单。旧备份里可能还带 `aiModels`、`embeddings` 字段，Json 配置了
 * ignoreUnknownKeys，因此反序列化时会自动忽略，不影响导入。
 */
@Serializable
data class AppBackupPayload(
    val exportedAt: Long,
    val syncVersion: Int = 1,
    val deviceId: String = "",
    val categories: List<CategorySnapshot>,
    val locations: List<LocationSnapshot>,
    val items: List<ItemSnapshot>,
    // 记账模块，v4 起导出；旧版本 App 读新备份时按空列表忽略。
    val ledgerAssets: List<LedgerAssetSnapshot> = emptyList(),
    val ledgerCategories: List<LedgerCategorySnapshot> = emptyList(),
    val ledgerRecords: List<LedgerRecordSnapshot> = emptyList(),
    val ledgerBudgets: List<LedgerBudgetSnapshot> = emptyList(),
    // 纪念日与待办提醒，v5 起导出。
    val anniversaries: List<AnniversarySnapshot> = emptyList(),
    val reminders: List<ReminderSnapshot> = emptyList(),
    // 菜谱/周菜单/自定义状态/提醒设置等偏好内容，v5 起导出。
    val preferences: PreferencesSnapshot? = null
)

/** 用户自定义状态的快照形式（dimension 存枚举名，导入端解析）。 */
@Serializable
data class CustomStatusSnapshot(
    val code: Int,
    val label: String,
    val dimension: String
) {
    fun toOption(): ItemStatusOption? {
        val dimension = runCatching { StatusDimension.valueOf(dimension) }.getOrNull()
            ?: return null
        return ItemStatusOption(
            code = code,
            label = label,
            dimension = dimension,
            isBuiltIn = false
        )
    }

    companion object {
        fun fromOption(option: ItemStatusOption): CustomStatusSnapshot = CustomStatusSnapshot(
            code = option.code,
            label = option.label,
            dimension = option.dimension.name
        )
    }
}

/**
 * 偏好内容快照（v5 起）：菜谱库、周菜单、已做日期、自定义状态与提醒相关设置。
 * 外观/录入等设备个性化设置不进备份。
 */
@Serializable
data class PreferencesSnapshot(
    val recipes: List<Recipe> = emptyList(),
    val weeklyMenu: Map<String, MealSpec> = emptyMap(),
    val cookedMenuDates: Set<String> = emptySet(),
    val customStatuses: List<CustomStatusSnapshot> = emptyList(),
    val reminderLadder: List<Int> = emptyList(),
    val reminderTimes: List<String> = emptyList(),
    val customReminderTimes: Set<String> = emptySet(),
    val mealPrepDayShift: Int? = null,
    val mealPrepFireTime: String? = null
)

/**
 * 纪念日快照；lastNotifiedDate 是设备本地通知状态，不导出。
 * type 是 v7 起的类型（倒数日/正数日/生日）；repeatUnit/repeatInterval 是 v6 起的重复周期。
 */
@Serializable
data class AnniversarySnapshot(
    val id: Long,
    val name: String,
    val note: String = "",
    /** 类型：TYPE_COUNTDOWN/TYPE_COUNTUP/TYPE_BIRTHDAY；旧快照缺省按倒数日。 */
    val type: Int = Anniversary.TYPE_COUNTDOWN,
    val date: String,
    val isLunar: Boolean = false,
    val lunarMonth: Int = 0,
    val lunarDay: Int = 0,
    val repeatUnit: String? = null,
    val repeatInterval: Int? = null,
    val remindDays: String = "",
    val enabled: Boolean = true,
    val createdAt: Long,
    val syncId: String? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null
) {
    companion object {
        fun fromEntity(anniversary: Anniversary): AnniversarySnapshot = AnniversarySnapshot(
            id = anniversary.id,
            name = anniversary.name,
            note = anniversary.note,
            type = anniversary.type,
            date = anniversary.date,
            isLunar = anniversary.isLunar,
            lunarMonth = anniversary.lunarMonth,
            lunarDay = anniversary.lunarDay,
            repeatUnit = anniversary.repeatUnit,
            repeatInterval = anniversary.repeatInterval,
            remindDays = anniversary.remindDays,
            enabled = anniversary.enabled,
            createdAt = anniversary.createdAt,
            syncId = anniversary.syncId,
            updatedAt = anniversary.updatedAt,
            deletedAt = anniversary.deletedAt
        )
    }
}

/** 待办提醒快照；notifiedAt 是设备本地通知状态，不导出。 */
@Serializable
data class ReminderSnapshot(
    val id: Long,
    val title: String,
    val note: String = "",
    val repeatType: String = "ONCE",
    val intervalDays: Int = 1,
    val weekdays: String = "",
    val fireTime: String = "19:00",
    val targetDate: String? = null,
    val nextFireAt: Long = 0,
    val enabled: Boolean = true,
    val completedAt: Long? = null,
    val source: String = "MANUAL",
    val sourceKey: String? = null,
    val createdAt: Long,
    val syncId: String? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null
) {
    /** 还原成实体；nextFireAt 保留原值（0 时由导入端重算）。 */
    fun toReminder(targetId: Long): Reminder = Reminder(
        id = targetId,
        title = title,
        note = note,
        repeatType = repeatType,
        intervalDays = intervalDays,
        weekdays = weekdays,
        fireTime = fireTime,
        targetDate = targetDate,
        nextFireAt = nextFireAt,
        enabled = enabled,
        completedAt = completedAt,
        source = source,
        sourceKey = sourceKey,
        createdAt = createdAt,
        syncId = syncId,
        updatedAt = updatedAt ?: createdAt,
        deletedAt = deletedAt
    )

    companion object {
        fun fromEntity(reminder: Reminder): ReminderSnapshot = ReminderSnapshot(
            id = reminder.id,
            title = reminder.title,
            note = reminder.note,
            repeatType = reminder.repeatType,
            intervalDays = reminder.intervalDays,
            weekdays = reminder.weekdays,
            fireTime = reminder.fireTime,
            targetDate = reminder.targetDate,
            nextFireAt = reminder.nextFireAt,
            enabled = reminder.enabled,
            completedAt = reminder.completedAt,
            source = reminder.source,
            sourceKey = reminder.sourceKey,
            createdAt = reminder.createdAt,
            syncId = reminder.syncId,
            updatedAt = reminder.updatedAt,
            deletedAt = reminder.deletedAt
        )
    }
}

@Serializable
data class LedgerAssetSnapshot(
    val id: Long,
    val name: String,
    val icon: String = "",
    val sort: Int = 0,
    val initialBalance: Long = 0,
    val type: Int = 0,
    val syncId: String? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null
) {
    companion object {
        fun fromEntity(asset: LedgerAsset): LedgerAssetSnapshot = LedgerAssetSnapshot(
            id = asset.id,
            name = asset.name,
            icon = asset.icon,
            sort = asset.sort,
            initialBalance = asset.initialBalance,
            type = asset.type,
            syncId = asset.syncId,
            updatedAt = asset.updatedAt,
            deletedAt = asset.deletedAt
        )
    }
}

@Serializable
data class LedgerCategorySnapshot(
    val id: Long,
    val name: String,
    val icon: String = "",
    val kind: Int = 0,
    val sort: Int = 0,
    /** 预留二级分类；v1 恒为 null，导出保留字段以兼容后续扩展。 */
    val parentId: Long? = null,
    val isProtected: Boolean = false,
    val syncId: String? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null
) {
    companion object {
        fun fromEntity(category: LedgerCategory): LedgerCategorySnapshot =
            LedgerCategorySnapshot(
                id = category.id,
                name = category.name,
                icon = category.icon,
                kind = category.kind,
                sort = category.sort,
                parentId = category.parentId,
                isProtected = category.isProtected,
                syncId = category.syncId,
                updatedAt = category.updatedAt,
                deletedAt = category.deletedAt
            )
    }
}

@Serializable
data class LedgerRecordSnapshot(
    val id: Long,
    val type: Int,
    /** 金额（分），恒为正。 */
    val amount: Long,
    val categoryId: Long? = null,
    val assetId: Long? = null,
    val targetAssetId: Long? = null,
    val recordTime: Long,
    val remark: String = "",
    val itemId: Long? = null,
    val createdAt: Long,
    val syncId: String? = null,
    /** 引用一律以 syncId 导出，导入端重映射到本地 id。 */
    val categorySyncId: String? = null,
    val assetSyncId: String? = null,
    val targetAssetSyncId: String? = null,
    val itemSyncId: String? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null
) {
    companion object {
        fun fromEntity(
            record: LedgerRecord,
            categorySyncId: String?,
            assetSyncId: String?,
            targetAssetSyncId: String?,
            itemSyncId: String?
        ): LedgerRecordSnapshot = LedgerRecordSnapshot(
            id = record.id,
            type = record.type,
            amount = record.amount,
            categoryId = record.categoryId,
            assetId = record.assetId,
            targetAssetId = record.targetAssetId,
            recordTime = record.recordTime,
            remark = record.remark,
            itemId = record.itemId,
            createdAt = record.createdAt,
            syncId = record.syncId,
            categorySyncId = categorySyncId,
            assetSyncId = assetSyncId,
            targetAssetSyncId = targetAssetSyncId,
            itemSyncId = itemSyncId,
            updatedAt = record.updatedAt,
            deletedAt = record.deletedAt
        )
    }
}

@Serializable
data class LedgerBudgetSnapshot(
    val id: Long,
    /** null 表示月度总预算。 */
    val categoryId: Long? = null,
    val categorySyncId: String? = null,
    val amount: Long,
    val period: String = "monthly",
    val syncId: String? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null
) {
    companion object {
        fun fromEntity(budget: LedgerBudget, categorySyncId: String?): LedgerBudgetSnapshot =
            LedgerBudgetSnapshot(
                id = budget.id,
                categoryId = budget.categoryId,
                categorySyncId = categorySyncId,
                amount = budget.amount,
                period = budget.period,
                syncId = budget.syncId,
                updatedAt = budget.updatedAt,
                deletedAt = budget.deletedAt
            )
    }
}

@Serializable
data class CategorySnapshot(
    val id: Long,
    val name: String,
    val icon: String,
    val parentId: Long? = null,
    val syncId: String? = null,
    /** 上级分类的 syncId，用于导入端把层级重映射到本地 id。 */
    val parentSyncId: String? = null,
    val updatedAt: Long? = null,
    /** 软删除墓碑；非空表示该分类已在来源设备删除。 */
    val deletedAt: Long? = null,
    /** 内置保护分类（食品及其食材子树）；v4 起导出，旧备份缺省为不保护。 */
    val isProtected: Boolean = false
) {
    companion object {
        fun fromEntity(category: Category, parentSyncId: String? = null): CategorySnapshot {
            return CategorySnapshot(
                id = category.id,
                name = category.name,
                icon = category.icon,
                parentId = category.parentId,
                syncId = category.syncId,
                parentSyncId = parentSyncId,
                updatedAt = category.updatedAt,
                deletedAt = category.deletedAt,
                isProtected = category.isProtected
            )
        }
    }
}

@Serializable
data class LocationSnapshot(
    val id: Long,
    val name: String,
    val parentId: Long?,
    val syncId: String? = null,
    val parentSyncId: String? = null,
    val updatedAt: Long? = null,
    /** 软删除墓碑；非空表示该位置已在来源设备删除。 */
    val deletedAt: Long? = null,
    /** 内置保护位置（冰箱）；v4 起导出，旧备份缺省为不保护。 */
    val isProtected: Boolean = false
) {
    companion object {
        fun fromEntity(location: Location, parentSyncId: String?): LocationSnapshot {
            return LocationSnapshot(
                id = location.id,
                name = location.name,
                parentId = location.parentId,
                syncId = location.syncId,
                parentSyncId = parentSyncId,
                updatedAt = location.updatedAt,
                deletedAt = location.deletedAt,
                isProtected = location.isProtected
            )
        }
    }
}

@Serializable
data class ItemSnapshot(
    val id: Long,
    val name: String,
    val barcode: String? = null,
    val categoryId: Long? = null,
    val locationId: Long? = null,
    val quantity: Int,
    /** 已消耗数量（按件消耗累计）；为 null 表示备份里没有（旧版本）。 */
    val consumedQuantity: Int? = null,
    val unit: String,
    val price: Double?,
    val expireTime: Long?,
    /** 旧版单值状态，v2 及更早的备份才有；仅在导入时用于推导三维状态。 */
    val status: Int? = null,
    /** 使用进度；v3 起导出。为 null 表示备份里没有该维度。 */
    val usageStatus: Int? = null,
    /** 物品去向；v3 起导出。 */
    val disposition: Int? = null,
    /** 需要补货；v3 起导出。 */
    val needRestock: Boolean? = null,
    /** 购买日期；使用周期统计，为 null 表示备份里没有。 */
    val purchaseDate: Long? = null,
    /** 开始使用时间；使用周期统计，为 null 表示备份里没有。 */
    val startUseTime: Long? = null,
    /** 使用结束时间；使用周期统计，为 null 表示备份里没有。 */
    val usageEndedAt: Long? = null,
    /** 计量方式（0=按件消耗 1=持续使用）；为 null 表示备份里没有。 */
    val trackMode: Int? = null,
    val rating: Int?,
    val ratedAt: Long?,
    val deletedAt: Long?,
    val note: String,
    val imagePaths: List<String>,
    val createdAt: Long,
    val syncId: String? = null,
    val categorySyncId: String? = null,
    val locationSyncId: String? = null,
    val updatedAt: Long? = null
) {
    fun toItem(
        targetId: Long,
        resolvedImages: List<String>,
        categoryId: Long?,
        locationId: Long?
    ): Item {
        val state = resolvedState()
        return Item(
            id = targetId,
            name = name,
            barcode = barcode,
            categoryId = categoryId,
            locationId = locationId,
            quantity = quantity,
            consumedQuantity = consumedQuantity ?: 0,
            unit = unit,
            price = price,
            expireTime = expireTime,
            usageStatus = state.usageStatus,
            disposition = state.disposition,
            needRestock = state.needRestock,
            purchaseDate = purchaseDate,
            startUseTime = startUseTime,
            usageEndedAt = usageEndedAt,
            trackMode = trackMode ?: Item.TRACK_CONSUMABLE,
            rating = rating,
            ratedAt = ratedAt,
            deletedAt = deletedAt,
            note = note,
            imagePath = resolvedImages.firstOrNull().orEmpty(),
            imagePaths = Item.encodeImagePaths(resolvedImages),
            createdAt = createdAt,
            syncId = syncId,
            updatedAt = updatedAt ?: createdAt
        )
    }

    /**
     * 还原三维状态：
     * - v3 备份直接带 usageStatus/disposition/needRestock；
     * - v2 及更早只有单值 status，按 [Item.fromLegacyStatus] 的映射表推导；
     * - 两者都没有时用「使用中 + 在库 + 不需要补货」兜底。
     */
    private fun resolvedState(): LegacyStatusMapping {
        val usage = usageStatus
        if (usage != null) {
            return LegacyStatusMapping(
                usageStatus = usage,
                disposition = disposition ?: Item.DISPOSITION_IN_STOCK,
                needRestock = needRestock ?: false
            )
        }
        status?.let { return Item.fromLegacyStatus(it) }
        return LegacyStatusMapping(
            usageStatus = Item.USAGE_IN_USE,
            disposition = Item.DISPOSITION_IN_STOCK,
            needRestock = false
        )
    }

    companion object {
        fun fromEntity(
            item: Item,
            imagePaths: List<String>,
            categorySyncId: String?,
            locationSyncId: String?
        ): ItemSnapshot {
            return ItemSnapshot(
                id = item.id,
                name = item.name,
                barcode = item.barcode,
                categoryId = item.categoryId,
                locationId = item.locationId,
                quantity = item.quantity,
                consumedQuantity = item.consumedQuantity,
                unit = item.unit,
                price = item.price,
                expireTime = item.expireTime,
                usageStatus = item.usageStatus,
                disposition = item.disposition,
                needRestock = item.needRestock,
                purchaseDate = item.purchaseDate,
                startUseTime = item.startUseTime,
                usageEndedAt = item.usageEndedAt,
                trackMode = item.trackMode,
                rating = item.rating,
                ratedAt = item.ratedAt,
                deletedAt = item.deletedAt,
                note = item.note,
                imagePaths = imagePaths,
                createdAt = item.createdAt,
                syncId = item.syncId,
                categorySyncId = categorySyncId,
                locationSyncId = locationSyncId,
                updatedAt = item.updatedAt
            )
        }
    }
}