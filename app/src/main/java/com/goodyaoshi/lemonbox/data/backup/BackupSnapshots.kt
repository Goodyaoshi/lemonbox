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
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
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
