package com.goodyaoshi.lemonbox.data.backup

import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset
import com.goodyaoshi.lemonbox.data.local.entity.LedgerBudget
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
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
            )
        )
        if (!isNew) updated++
    }

    return MergeOutcome(added, updated, idByKey)
}

internal suspend fun AppBackupManager.mergeItems(
    snapshots: List<ItemSnapshot>,
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
        // 永久删除墓碑（F21）：对方已彻底删除，本方同样标成永久删除以便继续向第三方传播，
        // 不重新插入、也不当成软删。
        if (snapshot.purgedAt != null) {
            local?.let { reminderRepository.markPurged(listOf(it.id), snapshot.purgedAt) }
            return@forEach
        }
        if (local == null) {
            reminderRepository.insertSynced(snapshot.toReminder(targetId = 0).recomputeFireAt())
            added++
        } else {
            if (!isRemoteNewer(snapshot.updatedAt, local.updatedAt)) return@forEach
            reminderRepository.updateSynced(
                snapshot.toReminder(targetId = local.id).copy(
                    syncId = local.syncId ?: snapshot.syncId.orNewSyncId()
                    // nextFireAt 由 recomputeFireAt 本地重算，不采用对方快照里的值。
                ).recomputeFireAt(fallback = local.nextFireAt)
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
    useSyncIds: Boolean
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
                snapshot.toCapsule(targetId = 0).copy(syncId = snapshot.syncId.orNewSyncId())
            )
            added++
        } else {
            if (!isRemoteNewer(snapshot.updatedAt, existing.updatedAt)) return@forEach
            timeCapsuleRepository.updateSynced(
                snapshot.toCapsule(targetId = existing.id).copy(
                    syncId = existing.syncId ?: snapshot.syncId.orNewSyncId()
                )
            )
            updated++
        }
    }
    return MergeOutcome(added, updated, emptyMap())
}

/** 旅行行程合并（v9 起）：产出 idByKey 供条目解析归属。 */
internal suspend fun AppBackupManager.mergePackingTrips(
    snapshots: List<PackingTripSnapshot>,
    useSyncIds: Boolean
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
                snapshot.toTrip(targetId = 0).copy(syncId = snapshot.syncId.orNewSyncId())
            )
            idByKey[key] = newId
            added++
        } else {
            idByKey[key] = existing.id
            if (!isRemoteNewer(snapshot.updatedAt, existing.updatedAt)) return@forEach
            packingRepository.updateSyncedTrip(
                snapshot.toTrip(targetId = existing.id).copy(
                    syncId = existing.syncId ?: snapshot.syncId.orNewSyncId()
                )
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
    tripIdByKey: Map<String, Long>
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
                    .copy(syncId = snapshot.syncId.orNewSyncId())
            )
            added++
        } else {
            if (!isRemoteNewer(snapshot.updatedAt, existing.updatedAt)) return@forEach
            packingRepository.updateSyncedItem(
                snapshot.toPackingItem(targetId = existing.id, resolvedTripId = resolvedTripId)
                    .copy(syncId = existing.syncId ?: snapshot.syncId.orNewSyncId())
            )
            updated++
        }
    }
    return MergeOutcome(added, updated, emptyMap())
}

/**
 * 导入时的本地图片内容索引（F20）。
 *
 * 目前仅用于「导入旧版备份」这一条兼容路径（旧备份会打包图片文件，需按内容去重还原）；
 * 新版备份只传哈希、不打包图片，因此不经过这里。
 *
 * 解决什么问题：备份导入把图片逐张复制进本地，若本地已有内容相同的图，再复制一份
 * 纯属浪费——导入自己刚导出的备份会让本地图片直接翻倍。
 *
 * 为什么按大小预筛：内容不同而大小恰好相同的概率很低，先按文件大小分组，
 * 只在「大小相同」的候选里真正读盘算 SHA-256，避免为每张图遍历整个图片目录；
 * 算过的哈希会缓存，同一文件不会被反复读。
 *
 * 只是导入过程内的临时结构，用完即弃，不做持久化——判断依据始终是文件内容本身。
 */
internal class LocalImageContentIndex(imagesDir: File) {

    private val filesBySize = HashMap<Long, MutableList<File>>()
    private val hashByPath = HashMap<String, String>()
    private val pathByHash = HashMap<String, String>()

    init {
        imagesDir.listFiles()?.forEach { file ->
            if (file.isFile) filesBySize.getOrPut(file.length()) { mutableListOf() } += file
        }
    }

    /** 找本地已有的同内容文件；没有则返回 null。[size] 由调用方给出，免得再 stat 一次。 */
    fun findByHash(hash: String, size: Long): String? {
        // 先看已登记的（含本次导入刚落盘的），再在「大小相同」的候选里懒算哈希——
        // 后者覆盖历史遗留的 UUID 命名文件，它们同样按内容参与去重。
        pathByHash[hash]?.let { path -> if (File(path).isFile) return path }
        filesBySize[size]?.forEach { candidate ->
            val candidateHash = hashByPath.getOrPut(candidate.absolutePath) {
                ImageUtil.contentHash(candidate).orEmpty()
            }
            if (candidateHash == hash) {
                pathByHash[hash] = candidate.absolutePath
                return candidate.absolutePath
            }
        }
        return null
    }

    /** 登记刚落盘的文件，供本次导入中后续图片命中。 */
    fun register(hash: String, path: String) {
        pathByHash[hash] = path
        hashByPath[path] = hash
        val bucket = filesBySize.getOrPut(File(path).length()) { mutableListOf() }
        if (bucket.none { it.absolutePath == path }) bucket += File(path)
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
