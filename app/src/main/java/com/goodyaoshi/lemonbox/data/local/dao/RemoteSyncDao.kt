package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.goodyaoshi.lemonbox.data.backup.SyncCollections
import com.goodyaoshi.lemonbox.data.local.entity.SyncStateRow

/**
 * 一行待推记录：本机 `dirty = 1` 且有 syncId 的行，连同本机已知的服务端序号。
 *
 * [serverSeq] 就是推送时的 `baseSeq`（本机上次见到的服务端版本）。P5 曾从「本轮拉取的
 * 记录」里取它，拉不到的（本机自己写过、服务端按设备排除掉的）只能回落 0，于是被服务端
 * 一律判成冲突；改读本地这一列之后，本机跟得上服务端就不会误判。
 *
 * [syncId] 声明可空是 Room 的苛求：列本身可空，查询里写 `IS NOT NULL` 并不能让生成的代码
 * 认为它非空。派发处统一 `mapNotNull` 掉，免得使用侧到处写 `!!`。
 */
data class PendingSyncRow(val syncId: String?, val serverSeq: Long)

/**
 * 一张同步表的对账计数（P9 诊断页）。
 *
 *  - [live]：非墓碑行数（对应服务端 `records.deleted = 0` 的口径）；
 *  - [tombstones]：软删墓碑行数（`items` / `reminders` 的 `purgedAt` 也算墓碑）；
 *  - [missingSyncId]：没有 syncId 的活行——增量同步推不出去也拉不下来，只增不减的账，
 *    诊断页要把这个数亮出来而不是静默吞掉；
 *  - [dirty]：本机改过、还没被服务端确认的行数。
 */
data class SyncTableAudit(
    val live: Int,
    val tombstones: Int,
    val missingSyncId: Int,
    val dirty: Int
)

/**
 * 增量同步在本地的记账口（P6）。
 *
 * 两件事：
 *  1. **待推行**：`dirty = 1` 的行才是本机改过、还没被服务端确认的，推送时按集合取出它们
 *     （连同本地 `serverSeq` 作 `baseSeq`），只推这一小批，不再每次把整库当快照推上去。
 *  2. **落地记账**：推成功 / 拉回来 / 判为冲突后，把这行的 `serverSeq` 写成服务端给的序号、
 *     并把 `dirty` 清 0，表示「本机这一版已经在服务端了」。
 *
 * 为什么是「一次读出全部待推行」而不是 `IN (:keys)` 变体：同一份脏记录集合既要用于
 * **推送筛选**，也要用于**拉取时跳过本机脏记录**（避免服务端旧值把本机未上传的改动盖掉），
 * 因此按集合整取一次即可，省下每个集合两个查询变体。
 *
 * 键一律是 `syncId`（跨设备稳定键），**绝不用本机自增 id**——两台设备的自增 id 各指各行。
 */
@Dao
interface RemoteSyncDao {

    // ---------- 同步状态位（水位 / server_ack / preferences 序号） ----------

    /** 读状态位；从未写过返回 null（调用方按各自的默认值兜底）。 */
    @Query("SELECT `value` FROM sync_state WHERE `key` = :key")
    suspend fun state(key: String): Long?

    /** 写状态位（存在即覆盖）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(row: SyncStateRow)

    // ---------- 待推行：dirty = 1 且有 syncId 的行 ----------

    @Query("SELECT syncId, serverSeq FROM categories WHERE (dirty = 1 OR serverSeq = 0) AND syncId IS NOT NULL")
    suspend fun pendingCategories(): List<PendingSyncRow>

    @Query("SELECT syncId, serverSeq FROM locations WHERE (dirty = 1 OR serverSeq = 0) AND syncId IS NOT NULL")
    suspend fun pendingLocations(): List<PendingSyncRow>

    @Query("SELECT syncId, serverSeq FROM items WHERE (dirty = 1 OR serverSeq = 0) AND syncId IS NOT NULL")
    suspend fun pendingItems(): List<PendingSyncRow>

    @Query("SELECT syncId, serverSeq FROM ledger_assets WHERE (dirty = 1 OR serverSeq = 0) AND syncId IS NOT NULL")
    suspend fun pendingLedgerAssets(): List<PendingSyncRow>

    @Query("SELECT syncId, serverSeq FROM ledger_categories WHERE (dirty = 1 OR serverSeq = 0) AND syncId IS NOT NULL")
    suspend fun pendingLedgerCategories(): List<PendingSyncRow>

    @Query("SELECT syncId, serverSeq FROM ledger_records WHERE (dirty = 1 OR serverSeq = 0) AND syncId IS NOT NULL")
    suspend fun pendingLedgerRecords(): List<PendingSyncRow>

    @Query("SELECT syncId, serverSeq FROM ledger_budgets WHERE (dirty = 1 OR serverSeq = 0) AND syncId IS NOT NULL")
    suspend fun pendingLedgerBudgets(): List<PendingSyncRow>

    @Query("SELECT syncId, serverSeq FROM anniversaries WHERE (dirty = 1 OR serverSeq = 0) AND syncId IS NOT NULL")
    suspend fun pendingAnniversaries(): List<PendingSyncRow>

    @Query("SELECT syncId, serverSeq FROM reminders WHERE (dirty = 1 OR serverSeq = 0) AND syncId IS NOT NULL")
    suspend fun pendingReminders(): List<PendingSyncRow>

    @Query("SELECT syncId, serverSeq FROM time_capsules WHERE (dirty = 1 OR serverSeq = 0) AND syncId IS NOT NULL")
    suspend fun pendingTimeCapsules(): List<PendingSyncRow>

    @Query("SELECT syncId, serverSeq FROM packing_trips WHERE (dirty = 1 OR serverSeq = 0) AND syncId IS NOT NULL")
    suspend fun pendingPackingTrips(): List<PendingSyncRow>

    @Query("SELECT syncId, serverSeq FROM packing_items WHERE (dirty = 1 OR serverSeq = 0) AND syncId IS NOT NULL")
    suspend fun pendingPackingItems(): List<PendingSyncRow>

    @Query("SELECT syncId, serverSeq FROM ledger_templates WHERE (dirty = 1 OR serverSeq = 0) AND syncId IS NOT NULL")
    suspend fun pendingLedgerTemplates(): List<PendingSyncRow>

    @Query("SELECT syncId, serverSeq FROM ledger_recurring_rules WHERE (dirty = 1 OR serverSeq = 0) AND syncId IS NOT NULL")
    suspend fun pendingLedgerRecurringRules(): List<PendingSyncRow>

    // ---------- 逐表对账计数（P9 诊断页） ----------
    // 墓碑口径与 toSyncRecords 一致：普通表看 deletedAt，items / reminders 连 purgedAt 一起算。

    @Query(
        "SELECT " +
            "SUM(CASE WHEN deletedAt IS NULL THEN 1 ELSE 0 END) AS live, " +
            "SUM(CASE WHEN deletedAt IS NOT NULL THEN 1 ELSE 0 END) AS tombstones, " +
            "SUM(CASE WHEN syncId IS NULL AND deletedAt IS NULL THEN 1 ELSE 0 END) AS missingSyncId, " +
            "SUM(CASE WHEN dirty = 1 THEN 1 ELSE 0 END) AS dirty " +
            "FROM categories"
    )
    suspend fun auditCategories(): SyncTableAudit

    @Query(
        "SELECT " +
            "SUM(CASE WHEN deletedAt IS NULL THEN 1 ELSE 0 END) AS live, " +
            "SUM(CASE WHEN deletedAt IS NOT NULL THEN 1 ELSE 0 END) AS tombstones, " +
            "SUM(CASE WHEN syncId IS NULL AND deletedAt IS NULL THEN 1 ELSE 0 END) AS missingSyncId, " +
            "SUM(CASE WHEN dirty = 1 THEN 1 ELSE 0 END) AS dirty " +
            "FROM locations"
    )
    suspend fun auditLocations(): SyncTableAudit

    @Query(
        "SELECT " +
            "SUM(CASE WHEN deletedAt IS NULL AND purgedAt IS NULL THEN 1 ELSE 0 END) AS live, " +
            "SUM(CASE WHEN deletedAt IS NOT NULL OR purgedAt IS NOT NULL THEN 1 ELSE 0 END) AS tombstones, " +
            "SUM(CASE WHEN syncId IS NULL AND deletedAt IS NULL AND purgedAt IS NULL THEN 1 ELSE 0 END) AS missingSyncId, " +
            "SUM(CASE WHEN dirty = 1 THEN 1 ELSE 0 END) AS dirty " +
            "FROM items"
    )
    suspend fun auditItems(): SyncTableAudit

    @Query(
        "SELECT " +
            "SUM(CASE WHEN deletedAt IS NULL THEN 1 ELSE 0 END) AS live, " +
            "SUM(CASE WHEN deletedAt IS NOT NULL THEN 1 ELSE 0 END) AS tombstones, " +
            "SUM(CASE WHEN syncId IS NULL AND deletedAt IS NULL THEN 1 ELSE 0 END) AS missingSyncId, " +
            "SUM(CASE WHEN dirty = 1 THEN 1 ELSE 0 END) AS dirty " +
            "FROM ledger_assets"
    )
    suspend fun auditLedgerAssets(): SyncTableAudit

    @Query(
        "SELECT " +
            "SUM(CASE WHEN deletedAt IS NULL THEN 1 ELSE 0 END) AS live, " +
            "SUM(CASE WHEN deletedAt IS NOT NULL THEN 1 ELSE 0 END) AS tombstones, " +
            "SUM(CASE WHEN syncId IS NULL AND deletedAt IS NULL THEN 1 ELSE 0 END) AS missingSyncId, " +
            "SUM(CASE WHEN dirty = 1 THEN 1 ELSE 0 END) AS dirty " +
            "FROM ledger_categories"
    )
    suspend fun auditLedgerCategories(): SyncTableAudit

    @Query(
        "SELECT " +
            "SUM(CASE WHEN deletedAt IS NULL THEN 1 ELSE 0 END) AS live, " +
            "SUM(CASE WHEN deletedAt IS NOT NULL THEN 1 ELSE 0 END) AS tombstones, " +
            "SUM(CASE WHEN syncId IS NULL AND deletedAt IS NULL THEN 1 ELSE 0 END) AS missingSyncId, " +
            "SUM(CASE WHEN dirty = 1 THEN 1 ELSE 0 END) AS dirty " +
            "FROM ledger_records"
    )
    suspend fun auditLedgerRecords(): SyncTableAudit

    @Query(
        "SELECT " +
            "SUM(CASE WHEN deletedAt IS NULL THEN 1 ELSE 0 END) AS live, " +
            "SUM(CASE WHEN deletedAt IS NOT NULL THEN 1 ELSE 0 END) AS tombstones, " +
            "SUM(CASE WHEN syncId IS NULL AND deletedAt IS NULL THEN 1 ELSE 0 END) AS missingSyncId, " +
            "SUM(CASE WHEN dirty = 1 THEN 1 ELSE 0 END) AS dirty " +
            "FROM ledger_budgets"
    )
    suspend fun auditLedgerBudgets(): SyncTableAudit

    @Query(
        "SELECT " +
            "SUM(CASE WHEN deletedAt IS NULL THEN 1 ELSE 0 END) AS live, " +
            "SUM(CASE WHEN deletedAt IS NOT NULL THEN 1 ELSE 0 END) AS tombstones, " +
            "SUM(CASE WHEN syncId IS NULL AND deletedAt IS NULL THEN 1 ELSE 0 END) AS missingSyncId, " +
            "SUM(CASE WHEN dirty = 1 THEN 1 ELSE 0 END) AS dirty " +
            "FROM anniversaries"
    )
    suspend fun auditAnniversaries(): SyncTableAudit

    @Query(
        "SELECT " +
            "SUM(CASE WHEN deletedAt IS NULL AND purgedAt IS NULL THEN 1 ELSE 0 END) AS live, " +
            "SUM(CASE WHEN deletedAt IS NOT NULL OR purgedAt IS NOT NULL THEN 1 ELSE 0 END) AS tombstones, " +
            "SUM(CASE WHEN syncId IS NULL AND deletedAt IS NULL AND purgedAt IS NULL THEN 1 ELSE 0 END) AS missingSyncId, " +
            "SUM(CASE WHEN dirty = 1 THEN 1 ELSE 0 END) AS dirty " +
            "FROM reminders"
    )
    suspend fun auditReminders(): SyncTableAudit

    @Query(
        "SELECT " +
            "SUM(CASE WHEN deletedAt IS NULL THEN 1 ELSE 0 END) AS live, " +
            "SUM(CASE WHEN deletedAt IS NOT NULL THEN 1 ELSE 0 END) AS tombstones, " +
            "SUM(CASE WHEN syncId IS NULL AND deletedAt IS NULL THEN 1 ELSE 0 END) AS missingSyncId, " +
            "SUM(CASE WHEN dirty = 1 THEN 1 ELSE 0 END) AS dirty " +
            "FROM time_capsules"
    )
    suspend fun auditTimeCapsules(): SyncTableAudit

    @Query(
        "SELECT " +
            "SUM(CASE WHEN deletedAt IS NULL THEN 1 ELSE 0 END) AS live, " +
            "SUM(CASE WHEN deletedAt IS NOT NULL THEN 1 ELSE 0 END) AS tombstones, " +
            "SUM(CASE WHEN syncId IS NULL AND deletedAt IS NULL THEN 1 ELSE 0 END) AS missingSyncId, " +
            "SUM(CASE WHEN dirty = 1 THEN 1 ELSE 0 END) AS dirty " +
            "FROM packing_trips"
    )
    suspend fun auditPackingTrips(): SyncTableAudit

    @Query(
        "SELECT " +
            "SUM(CASE WHEN deletedAt IS NULL THEN 1 ELSE 0 END) AS live, " +
            "SUM(CASE WHEN deletedAt IS NOT NULL THEN 1 ELSE 0 END) AS tombstones, " +
            "SUM(CASE WHEN syncId IS NULL AND deletedAt IS NULL THEN 1 ELSE 0 END) AS missingSyncId, " +
            "SUM(CASE WHEN dirty = 1 THEN 1 ELSE 0 END) AS dirty " +
            "FROM packing_items"
    )
    suspend fun auditPackingItems(): SyncTableAudit

    @Query(
        "SELECT " +
            "SUM(CASE WHEN deletedAt IS NULL THEN 1 ELSE 0 END) AS live, " +
            "SUM(CASE WHEN deletedAt IS NOT NULL THEN 1 ELSE 0 END) AS tombstones, " +
            "SUM(CASE WHEN syncId IS NULL AND deletedAt IS NULL THEN 1 ELSE 0 END) AS missingSyncId, " +
            "SUM(CASE WHEN dirty = 1 THEN 1 ELSE 0 END) AS dirty " +
            "FROM ledger_templates"
    )
    suspend fun auditLedgerTemplates(): SyncTableAudit

    @Query(
        "SELECT " +
            "SUM(CASE WHEN deletedAt IS NULL THEN 1 ELSE 0 END) AS live, " +
            "SUM(CASE WHEN deletedAt IS NOT NULL THEN 1 ELSE 0 END) AS tombstones, " +
            "SUM(CASE WHEN syncId IS NULL AND deletedAt IS NULL THEN 1 ELSE 0 END) AS missingSyncId, " +
            "SUM(CASE WHEN dirty = 1 THEN 1 ELSE 0 END) AS dirty " +
            "FROM ledger_recurring_rules"
    )
    suspend fun auditLedgerRecurringRules(): SyncTableAudit

    // ---------- 落地记账：写服务端序号并清脏 ----------

    @Query("UPDATE categories SET serverSeq = :seq, dirty = :dirty WHERE syncId = :syncId")
    suspend fun markCategorySynced(syncId: String, seq: Long, dirty: Boolean = false)

    @Query("UPDATE locations SET serverSeq = :seq, dirty = :dirty WHERE syncId = :syncId")
    suspend fun markLocationSynced(syncId: String, seq: Long, dirty: Boolean = false)

    @Query("UPDATE items SET serverSeq = :seq, dirty = :dirty WHERE syncId = :syncId")
    suspend fun markItemSynced(syncId: String, seq: Long, dirty: Boolean = false)

    @Query("UPDATE ledger_assets SET serverSeq = :seq, dirty = :dirty WHERE syncId = :syncId")
    suspend fun markLedgerAssetSynced(syncId: String, seq: Long, dirty: Boolean = false)

    @Query("UPDATE ledger_categories SET serverSeq = :seq, dirty = :dirty WHERE syncId = :syncId")
    suspend fun markLedgerCategorySynced(syncId: String, seq: Long, dirty: Boolean = false)

    @Query("UPDATE ledger_records SET serverSeq = :seq, dirty = :dirty WHERE syncId = :syncId")
    suspend fun markLedgerRecordSynced(syncId: String, seq: Long, dirty: Boolean = false)

    @Query("UPDATE ledger_budgets SET serverSeq = :seq, dirty = :dirty WHERE syncId = :syncId")
    suspend fun markLedgerBudgetSynced(syncId: String, seq: Long, dirty: Boolean = false)

    @Query("UPDATE anniversaries SET serverSeq = :seq, dirty = :dirty WHERE syncId = :syncId")
    suspend fun markAnniversarySynced(syncId: String, seq: Long, dirty: Boolean = false)

    @Query("UPDATE reminders SET serverSeq = :seq, dirty = :dirty WHERE syncId = :syncId")
    suspend fun markReminderSynced(syncId: String, seq: Long, dirty: Boolean = false)

    @Query("UPDATE time_capsules SET serverSeq = :seq, dirty = :dirty WHERE syncId = :syncId")
    suspend fun markTimeCapsuleSynced(syncId: String, seq: Long, dirty: Boolean = false)

    @Query("UPDATE packing_trips SET serverSeq = :seq, dirty = :dirty WHERE syncId = :syncId")
    suspend fun markPackingTripSynced(syncId: String, seq: Long, dirty: Boolean = false)

    @Query("UPDATE packing_items SET serverSeq = :seq, dirty = :dirty WHERE syncId = :syncId")
    suspend fun markPackingItemSynced(syncId: String, seq: Long, dirty: Boolean = false)

    @Query("UPDATE ledger_templates SET serverSeq = :seq, dirty = :dirty WHERE syncId = :syncId")
    suspend fun markLedgerTemplateSynced(syncId: String, seq: Long, dirty: Boolean = false)

    @Query("UPDATE ledger_recurring_rules SET serverSeq = :seq, dirty = :dirty WHERE syncId = :syncId")
    suspend fun markLedgerRecurringRuleSynced(syncId: String, seq: Long, dirty: Boolean = false)

    // ---------- 本地时间戳：判断「本机这版是否比服务端新」 ----------
    // 拉取落地时，合并策略会保留较新的本机版本（`isRemoteNewer` 为 false 直接跳过）。
    // 这类记录如果被无条件清脏，就会变成「本机内容较新、服务端较旧，但永远不再上传」，
    // 对账永远差一截。这里按 syncId 取本机时间戳，与远端载荷比较后决定是否保留脏位。
    // 取法与合并策略一致：updatedAt 缺失时回退 createdAt（没有该列的表回退 0）。

    @Query("SELECT COALESCE(updatedAt, 0) FROM categories WHERE syncId = :syncId LIMIT 1")
    suspend fun localCategoryStamp(syncId: String): Long?

    @Query("SELECT COALESCE(updatedAt, 0) FROM locations WHERE syncId = :syncId LIMIT 1")
    suspend fun localLocationStamp(syncId: String): Long?

    @Query("SELECT COALESCE(updatedAt, createdAt, 0) FROM items WHERE syncId = :syncId LIMIT 1")
    suspend fun localItemStamp(syncId: String): Long?

    @Query("SELECT COALESCE(updatedAt, 0) FROM ledger_assets WHERE syncId = :syncId LIMIT 1")
    suspend fun localLedgerAssetStamp(syncId: String): Long?

    @Query("SELECT COALESCE(updatedAt, 0) FROM ledger_categories WHERE syncId = :syncId LIMIT 1")
    suspend fun localLedgerCategoryStamp(syncId: String): Long?

    @Query("SELECT COALESCE(updatedAt, createdAt, 0) FROM ledger_records WHERE syncId = :syncId LIMIT 1")
    suspend fun localLedgerRecordStamp(syncId: String): Long?

    @Query("SELECT COALESCE(updatedAt, 0) FROM ledger_budgets WHERE syncId = :syncId LIMIT 1")
    suspend fun localLedgerBudgetStamp(syncId: String): Long?

    @Query("SELECT COALESCE(updatedAt, createdAt, 0) FROM anniversaries WHERE syncId = :syncId LIMIT 1")
    suspend fun localAnniversaryStamp(syncId: String): Long?

    @Query("SELECT COALESCE(updatedAt, createdAt, 0) FROM reminders WHERE syncId = :syncId LIMIT 1")
    suspend fun localReminderStamp(syncId: String): Long?

    @Query("SELECT COALESCE(updatedAt, createdAt, 0) FROM time_capsules WHERE syncId = :syncId LIMIT 1")
    suspend fun localTimeCapsuleStamp(syncId: String): Long?

    @Query("SELECT COALESCE(updatedAt, createdAt, 0) FROM packing_trips WHERE syncId = :syncId LIMIT 1")
    suspend fun localPackingTripStamp(syncId: String): Long?

    @Query("SELECT COALESCE(updatedAt, createdAt, 0) FROM packing_items WHERE syncId = :syncId LIMIT 1")
    suspend fun localPackingItemStamp(syncId: String): Long?

    @Query("SELECT COALESCE(updatedAt, createdAt, 0) FROM ledger_templates WHERE syncId = :syncId LIMIT 1")
    suspend fun localLedgerTemplateStamp(syncId: String): Long?

    @Query("SELECT COALESCE(updatedAt, createdAt, 0) FROM ledger_recurring_rules WHERE syncId = :syncId LIMIT 1")
    suspend fun localLedgerRecurringRuleStamp(syncId: String): Long?
}

/**
 * 按集合名取「本机改过、还没被服务端确认」的 `syncId → 本地 serverSeq`。
 *
 * 覆盖 `SyncTables` 的全部 14 张同步表；`preferences`（整块设置、无 dirty/serverSeq 列）
 * 与未知集合一律返回空——前者恒推，其服务端序号单独记在 `sync_state` 里。
 */
internal suspend fun RemoteSyncDao.pendingSeqs(collection: String): Map<String, Long> = when (collection) {
    SyncCollections.CATEGORIES -> pendingCategories()
    SyncCollections.LOCATIONS -> pendingLocations()
    SyncCollections.ITEMS -> pendingItems()
    SyncCollections.LEDGER_ASSETS -> pendingLedgerAssets()
    SyncCollections.LEDGER_CATEGORIES -> pendingLedgerCategories()
    SyncCollections.LEDGER_RECORDS -> pendingLedgerRecords()
    SyncCollections.LEDGER_BUDGETS -> pendingLedgerBudgets()
    SyncCollections.ANNIVERSARIES -> pendingAnniversaries()
    SyncCollections.REMINDERS -> pendingReminders()
    SyncCollections.TIME_CAPSULES -> pendingTimeCapsules()
    SyncCollections.PACKING_TRIPS -> pendingPackingTrips()
    SyncCollections.PACKING_ITEMS -> pendingPackingItems()
    SyncCollections.LEDGER_TEMPLATES -> pendingLedgerTemplates()
    SyncCollections.LEDGER_RECURRING_RULES -> pendingLedgerRecurringRules()
    else -> emptyList()
}.mapNotNull { row -> row.syncId?.let { it to row.serverSeq } }.toMap()

/**
 * 按集合名把一条记录标为「已在服务端」：写回服务端序号，并按 [dirty] 决定是否保留脏位。
 *
 * 默认清脏；拉取时发现「本机这版比服务端新」的条目要传 `dirty = true`：
 * 内容仍是本机那份，合并策略跳过了服务端版本，必须留到推送阶段送上去。
 * 未知集合（含 `preferences`）静默忽略——它没有 dirty/serverSeq 列可写。
 */
internal suspend fun RemoteSyncDao.markSynced(
    collection: String,
    syncId: String,
    seq: Long,
    dirty: Boolean = false
) {
    when (collection) {
        SyncCollections.CATEGORIES -> markCategorySynced(syncId, seq, dirty)
        SyncCollections.LOCATIONS -> markLocationSynced(syncId, seq, dirty)
        SyncCollections.ITEMS -> markItemSynced(syncId, seq, dirty)
        SyncCollections.LEDGER_ASSETS -> markLedgerAssetSynced(syncId, seq, dirty)
        SyncCollections.LEDGER_CATEGORIES -> markLedgerCategorySynced(syncId, seq, dirty)
        SyncCollections.LEDGER_RECORDS -> markLedgerRecordSynced(syncId, seq, dirty)
        SyncCollections.LEDGER_BUDGETS -> markLedgerBudgetSynced(syncId, seq, dirty)
        SyncCollections.ANNIVERSARIES -> markAnniversarySynced(syncId, seq, dirty)
        SyncCollections.REMINDERS -> markReminderSynced(syncId, seq, dirty)
        SyncCollections.TIME_CAPSULES -> markTimeCapsuleSynced(syncId, seq, dirty)
        SyncCollections.PACKING_TRIPS -> markPackingTripSynced(syncId, seq, dirty)
        SyncCollections.PACKING_ITEMS -> markPackingItemSynced(syncId, seq, dirty)
        SyncCollections.LEDGER_TEMPLATES -> markLedgerTemplateSynced(syncId, seq, dirty)
        SyncCollections.LEDGER_RECURRING_RULES -> markLedgerRecurringRuleSynced(syncId, seq, dirty)
        else -> Unit
    }
}

/**
 * 按集合名取本机某条记录的时间戳；本机没有该条返回 null。
 *
 * 只用于拉取落地后的「本地是否较新」判断，故与合并策略同口径：
 * `updatedAt` 优先，缺失时回退 `createdAt`（没有该列的表回退 0）。
 */
internal suspend fun RemoteSyncDao.localStamp(collection: String, syncId: String): Long? =
    when (collection) {
        SyncCollections.CATEGORIES -> localCategoryStamp(syncId)
        SyncCollections.LOCATIONS -> localLocationStamp(syncId)
        SyncCollections.ITEMS -> localItemStamp(syncId)
        SyncCollections.LEDGER_ASSETS -> localLedgerAssetStamp(syncId)
        SyncCollections.LEDGER_CATEGORIES -> localLedgerCategoryStamp(syncId)
        SyncCollections.LEDGER_RECORDS -> localLedgerRecordStamp(syncId)
        SyncCollections.LEDGER_BUDGETS -> localLedgerBudgetStamp(syncId)
        SyncCollections.ANNIVERSARIES -> localAnniversaryStamp(syncId)
        SyncCollections.REMINDERS -> localReminderStamp(syncId)
        SyncCollections.TIME_CAPSULES -> localTimeCapsuleStamp(syncId)
        SyncCollections.PACKING_TRIPS -> localPackingTripStamp(syncId)
        SyncCollections.PACKING_ITEMS -> localPackingItemStamp(syncId)
        SyncCollections.LEDGER_TEMPLATES -> localLedgerTemplateStamp(syncId)
        SyncCollections.LEDGER_RECURRING_RULES -> localLedgerRecurringRuleStamp(syncId)
        else -> null
    }

/**
 * 按集合名取本机这张同步表的对账计数（P9 诊断页）。
 * `preferences`（整块设置、无对应表）与未知集合返回 null，调用方按「无本地表」展示。
 */
internal suspend fun RemoteSyncDao.tableAudit(collection: String): SyncTableAudit? =
    when (collection) {
        SyncCollections.CATEGORIES -> auditCategories()
        SyncCollections.LOCATIONS -> auditLocations()
        SyncCollections.ITEMS -> auditItems()
        SyncCollections.LEDGER_ASSETS -> auditLedgerAssets()
        SyncCollections.LEDGER_CATEGORIES -> auditLedgerCategories()
        SyncCollections.LEDGER_RECORDS -> auditLedgerRecords()
        SyncCollections.LEDGER_BUDGETS -> auditLedgerBudgets()
        SyncCollections.ANNIVERSARIES -> auditAnniversaries()
        SyncCollections.REMINDERS -> auditReminders()
        SyncCollections.TIME_CAPSULES -> auditTimeCapsules()
        SyncCollections.PACKING_TRIPS -> auditPackingTrips()
        SyncCollections.PACKING_ITEMS -> auditPackingItems()
        SyncCollections.LEDGER_TEMPLATES -> auditLedgerTemplates()
        SyncCollections.LEDGER_RECURRING_RULES -> auditLedgerRecurringRules()
        else -> null
    }

/**
 * 墓碑物理清除的「服务端已确认」前提（P6-d）。
 *
 * 一条删除墓碑要先传到服务端、再被其他设备拉到，才能物理清除；本地自己清掉就等于
 * 「这台设备从没删过它」，对端那条会一直留着。故清除的前提是**这一条已被服务端确认**：
 * `dirty = 0 AND serverSeq > 0`（推成功或拉回来过）。
 *
 * 末尾的 `NOT EXISTS (server_ack)` 兜底：从没成功同步过的安装（纯本地、还没登录多设备）
 * 没有对端要通知，维持旧的「满 30 天就清」行为，否则墓碑在纯本地机器上无限堆积。
 *
 * 各 purge 语句一律拼在 WHERE 末尾。取值查询与删除**必须用同一段**——`items` 的清理
 * 是先选出待清图片再删行（见 ItemRepository.purgeDeletedItemsOlderThan），两处谓词
 * 不一致会先选出图片、却没删掉墓碑，白跑一趟云端删图。
 */
const val TOMBSTONE_CONFIRMED_SQL =
    " AND ((dirty = 0 AND serverSeq > 0) OR NOT EXISTS (SELECT 1 FROM sync_state WHERE `key` = 'server_ack'))"

