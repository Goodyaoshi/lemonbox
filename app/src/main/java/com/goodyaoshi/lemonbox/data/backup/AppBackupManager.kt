package com.goodyaoshi.lemonbox.data.backup

import android.content.Context
import android.net.Uri
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.LegacyStatusMapping
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LocationRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.ImageUtil
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
    val fromLegacyBackup: Boolean
) {
    val hasChanges: Boolean
        get() = itemAdded + itemUpdated + categoryAdded + categoryUpdated +
            locationAdded + locationUpdated > 0
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

            return BackupMergeResult(
                itemAdded = itemOutcome.added,
                itemUpdated = itemOutcome.updated,
                itemKept = itemOutcome.kept,
                categoryAdded = categoryOutcome.added,
                categoryUpdated = categoryOutcome.updated,
                locationAdded = locationOutcome.added,
                locationUpdated = locationOutcome.updated,
                fromLegacyBackup = !useSyncIds
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

        val categorySyncIdById = allCategories.associate { it.id to it.syncId }
        val locationSyncIdById = allLocations.associate { it.id to it.syncId }

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
            }
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

        /** 支持合并的备份格式版本；缺失该字段的旧备份按 v1（仅数字 id 对齐）处理。 */
        const val SYNC_VERSION = 3

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
    val items: List<ItemSnapshot>
)

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
            unit = unit,
            price = price,
            expireTime = expireTime,
            usageStatus = state.usageStatus,
            disposition = state.disposition,
            needRestock = state.needRestock,
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
                unit = item.unit,
                price = item.price,
                expireTime = item.expireTime,
                usageStatus = item.usageStatus,
                disposition = item.disposition,
                needRestock = item.needRestock,
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