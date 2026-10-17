package com.goodyaoshi.lemonbox.data.backup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 同步记录集合名（服务端 `records.collection`）。
 *
 * 一个集合对应一张同步表；集合名是**跨版本契约**，改名等同于换协议，
 * 会让服务端上已有的记录对不上本机而重新推一遍，也会让已升到新版本的设备
 * 读不懂旧记录，因此定下来就不要动。名字与 [AppBackupPayload] 的字段一一对应。
 */
object SyncCollections {
    const val CATEGORIES = "categories"
    const val LOCATIONS = "locations"
    const val ITEMS = "items"
    const val LEDGER_ASSETS = "ledger_assets"
    const val LEDGER_CATEGORIES = "ledger_categories"
    const val LEDGER_RECORDS = "ledger_records"
    const val LEDGER_BUDGETS = "ledger_budgets"
    const val ANNIVERSARIES = "anniversaries"
    const val REMINDERS = "reminders"
    const val TIME_CAPSULES = "time_capsules"
    const val PACKING_TRIPS = "packing_trips"
    const val PACKING_ITEMS = "packing_items"
    const val LEDGER_TEMPLATES = "ledger_templates"
    const val LEDGER_RECURRING_RULES = "ledger_recurring_rules"

    /**
     * 偏好内容（菜谱 / 周菜单 / 自定义状态 / 提醒设置 / 昵称 / 在一起起始日等）。
     *
     * 它们是「整块设置」而非逐条实体，没有 syncId 可以对齐，因此不做成多条记录，
     * 而是整块作为这一个固定键的记录同步：服务端序号大的一方整体胜出。
     * 偏好本来就很少两端同时改，用整体覆盖换掉「一整套逐字段比对」是划算的。
     */
    const val PREFERENCES = "preferences"

    /** [PREFERENCES] 集合里唯一一条记录的键。 */
    const val PREFERENCES_KEY = "preferences"
}

/**
 * 集合名 → 同步表名的唯一映射（P6）。
 *
 * 集合名是跨版本契约（见 [SyncCollections]），表名是本地 Room 实体名；两者目前同名，
 * 但**不假设日后永远同名**，统一从这里派生：迁移按 [TABLES] 逐表加列、
 * `RemoteSyncDao` 的待推派发与单测覆盖也以此为准。这样「新增一张同步表却漏改迁移
 * 或漏改派发」的静默漏同步能被编译/测试挡下来，而不是等到线上数据不一致才发现。
 */
object SyncTables {
    val BY_COLLECTION: Map<String, String> = mapOf(
        SyncCollections.CATEGORIES to "categories",
        SyncCollections.LOCATIONS to "locations",
        SyncCollections.ITEMS to "items",
        SyncCollections.LEDGER_ASSETS to "ledger_assets",
        SyncCollections.LEDGER_CATEGORIES to "ledger_categories",
        SyncCollections.LEDGER_RECORDS to "ledger_records",
        SyncCollections.LEDGER_BUDGETS to "ledger_budgets",
        SyncCollections.ANNIVERSARIES to "anniversaries",
        SyncCollections.REMINDERS to "reminders",
        SyncCollections.TIME_CAPSULES to "time_capsules",
        SyncCollections.PACKING_TRIPS to "packing_trips",
        SyncCollections.PACKING_ITEMS to "packing_items",
        SyncCollections.LEDGER_TEMPLATES to "ledger_templates",
        SyncCollections.LEDGER_RECURRING_RULES to "ledger_recurring_rules"
    )

    /** 14 张同步表的表名，供迁移逐表加列。 */
    val TABLES: List<String> = BY_COLLECTION.values.toList()
}

/**
 * 一条跨设备记录：集合名 + 记录键 + 载荷 + 墓碑标记。
 *
 * [key] 一律用 Snapshot 里的 `syncId`，**绝不用本机自增 id**：
 * 两台设备的自增 id 各指各行，用它做键会把 A 的记录写到 B 的另一条上。
 * [payload] 是 Snapshot 的 JSON 文本（不是实体序列化），[deleted] 只作服务端
 * 统计与过滤用——「这条是不是墓碑」的权威信息仍在载荷内的 `deletedAt` / `purgedAt`。
 */
@Serializable
data class SyncRecord(
    val collection: String,
    val key: String,
    val payload: String,
    val deleted: Boolean = false
)

/**
 * 记录载荷的编解码配置。
 *
 * `encodeDefaults`：默认值也写出来，让同一份数据在两台设备上编出的 JSON 逐字符一致
 * （服务端按载荷比对时不会因省字段而误判为「改过了」）。
 * `ignoreUnknownKeys`：新版本写的载荷被旧版本读到时不至于整条解不开。
 */
private val syncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 把一份完整清单摊成逐条记录。 */
fun AppBackupPayload.toSyncRecords(): List<SyncRecord> = buildList {
    addAll(
        categories.toRecords(
            SyncCollections.CATEGORIES,
            { it.syncId },
            { it.deletedAt != null }
        ) { syncJson.encodeToString(CategorySnapshot.serializer(), it) }
    )
    addAll(
        locations.toRecords(
            SyncCollections.LOCATIONS,
            { it.syncId },
            { it.deletedAt != null }
        ) { syncJson.encodeToString(LocationSnapshot.serializer(), it) }
    )
    addAll(
        items.toRecords(
            SyncCollections.ITEMS,
            { it.syncId },
            { it.deletedAt != null || it.purgedAt != null }
        ) { syncJson.encodeToString(ItemSnapshot.serializer(), it) }
    )
    addAll(
        ledgerAssets.toRecords(
            SyncCollections.LEDGER_ASSETS,
            { it.syncId },
            { it.deletedAt != null }
        ) { syncJson.encodeToString(LedgerAssetSnapshot.serializer(), it) }
    )
    addAll(
        ledgerCategories.toRecords(
            SyncCollections.LEDGER_CATEGORIES,
            { it.syncId },
            { it.deletedAt != null }
        ) { syncJson.encodeToString(LedgerCategorySnapshot.serializer(), it) }
    )
    addAll(
        ledgerRecords.toRecords(
            SyncCollections.LEDGER_RECORDS,
            { it.syncId },
            { it.deletedAt != null }
        ) { syncJson.encodeToString(LedgerRecordSnapshot.serializer(), it) }
    )
    addAll(
        ledgerBudgets.toRecords(
            SyncCollections.LEDGER_BUDGETS,
            { it.syncId },
            { it.deletedAt != null }
        ) { syncJson.encodeToString(LedgerBudgetSnapshot.serializer(), it) }
    )
    addAll(
        anniversaries.toRecords(
            SyncCollections.ANNIVERSARIES,
            { it.syncId },
            { it.deletedAt != null }
        ) { syncJson.encodeToString(AnniversarySnapshot.serializer(), it) }
    )
    addAll(
        reminders.toRecords(
            SyncCollections.REMINDERS,
            { it.syncId },
            { it.deletedAt != null || it.purgedAt != null }
        ) { syncJson.encodeToString(ReminderSnapshot.serializer(), it) }
    )
    addAll(
        timeCapsules.toRecords(
            SyncCollections.TIME_CAPSULES,
            { it.syncId },
            { it.deletedAt != null }
        ) { syncJson.encodeToString(TimeCapsuleSnapshot.serializer(), it) }
    )
    addAll(
        packingTrips.toRecords(
            SyncCollections.PACKING_TRIPS,
            { it.syncId },
            { it.deletedAt != null }
        ) { syncJson.encodeToString(PackingTripSnapshot.serializer(), it) }
    )
    addAll(
        packingItems.toRecords(
            SyncCollections.PACKING_ITEMS,
            { it.syncId },
            { it.deletedAt != null }
        ) { syncJson.encodeToString(PackingItemSnapshot.serializer(), it) }
    )
    addAll(
        ledgerTemplates.toRecords(
            SyncCollections.LEDGER_TEMPLATES,
            { it.syncId },
            { it.deletedAt != null }
        ) { syncJson.encodeToString(LedgerTemplateSnapshot.serializer(), it) }
    )
    addAll(
        ledgerRecurringRules.toRecords(
            SyncCollections.LEDGER_RECURRING_RULES,
            { it.syncId },
            { it.deletedAt != null }
        ) { syncJson.encodeToString(LedgerRecurringRuleSnapshot.serializer(), it) }
    )
    preferences?.let {
        add(
            SyncRecord(
                collection = SyncCollections.PREFERENCES,
                key = SyncCollections.PREFERENCES_KEY,
                payload = syncJson.encodeToString(PreferencesSnapshot.serializer(), it)
            )
        )
    }
}

/**
 * 把服务端拉回的一组记录重组为一份完整清单，供既有的合并通道落地。
 *
 * 只认已知集合：服务端上将来可能放别的用途的记录，不认识的一律忽略，
 * 而不是猜着解。单条解不开的载荷（写入方版本更新、内容损坏）跳过——
 * 一条坏记录不该让整轮同步卡死，那会让其余所有数据都拉不下来。
 */
fun syncRecordsToPayload(
    records: List<SyncRecord>,
    deviceId: String,
    exportedAt: Long = System.currentTimeMillis()
): AppBackupPayload {
    val byCollection = records.groupBy { it.collection }

    fun <T> decode(collection: String, serializer: KSerializer<T>): List<T> =
        byCollection[collection].orEmpty().mapNotNull { record ->
            runCatching { syncJson.decodeFromString(serializer, record.payload) }.getOrNull()
        }

    return AppBackupPayload(
        exportedAt = exportedAt,
        syncVersion = AppBackupManager.SYNC_VERSION,
        deviceId = deviceId,
        categories = decode(SyncCollections.CATEGORIES, CategorySnapshot.serializer()),
        locations = decode(SyncCollections.LOCATIONS, LocationSnapshot.serializer()),
        items = decode(SyncCollections.ITEMS, ItemSnapshot.serializer()),
        ledgerAssets = decode(SyncCollections.LEDGER_ASSETS, LedgerAssetSnapshot.serializer()),
        ledgerCategories = decode(
            SyncCollections.LEDGER_CATEGORIES,
            LedgerCategorySnapshot.serializer()
        ),
        ledgerRecords = decode(SyncCollections.LEDGER_RECORDS, LedgerRecordSnapshot.serializer()),
        ledgerBudgets = decode(SyncCollections.LEDGER_BUDGETS, LedgerBudgetSnapshot.serializer()),
        anniversaries = decode(SyncCollections.ANNIVERSARIES, AnniversarySnapshot.serializer()),
        reminders = decode(SyncCollections.REMINDERS, ReminderSnapshot.serializer()),
        timeCapsules = decode(SyncCollections.TIME_CAPSULES, TimeCapsuleSnapshot.serializer()),
        packingTrips = decode(SyncCollections.PACKING_TRIPS, PackingTripSnapshot.serializer()),
        packingItems = decode(SyncCollections.PACKING_ITEMS, PackingItemSnapshot.serializer()),
        ledgerTemplates = decode(
            SyncCollections.LEDGER_TEMPLATES,
            LedgerTemplateSnapshot.serializer()
        ),
        ledgerRecurringRules = decode(
            SyncCollections.LEDGER_RECURRING_RULES,
            LedgerRecurringRuleSnapshot.serializer()
        ),
        preferences = byCollection[SyncCollections.PREFERENCES]
            ?.firstOrNull()
            ?.let { record ->
                runCatching {
                    syncJson.decodeFromString(PreferencesSnapshot.serializer(), record.payload)
                }.getOrNull()
            }
    )
}

/**
 * 逐条记录化的公共部分：取记录键、判墓碑、编码载荷。
 *
 * 没有 syncId 的旧行**跳过**（返回 null）：它们没有跨设备可用的稳定键，
 * 硬用本机自增 id 顶上去只会把 A 的记录写到 B 的另一条上，比不同步更糟。
 * 这类行的数量由对账页统计呈现，不做静默吞掉。
 */
private inline fun <T> List<T>.toRecords(
    collection: String,
    syncId: (T) -> String?,
    deleted: (T) -> Boolean,
    encode: (T) -> String
): List<SyncRecord> = mapNotNull { value ->
    val key = syncId(value)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    SyncRecord(
        collection = collection,
        key = key,
        payload = encode(value),
        deleted = deleted(value)
    )
}
