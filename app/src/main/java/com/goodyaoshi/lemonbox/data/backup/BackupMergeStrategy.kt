package com.goodyaoshi.lemonbox.data.backup

import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset
import com.goodyaoshi.lemonbox.data.local.entity.LedgerBudget
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.util.ImageUtil
import com.goodyaoshi.lemonbox.util.ReminderClock
import java.io.File
import java.util.UUID

/**
 * 备份合并策略（F9）：把一份远端快照按 syncId 对齐、LWW 落地到本地库。
 *
 * 拆成 [AppBackupManager] 的扩展函数，是为了在「不改动调用方、不新增构造函数参数」
 * 的前提下把这段近 600 行的合并规则从装配代码里挪出来：
 * 导入流程仍按 `mergeItems(...)` 这样直呼其名，读代码的人却可以只打开本文件看完合并规则。
 * 为此 [AppBackupManager] 把合并所需的仓库与 Context 放宽到 `internal`（同模块可见）。
 */

internal data class MergeOutcome(
    val added: Int,
    val updated: Int,
    val idByKey: Map<String, Long>,
    val kept: Int = 0
)

internal data class ItemMergeOutcome(
    val added: Int,
    val updated: Int,
    val kept: Int,
    val idByKey: Map<String, Long>
)

/** 对齐键：新版优先用 syncId，旧备份退回数字 id。 */
internal fun keyOf(syncId: String?, id: Long, useSyncIds: Boolean): String {
    val normalized = syncId.orBlank()
    return if (useSyncIds && normalized != null) "sync:$normalized" else "id:$id"
}

internal fun isRemoteNewer(remote: Long?, local: Long?): Boolean {
    if (remote == null) return false
    if (local == null) return true
    return remote > local
}

internal fun String?.orBlank(): String? = this?.takeIf { it.isNotBlank() }

internal fun String?.orNewSyncId(): String = orBlank() ?: UUID.randomUUID().toString()

internal suspend fun AppBackupManager.mergeCategories(
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

internal suspend fun AppBackupManager.mergeLocations(
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

internal suspend fun AppBackupManager.mergeItems(
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
internal suspend fun AppBackupManager.mergeLedgerCategories(
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
internal suspend fun AppBackupManager.mergeLedgerAssets(
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
internal suspend fun AppBackupManager.mergeLedgerRecords(
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
internal suspend fun AppBackupManager.mergeLedgerBudgets(
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
internal suspend fun AppBackupManager.mergeAnniversaries(
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
internal suspend fun AppBackupManager.mergeReminders(
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

internal fun AppBackupManager.resolveImages(relativePaths: List<String>, workingDir: File): List<String> {
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

internal fun AppBackupManager.resolveParentKey(
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

internal fun AppBackupManager.resolveReferenceId(
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
