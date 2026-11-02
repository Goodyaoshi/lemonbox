package com.goodyaoshi.lemonbox.data.backup

import kotlinx.serialization.Serializable

/**
 * 一条同步记录的「身份证」：集合前缀 + 稳定键 + 最后修改时间。
 * 清单里不含任何内容，所以交换它的成本只与记录条数有关，与图片、账目正文的体量无关。
 */
@Serializable
data class SyncManifestEntry(
    val key: String,
    val updatedAt: Long = 0L
)

/**
 * 本机全部同步记录的轻量清单。
 *
 * 局域网同步的第一步就是交换它：双方先算出「谁缺哪条、谁的那份更新」，
 * 再只传真正需要的那部分，而不是先各推一份整包、比对完再丢掉大半。
 */
@Serializable
data class SyncManifest(
    val entries: List<SyncManifestEntry> = emptyList(),
    val exportedAt: Long = 0L
) {
    fun updatedAtMap(): Map<String, Long> = entries.associate { it.key to it.updatedAt }
}

/** 清单比对结果：双方各自还缺哪些记录。 */
@Serializable
data class SyncManifestDiff(
    /** 服务端还缺的键：需要发起方把这些记录推上来。 */
    val hostNeeds: List<String> = emptyList(),
    /** 发起方还缺的键：服务端应只把这些记录下发过去。 */
    val clientNeeds: List<String> = emptyList(),
    /** 两边时间戳一致、无需传输的条数，用于界面回执。 */
    val upToDate: Int = 0
) {
    val nothingToSend: Boolean get() = hostNeeds.isEmpty()
    val nothingToReceive: Boolean get() = clientNeeds.isEmpty()
}

/**
 * 比较两份清单：按 key 对齐，`updatedAt` 较新的一方胜；一方缺失即视为对方需要。
 *
 * 时间戳相同视为一致，不再传输——重复发送同一份内容只会浪费两端 IO，
 * 落地的 LWW 合并也吸收不了任何新信息。
 */
fun diffManifests(host: SyncManifest, client: SyncManifest): SyncManifestDiff {
    val hostMap = host.updatedAtMap()
    val clientMap = client.updatedAtMap()
    val hostNeeds = clientMap.keys.filter { key ->
        val hostAt = hostMap[key]
        hostAt == null || clientMap.getValue(key) > hostAt
    }
    val clientNeeds = hostMap.keys.filter { key ->
        val clientAt = clientMap[key]
        clientAt == null || hostMap.getValue(key) > clientAt
    }
    val upToDate = (hostMap.keys intersect clientMap.keys).count { key ->
        hostMap.getValue(key) == clientMap.getValue(key)
    }
    return SyncManifestDiff(
        hostNeeds = hostNeeds,
        clientNeeds = clientNeeds,
        upToDate = upToDate
    )
}

/**
 * 把完整清单摊成轻量清单。
 *
 * 键格式与导入端的白名单（`SyncEntityKind.name:syncId`）一致，
 * 这样比对结果可以直接当作「只导出这些记录」的过滤条件使用，不需要再换一套键。
 */
fun AppBackupPayload.toManifest(): SyncManifest {
    val entries = mutableListOf<SyncManifestEntry>()
    // 缺 syncId 的旧行没有跨设备稳定键，清单里直接不列（与逐条记录化时的处理一致）。
    fun add(kind: SyncEntityKind, syncId: String?, updatedAt: Long?) {
        val key = syncId?.takeIf { it.isNotBlank() } ?: return
        entries += SyncManifestEntry("${kind.name}:$key", updatedAt ?: 0L)
    }
    categories.forEach { add(SyncEntityKind.CATEGORY, it.syncId, it.updatedAt) }
    locations.forEach { add(SyncEntityKind.LOCATION, it.syncId, it.updatedAt) }
    items.forEach { add(SyncEntityKind.ITEM, it.syncId, it.updatedAt) }
    ledgerAssets.forEach { add(SyncEntityKind.LEDGER_ASSET, it.syncId, it.updatedAt) }
    ledgerCategories.forEach { add(SyncEntityKind.LEDGER_CATEGORY, it.syncId, it.updatedAt) }
    ledgerRecords.forEach { add(SyncEntityKind.LEDGER_RECORD, it.syncId, it.updatedAt) }
    ledgerBudgets.forEach { add(SyncEntityKind.LEDGER_BUDGET, it.syncId, it.updatedAt) }
    anniversaries.forEach { add(SyncEntityKind.ANNIVERSARY, it.syncId, it.updatedAt) }
    reminders.forEach { add(SyncEntityKind.REMINDER, it.syncId, it.updatedAt) }
    timeCapsules.forEach { add(SyncEntityKind.TIME_CAPSULE, it.syncId, it.updatedAt) }
    packingTrips.forEach { add(SyncEntityKind.PACKING_TRIP, it.syncId, it.updatedAt) }
    packingItems.forEach { add(SyncEntityKind.PACKING_ITEM, it.syncId, it.updatedAt) }
    ledgerTemplates.forEach { add(SyncEntityKind.LEDGER_TEMPLATE, it.syncId, it.updatedAt) }
    ledgerRecurringRules.forEach { add(SyncEntityKind.LEDGER_RECURRING_RULE, it.syncId, it.updatedAt) }
    preferences?.let {
        entries += SyncManifestEntry(
            key = "${SyncCollections.PREFERENCES}:${SyncCollections.PREFERENCES_KEY}",
            updatedAt = it.updatedAt ?: 0L
        )
    }
    return SyncManifest(entries = entries, exportedAt = exportedAt)
}
