package com.goodyaoshi.lemonbox.data.backup

import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset
import com.goodyaoshi.lemonbox.data.local.entity.LedgerBudget
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecurringRule
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.util.ReminderClock
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
    useSyncIds: Boolean,
    markDirty: Boolean
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
                ),
                markDirty = markDirty
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
        // 受保护分类（食品及其食材子树）是菜谱匹配与默认规则的基础：本地已存在的
        // 受保护分类无论远端是什么状态，都不允许被同步改写名称/父级，也不允许被
        // 远端的墓碑（deletedAt）软删。
        // 但本轮新插入的受保护分类必须例外：它刚以 parentId = null 落库，仍需在这一遍
        // 修正父级，否则「番茄」会停留在顶级成为孤儿、与本地同名项无法按路径对齐。
        val isNew = key in insertedKeys
        if (local.isProtected && !isNew) return@forEach
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
            ),
            markDirty = markDirty
        )
        if (!isNew) updated++
    }

    return MergeOutcome(added, updated, idByKey)
}

internal suspend fun AppBackupManager.mergeLocations(
    snapshots: List<LocationSnapshot>,
    useSyncIds: Boolean,
    markDirty: Boolean
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
                ),
                markDirty = markDirty
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
        // 受保护位置（冰箱）是「今天吃什么」取材规则的基础：本地已存在的受保护位置
        // 无论远端是什么状态，都不允许被同步改写名称/父级，也不允许被远端的墓碑软删。
        // 同理，本轮新插入的受保护位置仍需修正父级，故仅在非新插入时才跳过。
        val isNew = key in insertedKeys
        if (local.isProtected && !isNew) return@forEach
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
            ),
            markDirty = markDirty
        )
        if (!isNew) updated++
    }

    return MergeOutcome(added, updated, idByKey)
}

internal suspend fun AppBackupManager.mergeItems(
    snapshots: List<ItemSnapshot>,
    useSyncIds: Boolean,
    categoryIdByKey: Map<String, Long>,
    locationIdByKey: Map<String, Long>,
    markDirty: Boolean
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
        // 永久删除墓碑（F21）：对方已彻底删除这条。本方直接把本地那份物理清掉，
        // 既不重新插入、也不当成「回收站里的软删」——否则删除就永远传播不过来。
        if (snapshot.purgedAt != null) {
            if (local != null) {
                itemRepository.purgeDeletedByIds(listOf(local.id))
            }
            local?.let { idByKey[key] = it.id }
            return@forEach
        }
        if (local == null) {
            val newId = itemRepository.insertSynced(
                snapshot.toItem(
                    targetId = 0,
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
                ),
                markDirty = markDirty
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
            itemRepository.updateSynced(
                snapshot.toItem(
                    targetId = local.id,
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
                ).copy(syncId = local.syncId ?: snapshot.syncId.orNewSyncId()),
                markDirty = markDirty
            )
            updated++
        }
    }

    return ItemMergeOutcome(added, updated, kept, idByKey)
}

/** 记账分类合并：扁平结构（parentId 恒为 null），单遍即可；保护位只增不减。 */
internal suspend fun AppBackupManager.mergeLedgerCategories(
    snapshots: List<LedgerCategorySnapshot>,
    useSyncIds: Boolean,
    markDirty: Boolean
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
                ),
                markDirty = markDirty
            )
            idByKey[key] = newId
            added++
        } else {
            idByKey[key] = local.id
            // 受保护记账分类是账目统计的基础，不允许被同步改写或软删。
            if (local.isProtected) return@forEach
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
                ),
                markDirty = markDirty
            )
            updated++
        }
    }
    return MergeOutcome(added, updated, idByKey)
}

/** 记账账户合并：无引用字段，单遍 LWW。 */
internal suspend fun AppBackupManager.mergeLedgerAssets(
    snapshots: List<LedgerAssetSnapshot>,
    useSyncIds: Boolean,
    markDirty: Boolean
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
                ),
                markDirty = markDirty
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
                ),
                markDirty = markDirty
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
    itemKeyById: Map<String, Long>,
    markDirty: Boolean
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
                ),
                markDirty = markDirty
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
                ),
                markDirty = markDirty
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
    categoryIdByKey: Map<String, Long>,
    markDirty: Boolean
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
            // 远端墓碑且本地从未有过这条：无需落库——本就没有它，凭空插一条墓碑只是噪声。
            if (snapshot.deletedAt != null) return@forEach
            ledgerRepository.insertSyncedBudget(
                LedgerBudget(
                    id = 0,
                    categoryId = categoryId,
                    amount = snapshot.amount,
                    period = snapshot.period,
                    syncId = snapshot.syncId.orNewSyncId(),
                    updatedAt = snapshot.updatedAt ?: System.currentTimeMillis(),
                    deletedAt = null
                ),
                markDirty = markDirty
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
                    // 传播墓碑：对方取消预算（或删除）后，本端也据此隐藏，而不是把旧预算留着。
                    deletedAt = snapshot.deletedAt
                ),
                markDirty = markDirty
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
    useSyncIds: Boolean,
    markDirty: Boolean
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
                ),
                markDirty = markDirty
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
                ),
                markDirty = markDirty
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
    useSyncIds: Boolean,
    itemIdByKey: Map<String, Long> = emptyMap(),
    markDirty: Boolean = false
): MergeOutcome {
    val localReminders = reminderRepository.getAllSnapshot()
    val localByKey = localReminders.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
    var added = 0
    var updated = 0

    snapshots.forEach { snapshot ->
        val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
        val local = localByKey[key]
        // 关联家当：以 syncId 重映射到本地 id（旧备份退回直传的 linkedItemId）。
        val resolvedItemId = resolveReferenceId(
            snapshot.itemSyncId, snapshot.linkedItemId, itemIdByKey, useSyncIds
        )
        // 永久删除墓碑（F21）：对方已彻底删除，本方同样标成永久删除以便继续向第三方传播，
        // 不重新插入、也不当成软删。
        if (snapshot.purgedAt != null) {
            local?.let { reminderRepository.markPurged(listOf(it.id), snapshot.purgedAt) }
            return@forEach
        }
        if (local == null) {
            reminderRepository.insertSynced(
                snapshot.toReminder(targetId = 0, itemId = resolvedItemId).recomputeFireAt(),
                markDirty = markDirty
            )
            added++
        } else {
            if (!isRemoteNewer(snapshot.updatedAt, local.updatedAt)) return@forEach
            reminderRepository.updateSynced(
                snapshot.toReminder(targetId = local.id, itemId = resolvedItemId).copy(
                    syncId = local.syncId ?: snapshot.syncId.orNewSyncId()
                    // nextFireAt 由 recomputeFireAt 本地重算，不采用对方快照里的值。
                ).recomputeFireAt(fallback = local.nextFireAt),
                markDirty = markDirty
            )
            updated++
        }
    }
    return MergeOutcome(added, updated, emptyMap())
}

/**
 * 按本地规则重算下次触发时间。
 *
 * [Reminder.nextFireAt] 是设备本地的调度状态，各设备各自推进；随快照传会让调度时钟互相
 * 覆盖（周期错乱、重复通知），故合并时一律本地重算，仅以 [fallback] 兜底。墓碑不重算。
 */
internal fun Reminder.recomputeFireAt(fallback: Long = 0L): Reminder {
    if (deletedAt != null) return this
    val fireAt = ReminderClock.firstFireAt(this)?.takeIf { it > 0 } ?: fallback
    return copy(nextFireAt = fireAt)
}

/**
 * 时间胶囊合并（v9 起）：与提醒同款——syncId 对齐、LWW、墓碑随快照传播。
 *
 * 解锁提醒不在这里重建：它由 TimeCapsuleRepository 在打开胶囊时联动，
 * 合并只负责把胶囊本体对齐，避免同步两端各建一条重复提醒。
 */
internal suspend fun AppBackupManager.mergeTimeCapsules(
    snapshots: List<TimeCapsuleSnapshot>,
    useSyncIds: Boolean,
    markDirty: Boolean
): MergeOutcome {
    val local = timeCapsuleRepository.getAllSnapshot()
    val localByKey = local.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
    var added = 0
    var updated = 0

    snapshots.forEach { snapshot ->
        val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
        val existing = localByKey[key]
        if (existing == null) {
            timeCapsuleRepository.insertSynced(
                snapshot.toCapsule(targetId = 0).copy(syncId = snapshot.syncId.orNewSyncId()),
                markDirty = markDirty
            )
            added++
        } else {
            if (!isRemoteNewer(snapshot.updatedAt, existing.updatedAt)) return@forEach
            timeCapsuleRepository.updateSynced(
                snapshot.toCapsule(targetId = existing.id).copy(
                    syncId = existing.syncId ?: snapshot.syncId.orNewSyncId()
                ),
                markDirty = markDirty
            )
            updated++
        }
    }
    return MergeOutcome(added, updated, emptyMap())
}

/** 旅行行程合并（v9 起）：产出 idByKey 供条目解析归属。 */
internal suspend fun AppBackupManager.mergePackingTrips(
    snapshots: List<PackingTripSnapshot>,
    useSyncIds: Boolean,
    markDirty: Boolean
): MergeOutcome {
    val local = packingRepository.getAllTripsSnapshot()
    val localByKey = local.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
    val idByKey = HashMap(localByKey.mapValues { it.value.id })
    var added = 0
    var updated = 0

    snapshots.forEach { snapshot ->
        val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
        val existing = localByKey[key]
        if (existing == null) {
            val newId = packingRepository.insertSyncedTrip(
                snapshot.toTrip(targetId = 0).copy(syncId = snapshot.syncId.orNewSyncId()),
                markDirty = markDirty
            )
            idByKey[key] = newId
            added++
        } else {
            idByKey[key] = existing.id
            if (!isRemoteNewer(snapshot.updatedAt, existing.updatedAt)) return@forEach
            packingRepository.updateSyncedTrip(
                snapshot.toTrip(targetId = existing.id).copy(
                    syncId = existing.syncId ?: snapshot.syncId.orNewSyncId()
                ),
                markDirty = markDirty
            )
            updated++
        }
    }
    return MergeOutcome(added, updated, idByKey)
}

/**
 * 打包条目合并（v9 起）。
 *
 * tripId 是本地主键、跨设备对不上，必须按 tripSyncId 重新解析；解析不到就先跳过
 * 这一条——它所属行程可能在本轮更早或更晚才落库，硬塞会挂到错误行程下。
 */
internal suspend fun AppBackupManager.mergePackingItems(
    snapshots: List<PackingItemSnapshot>,
    useSyncIds: Boolean,
    tripIdByKey: Map<String, Long>,
    markDirty: Boolean
): MergeOutcome {
    val local = packingRepository.getAllItemsSnapshot()
    val localByKey = local.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
    var added = 0
    var updated = 0

    snapshots.forEach { snapshot ->
        val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
        val existing = localByKey[key]
        // 条目归属：已有条目沿用其本地 tripId；新条目按行程 syncId 解析。
        // 解析不到就跳过本条——它所属行程可能尚未落库，硬塞会挂到错误行程下。
        val resolvedTripId = existing?.tripId
            ?: snapshot.tripSyncId.orBlank()?.let { tripIdByKey[keyOf(it, 0L, useSyncIds = true)] }
            ?: return@forEach

        if (existing == null) {
            packingRepository.insertSyncedItem(
                snapshot.toPackingItem(targetId = 0, resolvedTripId = resolvedTripId)
                    .copy(syncId = snapshot.syncId.orNewSyncId()),
                markDirty = markDirty
            )
            added++
        } else {
            if (!isRemoteNewer(snapshot.updatedAt, existing.updatedAt)) return@forEach
            packingRepository.updateSyncedItem(
                snapshot.toPackingItem(targetId = existing.id, resolvedTripId = resolvedTripId)
                    .copy(syncId = existing.syncId ?: snapshot.syncId.orNewSyncId()),
                markDirty = markDirty
            )
            updated++
        }
    }
    return MergeOutcome(added, updated, emptyMap())
}

/**
 * 记账模板合并（v10）。
 *
 * 分类与账户引用跨设备对不上本地主键，因此按 syncId 重映射；解析不到就置 null，
 * 让模板退化为「不指定分类/账户」而不是挂到错误的分类上——模板本就是可编辑的草稿，
 * 少一个引用比指错更安全。
 */
internal suspend fun AppBackupManager.mergeLedgerTemplates(
    snapshots: List<LedgerTemplateSnapshot>,
    useSyncIds: Boolean,
    categoryIdByKey: Map<String, Long>,
    assetIdByKey: Map<String, Long>,
    markDirty: Boolean
): MergeOutcome {
    val local = ledgerPlanningRepository.getAllTemplatesSnapshot()
    val localByKey = local.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
    var added = 0
    var updated = 0

    snapshots.forEach { snapshot ->
        val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
        val existing = localByKey[key]
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

        if (existing == null) {
            ledgerPlanningRepository.insertSyncedTemplate(
                snapshot.toTemplate(
                    targetId = 0,
                    categoryId = categoryId,
                    assetId = assetId,
                    targetAssetId = targetAssetId
                ).copy(syncId = snapshot.syncId.orNewSyncId()),
                markDirty = markDirty
            )
            added++
        } else {
            if (!isRemoteNewer(snapshot.updatedAt, existing.updatedAt)) return@forEach
            ledgerPlanningRepository.updateSyncedTemplate(
                snapshot.toTemplate(
                    targetId = existing.id,
                    categoryId = categoryId,
                    assetId = assetId,
                    targetAssetId = targetAssetId
                ).copy(syncId = existing.syncId ?: snapshot.syncId.orNewSyncId()),
                markDirty = markDirty
            )
            updated++
        }
    }
    return MergeOutcome(added, updated, emptyMap())
}

/**
 * 周期记账规则合并（v10）。
 *
 * 与模板同样按 syncId 重映射分类/账户。`nextFireAt`/`lastFiredAt` 随 LWW 一起取较新的一方：
 * 它们描述的是「这条规则生成到哪一期」，属于规则自身状态而非设备本地状态。
 */
internal suspend fun AppBackupManager.mergeLedgerRecurringRules(
    snapshots: List<LedgerRecurringRuleSnapshot>,
    useSyncIds: Boolean,
    categoryIdByKey: Map<String, Long>,
    assetIdByKey: Map<String, Long>,
    markDirty: Boolean
): MergeOutcome {
    val local = ledgerPlanningRepository.getAllRulesSnapshot()
    val localByKey = local.associateBy { keyOf(it.syncId, it.id, useSyncIds) }
    var added = 0
    var updated = 0

    snapshots.forEach { snapshot ->
        val key = keyOf(snapshot.syncId, snapshot.id, useSyncIds)
        val existing = localByKey[key]
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
        val rule = snapshot.toRule(
            targetId = existing?.id ?: 0,
            categoryId = categoryId,
            assetId = assetId,
            targetAssetId = targetAssetId
        ).let { resolved ->
            // repeatUnit 归一：远端可能来自更新的版本，未知单位按 MONTH 兜底而不是让
            // valueOf 抛错；间隔与 nextFireAt 做下界保护，避免 0 值把规则变成「每次都到点」。
            resolved.copy(
                repeatUnit = runCatching {
                    LedgerRecurringRule.RepeatUnit.valueOf(snapshot.repeatUnit).name
                }.getOrDefault(LedgerRecurringRule.RepeatUnit.MONTH.name),
                repeatInterval = snapshot.repeatInterval.coerceAtLeast(1),
                syncId = existing?.syncId ?: snapshot.syncId.orNewSyncId()
            )
        }

        if (existing == null) {
            ledgerPlanningRepository.insertSyncedRule(rule, markDirty = markDirty)
            added++
        } else {
            if (!isRemoteNewer(snapshot.updatedAt, existing.updatedAt)) return@forEach
            ledgerPlanningRepository.updateSyncedRule(rule, markDirty = markDirty)
            updated++
        }
    }
    return MergeOutcome(added, updated, emptyMap())
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
