package com.goodyaoshi.lemonbox.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.goodyaoshi.lemonbox.data.local.dao.AnniversaryDao
import com.goodyaoshi.lemonbox.data.local.dao.CategoryDao
import com.goodyaoshi.lemonbox.data.local.dao.ItemDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerAssetDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerBudgetDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerCategoryDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerRecordDao
import com.goodyaoshi.lemonbox.data.local.dao.LocationDao
import com.goodyaoshi.lemonbox.data.local.dao.ReminderDao
import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.LedgerAsset
import com.goodyaoshi.lemonbox.data.local.entity.LedgerBudget
import com.goodyaoshi.lemonbox.data.local.entity.LedgerCategory
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.util.LegacyTextNormalizer

@Database(
    entities = [
        Item::class,
        Category::class,
        Location::class,
        Reminder::class,
        Anniversary::class,
        LedgerRecord::class,
        LedgerCategory::class,
        LedgerAsset::class,
        LedgerBudget::class
    ],
    version = 26,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao
    abstract fun categoryDao(): CategoryDao
    abstract fun locationDao(): LocationDao
    abstract fun reminderDao(): ReminderDao
    abstract fun anniversaryDao(): AnniversaryDao
    abstract fun ledgerRecordDao(): LedgerRecordDao
    abstract fun ledgerCategoryDao(): LedgerCategoryDao
    abstract fun ledgerAssetDao(): LedgerAssetDao
    abstract fun ledgerBudgetDao(): LedgerBudgetDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN rating INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN ratedAt INTEGER")
                SeedHelper.normalizeLegacyData(db)
                SeedHelper.ensureSeedData(db)
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN deletedAt INTEGER")
                SeedHelper.normalizeLegacyData(db)
                SeedHelper.ensureSeedData(db)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN imagePaths TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    UPDATE items
                    SET imagePaths = CASE
                        WHEN TRIM(COALESCE(imagePath, '')) = '' THEN ''
                        ELSE imagePath
                    END
                    """.trimIndent()
                )
                SeedHelper.normalizeLegacyData(db)
                SeedHelper.ensureSeedData(db)
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedHelper.normalizeLegacyData(db)
                SeedHelper.ensureSeedData(db)
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN barcode TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_items_barcode ON items(barcode)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS item_embeddings (
                        itemId INTEGER NOT NULL,
                        vector BLOB NOT NULL,
                        dim INTEGER NOT NULL,
                        detectedLabel TEXT,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(itemId),
                        FOREIGN KEY(itemId) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_item_embeddings_itemId " +
                        "ON item_embeddings(itemId)"
                )
            }
        }

        /** 移除云端 AI 模型配置表：本地端侧推理不再需要任何模型连接信息。 */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS ai_model_configs")
            }
        }

        /**
         * 为跨设备合并引入稳定标识 syncId 与修改时间 updatedAt。
         * 列为可空且无默认值，避免 Room 对迁移默认值的严格校验；
         * 历史数据在这里一次性回填 syncId，并让 updatedAt 回退到 createdAt。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("items", "categories", "locations").forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN syncId TEXT")
                    db.execSQL("ALTER TABLE $table ADD COLUMN updatedAt INTEGER")
                    db.execSQL(
                        "UPDATE $table SET syncId = lower(hex(randomblob(16))) " +
                            "WHERE syncId IS NULL"
                    )
                }
                db.execSQL("UPDATE items SET updatedAt = createdAt WHERE updatedAt IS NULL")
                db.execSQL("UPDATE categories SET updatedAt = 0 WHERE updatedAt IS NULL")
                db.execSQL("UPDATE locations SET updatedAt = 0 WHERE updatedAt IS NULL")
            }
        }

        /**
         * 为分类与位置引入软删除墓碑 deletedAt，使删除也能通过备份/同步传递。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE locations ADD COLUMN deletedAt INTEGER")
            }
        }

        /** 移除端侧 AI 识别后，不再需要物品图片向量表。 */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS item_embeddings")
            }
        }

        /**
         * 分类升级为多级：新增 parentId。
         * 只加普通可空列 + 索引（ALTER TABLE 无法补外键），级联删除由
         * CategoryRepository 的软删除逻辑处理。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN parentId INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_categories_parentId " +
                        "ON categories(parentId)"
                )
            }
        }

        /**
         * 状态模型重构：单值 status 拆成三个正交字段
         * usageStatus（使用进度）、disposition（物品去向）、needRestock（需要补货）。
         *
         * SQLite 不支持删除已有列，因此按 Room 导出的新表结构重建 items 表，
         * 再把旧 status 按映射表逐行转换后灌入新表。映射规则与
         * [Item.fromLegacyStatus] 保持一致。
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `items_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `barcode` TEXT,
                        `categoryId` INTEGER,
                        `locationId` INTEGER,
                        `quantity` INTEGER NOT NULL,
                        `unit` TEXT NOT NULL,
                        `price` REAL,
                        `expireTime` INTEGER,
                        `usageStatus` INTEGER NOT NULL,
                        `disposition` INTEGER NOT NULL,
                        `needRestock` INTEGER NOT NULL,
                        `rating` INTEGER,
                        `ratedAt` INTEGER,
                        `deletedAt` INTEGER,
                        `note` TEXT NOT NULL,
                        `imagePath` TEXT NOT NULL,
                        `imagePaths` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `syncId` TEXT,
                        `updatedAt` INTEGER,
                        FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`locationId`) REFERENCES `locations`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `items_new` (
                        `id`, `name`, `barcode`, `categoryId`, `locationId`, `quantity`, `unit`,
                        `price`, `expireTime`, `usageStatus`, `disposition`, `needRestock`,
                        `rating`, `ratedAt`, `deletedAt`, `note`, `imagePath`, `imagePaths`,
                        `createdAt`, `syncId`, `updatedAt`
                    )
                    SELECT
                        `id`, `name`, `barcode`, `categoryId`, `locationId`, `quantity`, `unit`,
                        `price`, `expireTime`,
                        CASE `status`
                            WHEN 0 THEN 1
                            WHEN 1 THEN 2
                            WHEN 2 THEN 1
                            WHEN 3 THEN 2
                            WHEN 4 THEN 1
                            WHEN 5 THEN 1
                            WHEN 6 THEN 1
                            WHEN 7 THEN 0
                            ELSE `status`
                        END,
                        CASE `status`
                            WHEN 2 THEN 3
                            WHEN 4 THEN 1
                            WHEN 5 THEN 2
                            ELSE 0
                        END,
                        CASE WHEN `status` = 3 THEN 1 ELSE 0 END,
                        `rating`, `ratedAt`, `deletedAt`, `note`, `imagePath`, `imagePaths`,
                        `createdAt`, `syncId`, `updatedAt`
                    FROM `items`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `items`")
                db.execSQL("ALTER TABLE `items_new` RENAME TO `items`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_categoryId` " +
                        "ON `items` (`categoryId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_locationId` " +
                        "ON `items` (`locationId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_usageStatus` " +
                        "ON `items` (`usageStatus`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_disposition` " +
                        "ON `items` (`disposition`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_barcode` " +
                        "ON `items` (`barcode`)"
                )
            }
        }

        /**
         * 内置保护数据：categories / locations 新增 isProtected 列，并把
         * 食品分类树（菜谱食材匹配依赖）与冰箱（取材位置依赖）标记为受保护。
         * 顺带补种食品的三级分类（子分类 + 具体分类），老安装也能拿到完整食材分类。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN isProtected INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE locations ADD COLUMN isProtected INTEGER NOT NULL DEFAULT 0")
                SeedHelper.ensureProtectedSeedData(db)
            }
        }

        /**
         * 内置一级分类图标差异化 + 新增「化妆品」：
         * 老安装按名称回填默认图标，并补种缺失的内置分类（含化妆品）。
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedHelper.ensureSeedCategories(db)
            }
        }

        /**
         * 新增「厨具」「家电」，并把食品下的「零食饮品」升级为同级一级分类「零食」。
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedHelper.promoteSnackCategory(db)
                SeedHelper.ensureSeedCategories(db)
            }
        }

        /**
         * 家当卡片展示分类图标：叶子分类的 icon 为空，沿分类树继承父级图标；
         * 同时为物品补上提醒阶梯字段（空 = 跟随全局默认阶梯）。
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN reminderDays TEXT")
                SeedHelper.inheritCategoryIcons(db)
            }
        }

        /**
         * 葱姜蒜、辣椒只是辅料：新增受保护子分类「辅料」（葱/姜/蒜/辣椒），
         * 把蔬菜下的「葱姜蒜」改名「蒜」、「辣椒」挪入辅料，物品引用自动跟随。
         */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedHelper.separateAuxiliaryCategory(db)
            }
        }

        /**
         * 待办提醒：新表 reminders 承载一次性（某天某时）与周期性
         * （每天/每隔 N 天/每周几）提醒，到点由 TodoReminderWorker 通知并推进。
         */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reminders` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `repeatType` TEXT NOT NULL,
                        `intervalDays` INTEGER NOT NULL,
                        `weekdays` TEXT NOT NULL,
                        `fireTime` TEXT NOT NULL,
                        `targetDate` TEXT,
                        `nextFireAt` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `source` TEXT NOT NULL,
                        `sourceKey` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reminders_nextFireAt` " +
                        "ON `reminders` (`nextFireAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reminders_sourceKey` " +
                        "ON `reminders` (`sourceKey`)"
                )
            }
        }

        /**
         * 待办提醒语义：到点只通知不自动完结，一次性提醒保持待办等手动完成。
         * notifiedAt 记录最近一次发通知的时间（0=未提醒），既防重复通知，
         * 也让列表能把「已提醒待完成」的项标出来。
         */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `reminders` ADD COLUMN `notifiedAt` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 物品使用周期统计：购买日期、开始使用、使用结束三个时间点，
         * 外加计量方式（按件消耗 / 持续使用，耐用品不扣数量只计天数）。
         * 只加列不回填，历史记录不展示使用统计。
         */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `items` ADD COLUMN `purchaseDate` INTEGER")
                db.execSQL("ALTER TABLE `items` ADD COLUMN `startUseTime` INTEGER")
                db.execSQL("ALTER TABLE `items` ADD COLUMN `usageEndedAt` INTEGER")
                db.execSQL(
                    "ALTER TABLE `items` ADD COLUMN `trackMode` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 记账模块：新增账单 / 记账分类 / 账户 / 预算四张表，
         * 并种入默认账户（微信/支付宝/现金/银行卡）与默认记账分类。
         */
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ledger_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `type` INTEGER NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `categoryId` INTEGER,
                        `assetId` INTEGER,
                        `targetAssetId` INTEGER,
                        `recordTime` INTEGER NOT NULL,
                        `remark` TEXT NOT NULL,
                        `itemId` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `syncId` TEXT,
                        `updatedAt` INTEGER,
                        `deletedAt` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ledger_records_recordTime` " +
                        "ON `ledger_records` (`recordTime`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ledger_records_assetId` " +
                        "ON `ledger_records` (`assetId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ledger_records_targetAssetId` " +
                        "ON `ledger_records` (`targetAssetId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ledger_records_categoryId` " +
                        "ON `ledger_records` (`categoryId`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ledger_categories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `icon` TEXT NOT NULL,
                        `kind` INTEGER NOT NULL,
                        `sort` INTEGER NOT NULL,
                        `parentId` INTEGER,
                        `isProtected` INTEGER NOT NULL,
                        `syncId` TEXT,
                        `updatedAt` INTEGER,
                        `deletedAt` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ledger_categories_kind` " +
                        "ON `ledger_categories` (`kind`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ledger_assets` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `icon` TEXT NOT NULL,
                        `sort` INTEGER NOT NULL,
                        `initialBalance` INTEGER NOT NULL,
                        `type` INTEGER NOT NULL,
                        `syncId` TEXT,
                        `updatedAt` INTEGER,
                        `deletedAt` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ledger_budgets` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `categoryId` INTEGER,
                        `amount` INTEGER NOT NULL,
                        `period` TEXT NOT NULL,
                        `syncId` TEXT,
                        `updatedAt` INTEGER,
                        `deletedAt` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ledger_budgets_categoryId` " +
                        "ON `ledger_budgets` (`categoryId`)"
                )
                SeedHelper.ensureLedgerSeedData(db)
            }
        }

        /**
         * 纪念日模块：新表 anniversaries（锚点日期 + 每年循环 + 农历 + 提前提醒）；
         * 同时给 reminders 补上跨设备合并三件套（syncId/updatedAt/deletedAt），
         * 让待办提醒也进备份与局域网同步。
         */
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `anniversaries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `date` TEXT NOT NULL,
                        `isLunar` INTEGER NOT NULL,
                        `lunarMonth` INTEGER NOT NULL,
                        `lunarDay` INTEGER NOT NULL,
                        `repeatYearly` INTEGER NOT NULL,
                        `remindDays` TEXT NOT NULL,
                        `lastNotifiedDate` TEXT,
                        `enabled` INTEGER NOT NULL,
                        `syncId` TEXT,
                        `updatedAt` INTEGER,
                        `deletedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                listOf("syncId TEXT", "updatedAt INTEGER", "deletedAt INTEGER").forEach { column ->
                    db.execSQL("ALTER TABLE `reminders` ADD COLUMN `$column`")
                }
                db.execSQL(
                    "UPDATE reminders SET syncId = lower(hex(randomblob(16))) " +
                        "WHERE syncId IS NULL"
                )
                db.execSQL(
                    "UPDATE reminders SET updatedAt = createdAt WHERE updatedAt IS NULL"
                )
            }
        }

        /**
         * 纪念日重复周期：「每年循环」开关升级为 repeatUnit（不循环/天/周/月/年）
         * + repeatInterval（每 N 个单位）。SQLite 不支持直接删列，按 Room 惯例
         * 重建表搬数据，repeatYearly=true 的旧数据回填成「每年」。
         */
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `anniversaries_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `date` TEXT NOT NULL,
                        `isLunar` INTEGER NOT NULL,
                        `lunarMonth` INTEGER NOT NULL,
                        `lunarDay` INTEGER NOT NULL,
                        `repeatUnit` TEXT NOT NULL,
                        `repeatInterval` INTEGER NOT NULL,
                        `remindDays` TEXT NOT NULL,
                        `lastNotifiedDate` TEXT,
                        `enabled` INTEGER NOT NULL,
                        `syncId` TEXT,
                        `updatedAt` INTEGER,
                        `deletedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `anniversaries_new`
                        (`id`, `name`, `note`, `date`, `isLunar`, `lunarMonth`, `lunarDay`,
                         `repeatUnit`, `repeatInterval`, `remindDays`, `lastNotifiedDate`,
                         `enabled`, `syncId`, `updatedAt`, `deletedAt`, `createdAt`)
                    SELECT `id`, `name`, `note`, `date`, `isLunar`, `lunarMonth`, `lunarDay`,
                        CASE WHEN `repeatYearly` = 1 THEN 'YEAR' ELSE 'NONE' END,
                        1, `remindDays`, `lastNotifiedDate`,
                        `enabled`, `syncId`, `updatedAt`, `deletedAt`, `createdAt`
                    FROM `anniversaries`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `anniversaries`")
                db.execSQL("ALTER TABLE `anniversaries_new` RENAME TO `anniversaries`")
            }
        }

        /**
         * 纪念日重构为类型制：新增 type 列（0 倒数日 / 1 正数日 / 2 生日）。
         * 这里必须保留既有纪念日数据，仅重建表结构并回填默认类型，
         * 绝不能用 DROP TABLE 造成用户数据静默丢失。
         */
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `anniversaries_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `type` INTEGER NOT NULL,
                        `date` TEXT NOT NULL,
                        `isLunar` INTEGER NOT NULL,
                        `lunarMonth` INTEGER NOT NULL,
                        `lunarDay` INTEGER NOT NULL,
                        `repeatUnit` TEXT NOT NULL,
                        `repeatInterval` INTEGER NOT NULL,
                        `remindDays` TEXT NOT NULL,
                        `lastNotifiedDate` TEXT,
                        `enabled` INTEGER NOT NULL,
                        `syncId` TEXT,
                        `updatedAt` INTEGER,
                        `deletedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // 历史纪念日此前无类型概念，统一归为「倒数日」(0)，其余字段原样保留。
                db.execSQL(
                    """
                    INSERT INTO `anniversaries_new`
                        (`id`, `name`, `note`, `type`, `date`, `isLunar`, `lunarMonth`, `lunarDay`,
                         `repeatUnit`, `repeatInterval`, `remindDays`, `lastNotifiedDate`,
                         `enabled`, `syncId`, `updatedAt`, `deletedAt`, `createdAt`)
                    SELECT `id`, `name`, `note`, 0, `date`, `isLunar`, `lunarMonth`, `lunarDay`,
                        `repeatUnit`, `repeatInterval`, `remindDays`, `lastNotifiedDate`,
                        `enabled`, `syncId`, `updatedAt`, `deletedAt`, `createdAt`
                    FROM `anniversaries`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `anniversaries`")
                db.execSQL("ALTER TABLE `anniversaries_new` RENAME TO `anniversaries`")
            }
        }

        /**
         * 按件消耗物品新增「已消耗数量」列：总量 = 剩余(quantity) + 已用(consumedQuantity)。
         * 历史数据默认为 0，剩余即当前 quantity，总量从当前值起算。
         */
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `items` ADD COLUMN `consumedQuantity` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun buildDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lemonbox.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                        MIGRATION_23_24,
                        MIGRATION_24_25,
                        MIGRATION_25_26
                    )
                    .addCallback(PrepopulateCallback())
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }

    /**
     * 种子数据只在数据库首次创建时写入；历史数据的归一化交给一次性迁移处理，
     * 不再每次打开数据库都跑一遍 SQL。
     */
    private class PrepopulateCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            SeedHelper.ensureSeedData(db)
            SeedHelper.ensureProtectedSeedData(db)
            SeedHelper.ensureLedgerSeedData(db)
        }
    }
}

private object SeedHelper {
    /**
     * 内置一级分类与默认图标 key（key 对应分类页图标目录 categoryIconOptions）。
     */
    private val defaultCategories = linkedMapOf(
        "食品" to "food",
        "药品" to "medicine",
        "日用品" to "home",
        "数码" to "digital",
        "衣物" to "clothes",
        "文具" to "stationery",
        "工具" to "tool",
        "化妆品" to "beauty",
        "厨具" to "kitchen",
        "家电" to "appliance",
        "零食" to "snack",
        "其他" to "other"
    )

    /** 「今天吃什么」的取材位置，随保护位一起种子化、不可删改。 */
    private const val MEAL_LOCATION = "冰箱"

    /**
     * 「食品」分类的预置子分类与具体分类（常规家常分类）。
     * 菜谱按这些具体分类匹配食材，属于基础规则，随保护位一起种子化、不可删改。
     */
    private val protectedFoodSubtree = linkedMapOf(
        "蔬菜" to listOf(
            "青菜", "番茄", "土豆", "黄瓜", "青椒", "洋葱", "白菜", "萝卜",
            "茄子", "豆角", "菌菇", "木耳"
        ),
        "水果" to listOf("苹果", "香蕉", "橙子", "葡萄", "草莓", "梨"),
        "肉禽" to listOf("猪肉", "排骨", "牛肉", "羊肉", "鸡肉", "鸭肉"),
        "蛋类" to listOf("鸡蛋", "鸭蛋", "鹌鹑蛋"),
        "水产" to listOf("鱼", "虾", "贝类", "紫菜", "海带"),
        "豆制品" to listOf("豆腐", "豆干", "腐竹"),
        "主食粮油" to listOf("大米", "面粉", "面条", "杂粮", "食用油"),
        "乳品" to listOf("牛奶", "酸奶", "奶酪", "黄油"),
        "调味品" to listOf(
            "酱油", "醋", "盐", "糖", "料酒", "蚝油", "豆瓣酱",
            "香油", "淀粉", "香料", "鸡精"
        ),
        // 葱姜蒜、辣椒只是辅料，不算主菜蔬菜，单独分组（v18 起从「蔬菜」拆出）。
        "辅料" to listOf("葱", "姜", "蒜", "辣椒")
    )

    /** 「零食」原是食品下的「零食饮品」子分类，v16 起升级为同级一级分类，仍受保护。 */
    private const val SNACK_CATEGORY = "零食"
    private val snackLeaves = listOf("零食", "饼干", "坚果", "饮料", "茶叶", "咖啡")

    /** 「辅料」是 v18 新增的食品子分类：葱姜蒜、辣椒等配料从「蔬菜」拆出，仍受保护。 */
    private const val AUXILIARY_CATEGORY = "辅料"

    private val roomHierarchy = linkedMapOf(
        "厨房" to listOf("冰箱", "橱柜", "台面"),
        "客厅" to listOf("电视柜", "茶几", "书架"),
        "卧室" to listOf("衣柜", "床头柜", "梳妆台"),
        "卫生间" to listOf("洗手台", "淋浴间"),
        "阳台" to listOf("储物柜"),
        "书房" to listOf("书桌", "书柜"),
        "储物间" to listOf("收纳架")
    )

    fun ensureSeedData(db: SupportSQLiteDatabase) {
        ensureSeedCategories(db)

        val homeId = ensureHomeRoot(db)
        roomHierarchy.forEach { (room, children) ->
            val roomId = ensureLocation(db, room, homeId)
            children.forEach { child ->
                ensureLocation(db, child, roomId)
            }
        }
    }

    /**
     * 受保护的基础数据（依赖 isProtected 列，只在 v14+ 的建库/迁移里调用）：
     * 补种「食品 → 子分类 → 具体分类」三级结构并把整棵树标为受保护，
     * 同时把「冰箱」位置标为受保护。
     */
    fun ensureProtectedSeedData(db: SupportSQLiteDatabase) {
        val foodId = ensureProtectedCategory(db, "食品", null)
        protectedFoodSubtree.forEach { (subName, leaves) ->
            val subId = ensureProtectedCategory(db, subName, foodId)
            leaves.forEach { leaf ->
                ensureProtectedCategory(db, leaf, subId)
            }
        }
        // 「零食」与食品同级（首次建库时已由默认分类带图标种入），这里补保护位并挂叶子
        val snackId = ensureProtectedCategory(db, SNACK_CATEGORY, null)
        snackLeaves.forEach { leaf ->
            ensureProtectedCategory(db, leaf, snackId)
        }
        db.execSQL(
            "UPDATE locations SET isProtected = 1 " +
                "WHERE name = '${MEAL_LOCATION.sql()}' AND deletedAt IS NULL"
        )
        inheritCategoryIcons(db)
    }

    /**
     * 让 icon 为空的子分类沿分类树继承最近的非空图标（家当卡片据此展示分类图标）。
     * 单条 UPDATE 内的子查询看不到同语句刚写入的值，故重复几轮以覆盖更深的层级。
     */
    fun inheritCategoryIcons(db: SupportSQLiteDatabase) {
        repeat(4) {
            db.execSQL(
                """
                UPDATE categories
                SET icon = (
                    SELECT parent.icon FROM categories AS parent
                    WHERE parent.id = categories.parentId
                )
                WHERE (icon IS NULL OR icon = '')
                  AND parentId IS NOT NULL
                  AND EXISTS (
                    SELECT 1 FROM categories AS parent
                    WHERE parent.id = categories.parentId
                      AND parent.icon IS NOT NULL
                      AND parent.icon <> ''
                  )
                """.trimIndent()
            )
        }
    }

    /**
     * v15→v16：「零食饮品」子分类升级为一级分类「零食」。
     * 先改名并提升到顶级（保留保护位，叶子挂靠关系不变），再补种兜底，最后由
     * ensureSeedCategories 回填图标。注意顺序：必须先升级再按名称回填，否则会命中同名叶子。
     */
    fun promoteSnackCategory(db: SupportSQLiteDatabase) {
        val foodId = findCategoryId(db, "食品")
        if (foodId != null) {
            db.execSQL(
                "UPDATE categories SET name = '${SNACK_CATEGORY.sql()}', parentId = NULL " +
                    "WHERE name = '零食饮品' AND parentId = $foodId"
            )
        }
        val snackId = ensureProtectedCategory(db, SNACK_CATEGORY, null)
        snackLeaves.forEach { leaf ->
            ensureProtectedCategory(db, leaf, snackId)
        }
    }

    /**
     * v18：葱姜蒜、辣椒只是辅料，不再归属蔬菜。
     * 新增受保护子分类「辅料」，把蔬菜下的「葱姜蒜」改名「蒜」、「辣椒」挪进辅料，
     * 再补种「葱」「姜」；已挂在旧分类上的物品引用不变，自动跟着归到辅料。
     */
    fun separateAuxiliaryCategory(db: SupportSQLiteDatabase) {
        val foodId = findCategoryId(db, "食品") ?: return
        val vegetableId = db.query(
            "SELECT id FROM categories WHERE name = '蔬菜' AND parentId = $foodId LIMIT 1"
        ).useCursor { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
        val auxiliaryId = ensureProtectedCategory(db, AUXILIARY_CATEGORY, foodId)
        if (vegetableId != null) {
            db.execSQL(
                "UPDATE categories SET name = '蒜', parentId = $auxiliaryId, " +
                    "updatedAt = $nowMillisSql WHERE name = '葱姜蒜' AND parentId = $vegetableId"
            )
            db.execSQL(
                "UPDATE categories SET parentId = $auxiliaryId, " +
                    "updatedAt = $nowMillisSql WHERE name = '辣椒' AND parentId = $vegetableId"
            )
        }
        ensureProtectedCategory(db, "葱", auxiliaryId)
        ensureProtectedCategory(db, "姜", auxiliaryId)
        ensureProtectedCategory(db, "蒜", auxiliaryId)
        inheritCategoryIcons(db)
    }

    fun normalizeLegacyData(db: SupportSQLiteDatabase) {
        LegacyTextNormalizer.categoryReplacements.forEach { (legacy, correct) ->
            db.execSQL(
                "UPDATE categories SET name = '${correct.sql()}' WHERE name = '${legacy.sql()}'"
            )
        }
        LegacyTextNormalizer.locationReplacements.forEach { (legacy, correct) ->
            db.execSQL(
                "UPDATE locations SET name = '${correct.sql()}' WHERE name = '${legacy.sql()}'"
            )
        }
        val legacyUnits = LegacyTextNormalizer.legacyUnitNames.joinToString { "'${it.sql()}'" }
        db.execSQL("UPDATE items SET unit = '件' WHERE unit IN ($legacyUnits)")
    }

    /**
     * 种入/回填内置一级分类：缺的按默认图标补种，已存在但图标为空的按默认 key 回填。
     * 供首次建库与 v14→v15 迁移共用。
     */
    fun ensureSeedCategories(db: SupportSQLiteDatabase) {
        defaultCategories.forEach { (name, iconKey) ->
            ensureCategory(db, name, iconKey)
        }
    }

    /** 默认账户（名称 to 图标 key），随记账模块首次使用种入。 */
    private val defaultLedgerAssets = linkedMapOf(
        "微信" to "wechat",
        "支付宝" to "alipay",
        "现金" to "cash",
        "银行卡" to "bank"
    )

    /** 默认支出分类（名称 to 图标 key）。「其他」受保护不可删。 */
    private val defaultLedgerExpenseCategories = linkedMapOf(
        "餐饮" to "restaurant",
        "购物" to "shopping",
        "日用" to "home",
        "交通" to "transport",
        "娱乐" to "entertainment",
        "居住" to "house",
        "医疗" to "medical",
        "其他" to "other"
    )

    /** 默认收入分类（名称 to 图标 key）。「其他」受保护不可删。 */
    private val defaultLedgerIncomeCategories = linkedMapOf(
        "工资" to "salary",
        "红包" to "redpacket",
        "理财" to "invest",
        "其他" to "other"
    )

    /**
     * 记账种子数据：默认账户与支出/收入分类。按「名称 + kind」查重，
     * 已存在时仅回填空图标；供首次建库与 v21→v22 迁移共用。
     */
    fun ensureLedgerSeedData(db: SupportSQLiteDatabase) {
        if (assetCount(db) == 0) {
            var sort = 0
            defaultLedgerAssets.forEach { (name, iconKey) ->
                db.execSQL(
                    "INSERT INTO ledger_assets (name, icon, sort, initialBalance, type, " +
                        "syncId, updatedAt) VALUES " +
                        "('${name.sql()}', '$iconKey', $sort, 0, 0, $newSyncIdSql, $nowMillisSql)"
                )
                sort++
            }
        }
        defaultLedgerExpenseCategories.forEach { (name, iconKey) ->
            ensureLedgerCategory(db, name, iconKey, /* kind = */ 0)
        }
        defaultLedgerIncomeCategories.forEach { (name, iconKey) ->
            ensureLedgerCategory(db, name, iconKey, /* kind = */ 1)
        }
    }

    private fun assetCount(db: SupportSQLiteDatabase): Int {
        return db.query("SELECT COUNT(*) FROM ledger_assets").useCursor { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun ensureLedgerCategory(
        db: SupportSQLiteDatabase,
        name: String,
        iconKey: String,
        kind: Int
    ) {
        val existing = db.query(
            "SELECT id, icon FROM ledger_categories " +
                "WHERE name = '${name.sql()}' AND kind = $kind LIMIT 1"
        ).useCursor { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(0) to cursor.getString(1)
            } else {
                null
            }
        }
        if (existing == null) {
            val isProtected = if (name == "其他") 1 else 0
            db.execSQL(
                "INSERT INTO ledger_categories (name, icon, kind, sort, parentId, isProtected, " +
                    "syncId, updatedAt) VALUES " +
                    "('${name.sql()}', '$iconKey', $kind, 0, NULL, $isProtected, " +
                    "$newSyncIdSql, $nowMillisSql)"
            )
        } else if (existing.second.isEmpty()) {
            db.execSQL(
                "UPDATE ledger_categories SET icon = '$iconKey' WHERE id = ${existing.first}"
            )
        }
    }

    private fun ensureCategory(db: SupportSQLiteDatabase, name: String, iconKey: String) {
        if (findCategoryId(db, name) == null) {
            db.execSQL(
                "INSERT INTO categories (name, icon, syncId, updatedAt) VALUES " +
                    "('${name.sql()}', '$iconKey', $newSyncIdSql, $nowMillisSql)"
            )
        } else {
            db.execSQL(
                "UPDATE categories SET icon = '$iconKey' " +
                    "WHERE name = '${name.sql()}' AND icon = ''"
            )
        }
    }

    /**
     * 按「名称 + 父级」查找或创建受保护分类；已存在时只补上保护位。
     * 返回该分类的 id，便于继续挂子分类。
     */
    private fun ensureProtectedCategory(
        db: SupportSQLiteDatabase,
        name: String,
        parentId: Long?
    ): Long {
        val parentClause = parentId?.let { "parentId = $it" } ?: "parentId IS NULL"
        val existing = db.query(
            "SELECT id FROM categories WHERE name = '${name.sql()}' AND $parentClause LIMIT 1"
        ).useCursor { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
        if (existing != null) {
            db.execSQL("UPDATE categories SET isProtected = 1 WHERE id = $existing")
            return existing
        }
        val parentSql = parentId?.toString() ?: "NULL"
        db.execSQL(
            "INSERT INTO categories (name, icon, parentId, syncId, updatedAt, isProtected) VALUES " +
                "('${name.sql()}', '', $parentSql, $newSyncIdSql, $nowMillisSql, 1)"
        )
        return db.query(
            "SELECT id FROM categories WHERE name = '${name.sql()}' AND $parentClause LIMIT 1"
        ).useCursor { cursor ->
            require(cursor.moveToFirst()) { "受保护分类 $name 插入失败" }
            cursor.getLong(0)
        }
    }

    private fun ensureHomeRoot(db: SupportSQLiteDatabase): Long {
        val canonicalName = LegacyTextNormalizer.HOME_ROOT_NAME
        val legacyNames = LegacyTextNormalizer.aliases(canonicalName)
            .joinToString { "'${it.sql()}'" }

        db.execSQL(
            "UPDATE locations SET name = '${canonicalName.sql()}' " +
                "WHERE parentId IS NULL AND name IN ($legacyNames)"
        )

        val rootIds = mutableListOf<Long>()
        db.query(
            "SELECT id FROM locations " +
                "WHERE parentId IS NULL AND name = '${canonicalName.sql()}' ORDER BY id ASC"
        ).useCursor { cursor ->
            while (cursor.moveToNext()) {
                rootIds += cursor.getLong(0)
            }
        }

        if (rootIds.isEmpty()) {
            db.execSQL(
                "INSERT INTO locations (name, parentId, syncId, updatedAt) VALUES " +
                    "('${canonicalName.sql()}', NULL, $newSyncIdSql, $nowMillisSql)"
            )
            return requireNotNull(findLocationId(db, canonicalName, null))
        }

        val keeperId = rootIds.first()
        rootIds.drop(1).forEach { duplicateId ->
            db.execSQL("UPDATE locations SET parentId = $keeperId WHERE parentId = $duplicateId")
            db.execSQL("UPDATE items SET locationId = $keeperId WHERE locationId = $duplicateId")
            db.execSQL("DELETE FROM locations WHERE id = $duplicateId")
        }

        return keeperId
    }

    private fun ensureLocation(db: SupportSQLiteDatabase, name: String, parentId: Long?): Long {
        findLocationId(db, name, parentId)?.let { return it }

        val parentSql = parentId?.toString() ?: "NULL"
        db.execSQL(
            "INSERT INTO locations (name, parentId, syncId, updatedAt) VALUES " +
                "('${name.sql()}', $parentSql, $newSyncIdSql, $nowMillisSql)"
        )
        return requireNotNull(findLocationId(db, name, parentId))
    }

    private fun findCategoryId(db: SupportSQLiteDatabase, name: String): Long? {
        return db.query(
            "SELECT id FROM categories WHERE name = '${name.sql()}' LIMIT 1"
        ).useCursor { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun findLocationId(
        db: SupportSQLiteDatabase,
        name: String,
        parentId: Long?
    ): Long? {
        val parentClause = parentId?.let { "parentId = $it" } ?: "parentId IS NULL"
        return db.query(
            "SELECT id FROM locations WHERE name = '${name.sql()}' AND $parentClause LIMIT 1"
        ).useCursor { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun String.sql(): String = replace("'", "''")

    /** SQLite 侧生成 32 位十六进制随机串，作为新种子数据的 syncId。 */
    private const val newSyncIdSql = "lower(hex(randomblob(16)))"

    /** SQLite 侧取当前毫秒时间戳。 */
    private const val nowMillisSql = "(CAST(strftime('%s','now') AS INTEGER) * 1000)"
}

private inline fun <T> android.database.Cursor.useCursor(block: (android.database.Cursor) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}
