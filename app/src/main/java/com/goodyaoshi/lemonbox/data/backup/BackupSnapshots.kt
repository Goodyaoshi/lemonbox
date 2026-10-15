package com.goodyaoshi.lemonbox.data.backup

import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusOption
import com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset
import com.goodyaoshi.lemonbox.data.local.entity.LedgerBudget
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.local.entity.LegacyStatusMapping
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.local.entity.PackingItem
import com.goodyaoshi.lemonbox.data.local.entity.PackingTrip
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.data.local.entity.TimeCapsule
import com.goodyaoshi.lemonbox.data.meal.MealSpec
import com.goodyaoshi.lemonbox.data.meal.Recipe
import kotlinx.serialization.Serializable

/**
 * 备份 / 同步用到的全部快照数据结构（F9）。
 *
 * 这些类型只描述「跨设备传输时一份数据长什么样」，与「怎么读库、怎么写库」无关：
 * 拆分前它们和导入导出流程共处一个近 1600 行的文件，改一个字段要在流程代码里翻找。
 * 独立成文件后可以一眼看全备份契约，也便于和 `SYNC_VERSION` 的演进对照审阅。
 */

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
    // 时间胶囊与旅行打包清单，v9 起导出。此前这两类从未进备份，
    // 因此多设备之间始终各存各的（用户反馈的「每个设备都不同步」）。
    val timeCapsules: List<TimeCapsuleSnapshot> = emptyList(),
    val packingTrips: List<PackingTripSnapshot> = emptyList(),
    val packingItems: List<PackingItemSnapshot> = emptyList(),
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
 * togetherStartDate/togetherStartDateUpdatedAt/togetherLabelName 是 v8 起的「在一起」计数配置。
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
    val mealPrepFireTime: String? = null,
    /** 「在一起」起始日（yyyy-MM-dd）；null 表示未设置。 */
    val togetherStartDate: String? = null,
    /** 起始日最近修改时间戳（毫秒），多设备合并按「较新者胜」。 */
    val togetherStartDateUpdatedAt: Long? = null,
    /** 首页计数前缀（如「我们」）。 */
    val togetherLabelName: String? = null
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

/**
 * 时间胶囊快照（v9 起导出）。
 *
 * 此前这类数据从未进备份，多设备之间因此始终各存各的。syncId/updatedAt/deletedAt
 * 三件套让它能直接走通用合并规则（较新者胜 + 墓碑传播）。
 */
@Serializable
data class TimeCapsuleSnapshot(
    val id: Long,
    val title: String,
    val content: String = "",
    val unlockAt: Long,
    val openedAt: Long? = null,
    val forWhom: String? = null,
    val createdAt: Long,
    val syncId: String? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null
) {
    fun toCapsule(targetId: Long): TimeCapsule = TimeCapsule(
        id = targetId,
        title = title,
        content = content,
        unlockAt = unlockAt,
        openedAt = openedAt,
        forWhom = forWhom,
        createdAt = createdAt,
        syncId = syncId,
        updatedAt = updatedAt ?: createdAt,
        deletedAt = deletedAt
    )

    companion object {
        fun fromEntity(capsule: TimeCapsule): TimeCapsuleSnapshot = TimeCapsuleSnapshot(
            id = capsule.id,
            title = capsule.title,
            content = capsule.content,
            unlockAt = capsule.unlockAt,
            openedAt = capsule.openedAt,
            forWhom = capsule.forWhom,
            createdAt = capsule.createdAt,
            syncId = capsule.syncId,
            updatedAt = capsule.updatedAt,
            deletedAt = capsule.deletedAt
        )
    }
}

/** 旅行打包行程快照（v9 起导出）。 */
@Serializable
data class PackingTripSnapshot(
    val id: Long,
    val name: String,
    val destination: String = "",
    val startDate: String? = null,
    val endDate: String? = null,
    val createdAt: Long,
    val syncId: String? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null
) {
    fun toTrip(targetId: Long): PackingTrip = PackingTrip(
        id = targetId,
        name = name,
        destination = destination,
        startDate = startDate,
        endDate = endDate,
        createdAt = createdAt,
        syncId = syncId,
        updatedAt = updatedAt ?: createdAt,
        deletedAt = deletedAt
    )

    companion object {
        fun fromEntity(trip: PackingTrip): PackingTripSnapshot = PackingTripSnapshot(
            id = trip.id,
            name = trip.name,
            destination = trip.destination,
            startDate = trip.startDate,
            endDate = trip.endDate,
            createdAt = trip.createdAt,
            syncId = trip.syncId,
            updatedAt = trip.updatedAt,
            deletedAt = trip.deletedAt
        )
    }
}

/**
 * 打包条目快照（v9 起导出）。
 *
 * [tripSyncId] 与 [tripId] 的关系同 ItemSnapshot 的 categorySyncId/categoryId：
 * 跨设备合并时本地 tripId 对不上，必须靠稳定的 syncId 重新解析归属行程。
 */
@Serializable
data class PackingItemSnapshot(
    val id: Long,
    val tripId: Long,
    val name: String,
    val quantity: Int = 1,
    val category: String = "",
    val checked: Boolean = false,
    val sort: Int = 0,
    val createdAt: Long,
    val syncId: String? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null,
    val tripSyncId: String? = null
) {
    fun toPackingItem(targetId: Long, resolvedTripId: Long): PackingItem = PackingItem(
        id = targetId,
        tripId = resolvedTripId,
        name = name,
        quantity = quantity,
        category = category,
        checked = checked,
        sort = sort,
        createdAt = createdAt,
        syncId = syncId,
        updatedAt = updatedAt ?: createdAt,
        deletedAt = deletedAt
    )

    companion object {
        fun fromEntity(item: PackingItem, tripSyncId: String?): PackingItemSnapshot =
            PackingItemSnapshot(
                id = item.id,
                tripId = item.tripId,
                name = item.name,
                quantity = item.quantity,
                category = item.category,
                checked = item.checked,
                sort = item.sort,
                createdAt = item.createdAt,
                syncId = item.syncId,
                updatedAt = item.updatedAt,
                deletedAt = item.deletedAt,
                tripSyncId = tripSyncId
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
    /** 生产日期；仅作参考，为 null 表示备份里没有。 */
    val productionDate: Long? = null,
    /** 开始使用时间；使用周期统计，为 null 表示备份里没有。 */
    val startUseTime: Long? = null,
    /** 使用结束时间；使用周期统计，为 null 表示备份里没有。 */
    val usageEndedAt: Long? = null,
    /** 计量方式（0=按件消耗 1=持续使用）；为 null 表示备份里没有。 */
    val trackMode: Int? = null,
    /** 待买归属码值（self/partner/shared）；v8 起导出，null 表示未标注。 */
    val forWhom: String? = null,
    val rating: Int?,
    val ratedAt: Long?,
    val deletedAt: Long?,
    /**
     * 「永久删除」墓碑标记（F21）。非空表示这条是永久删除留下的墓碑：
     * 对方合并时直接物理清除，而不是把它重新插入库中。
     */
    val purgedAt: Long? = null,
    val note: String,
    /**
     * 图片槽位的内容哈希（F22），顺序即展示顺序（首项为主图）。
     *
     * 只传哈希而不打包图片文件：图片本体在对象存储上，两台设备指向同一个桶，
     * 对方按哈希推导对象键即可取回。这也让备份体积与图片数量无关。
     *
     * 为 null 表示旧备份（那时存的是本地路径与对象键），按「无图片」处理——
     * 早期版本的图片信息不再还原，但其余字段完好。
     */
    val imageHashes: List<String>? = null,
    val createdAt: Long,
    val syncId: String? = null,
    val categorySyncId: String? = null,
    val locationSyncId: String? = null,
    val updatedAt: Long? = null
) {
    fun toItem(
        targetId: Long,
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
            productionDate = productionDate,
            startUseTime = startUseTime,
            usageEndedAt = usageEndedAt,
            trackMode = trackMode ?: Item.TRACK_CONSUMABLE,
            forWhom = forWhom,
            rating = rating,
            ratedAt = ratedAt,
            deletedAt = deletedAt,
            purgedAt = purgedAt,
            note = note,
            imageHashes = Item.encodeImageHashes(imageHashes.orEmpty()),
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
            imageHashes: List<String>,
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
                productionDate = item.productionDate,
                startUseTime = item.startUseTime,
                usageEndedAt = item.usageEndedAt,
                trackMode = item.trackMode,
                forWhom = item.forWhom,
                rating = item.rating,
                ratedAt = item.ratedAt,
                deletedAt = item.deletedAt,
                purgedAt = item.purgedAt,
                note = item.note,
                imageHashes = imageHashes,
                createdAt = item.createdAt,
                syncId = item.syncId,
                categorySyncId = categorySyncId,
                locationSyncId = locationSyncId,
                updatedAt = item.updatedAt
            )
        }
    }
}
