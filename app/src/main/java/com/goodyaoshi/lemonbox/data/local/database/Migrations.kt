package com.goodyaoshi.lemonbox.data.local.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 集中承载全部数据库迁移（F9）。
 *
 * 拆分前这些迁移对象与建库装配、DAO 声明同处 `AppDatabase.kt`，
 * 单文件因此膨胀到千行量级。迁移只与「版本 + 表结构」有关，
 * 与数据库的装配职责正交：独立成文件后既能按版本顺序通读，
 * 也便于单独审阅「是否有丢列 / 丢数据的风险」。
 */
internal object Migrations {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN rating INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN ratedAt INTEGER")
                SeedDataProvider.normalizeLegacyData(db)
                SeedDataProvider.ensureSeedData(db)
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN deletedAt INTEGER")
                SeedDataProvider.normalizeLegacyData(db)
                SeedDataProvider.ensureSeedData(db)
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
                SeedDataProvider.normalizeLegacyData(db)
                SeedDataProvider.ensureSeedData(db)
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedDataProvider.normalizeLegacyData(db)
                SeedDataProvider.ensureSeedData(db)
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
                SeedDataProvider.ensureProtectedSeedData(db)
            }
        }

        /**
         * 内置一级分类图标差异化 + 新增「化妆品」：
         * 老安装按名称回填默认图标，并补种缺失的内置分类（含化妆品）。
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedDataProvider.ensureSeedCategories(db)
            }
        }

        /**
         * 新增「厨具」「家电」，并把食品下的「零食饮品」升级为同级一级分类「零食」。
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedDataProvider.promoteSnackCategory(db)
                SeedDataProvider.ensureSeedCategories(db)
            }
        }

        /**
         * 家当卡片展示分类图标：叶子分类的 icon 为空，沿分类树继承父级图标；
         * 同时为物品补上提醒阶梯字段（空 = 跟随全局默认阶梯）。
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN reminderDays TEXT")
                SeedDataProvider.inheritCategoryIcons(db)
            }
        }

        /**
         * 葱姜蒜、辣椒只是辅料：新增受保护子分类「辅料」（葱/姜/蒜/辣椒），
         * 把蔬菜下的「葱姜蒜」改名「蒜」、「辣椒」挪入辅料，物品引用自动跟随。
         */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedDataProvider.separateAuxiliaryCategory(db)
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
                SeedDataProvider.ensureLedgerSeedData(db)
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

        /**
         * 待买项新增「给谁的」可选归属列：存码值（self/partner/shared），
         * null 表示未标注。历史数据一律为 null，界面不展示标签。
         */
        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `items` ADD COLUMN `forWhom` TEXT")
            }
        }

        /**
         * 家居布局收敛为「厨房 / 卧室 / 卫生间 / 阳台」四间：
         * 早期版本默认种下的客厅 / 书房 / 储物间（及其子位置）在整棵子树内
         * 没有物品时下线（软删除写墓碑，删除可随备份/同步传递）；
         * 若用户已往里归置过物品则保留，避免误删。
         */
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedDataProvider.pruneObsoleteRooms(db)
            }
        }

        /**
         * 物品新增「生产日期」：只加列不回填，历史记录保持 null（未记录）。
         */
        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `items` ADD COLUMN `productionDate` INTEGER")
            }
        }

        /**
         * 周期记账 + 记账模板（F05）：新增模板表与周期规则表。
         * 模板把常用记账参数存下来供一键套用；周期规则到点由调度任务生成账单。
         */
        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ledger_templates` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` INTEGER NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `categoryId` INTEGER,
                        `assetId` INTEGER,
                        `targetAssetId` INTEGER,
                        `remark` TEXT NOT NULL,
                        `sort` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `syncId` TEXT,
                        `updatedAt` INTEGER,
                        `deletedAt` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ledger_recurring_rules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` INTEGER NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `categoryId` INTEGER,
                        `assetId` INTEGER,
                        `targetAssetId` INTEGER,
                        `remark` TEXT NOT NULL,
                        `repeatUnit` TEXT NOT NULL,
                        `repeatInterval` INTEGER NOT NULL,
                        `nextFireAt` INTEGER NOT NULL,
                        `lastFiredAt` INTEGER,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `syncId` TEXT,
                        `updatedAt` INTEGER,
                        `deletedAt` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ledger_recurring_rules_nextFireAt` " +
                        "ON `ledger_recurring_rules` (`nextFireAt`)"
                )
            }
        }

        /**
         * 时间胶囊（F14）+ 旅行打包清单（F16）：新增三张表。
         * 时间胶囊存写给未来的话与解锁时间；打包清单分行与条目两级。
         */
        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `time_capsules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `unlockAt` INTEGER NOT NULL,
                        `openedAt` INTEGER,
                        `forWhom` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `syncId` TEXT,
                        `updatedAt` INTEGER,
                        `deletedAt` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `packing_trips` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `destination` TEXT NOT NULL,
                        `startDate` TEXT,
                        `endDate` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `syncId` TEXT,
                        `updatedAt` INTEGER,
                        `deletedAt` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `packing_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `tripId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `quantity` INTEGER NOT NULL,
                        `category` TEXT NOT NULL,
                        `checked` INTEGER NOT NULL,
                        `sort` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `syncId` TEXT,
                        `updatedAt` INTEGER,
                        `deletedAt` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_packing_items_tripId` " +
                        "ON `packing_items` (`tripId`)"
                )
            }
        }

    /** v32（F17）：reminders 增加按疗程用药字段——每日多次服药时刻、疗程起止、关联库存物品。 */
    private val MIGRATION_31_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `reminders` ADD COLUMN `doseTimes` TEXT NOT NULL DEFAULT ''"
            )
            db.execSQL(
                "ALTER TABLE `reminders` ADD COLUMN `courseStartDate` TEXT"
            )
            db.execSQL(
                "ALTER TABLE `reminders` ADD COLUMN `courseDays` INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE `reminders` ADD COLUMN `linkedItemId` INTEGER"
            )
        }
    }

    /**
     * v33（F20）：items 增加云端对象键列——与 imagePaths 按下标一一对应，
     * 让「这张图在云端对应哪个对象」有据可查，本地文件缺失时才能从云端取回。
     *
     * 保持可空、且不加 DEFAULT：语义上「还没上传」就是「没有值」，
     * 也让本迁移的 ADD COLUMN 与实体注解天然一致（不依赖 DEFAULT 字面量的写法）。
     */
    private val MIGRATION_32_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `items` ADD COLUMN `imageObjectKeys` TEXT")
        }
    }

    /**
     * v34（F21）：items 增加 `purgedAt` 列——永久删除改为写墓碑，删除才能同步到其他设备。
     *
     * 可空、无 DEFAULT：语义上「不是永久删除墓碑」就是「没有值」，也与实体的
     * `val purgedAt: Long? = null` 天然一致（不依赖 DEFAULT 字面量写法）。
     */
    private val MIGRATION_33_34 = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `items` ADD COLUMN `purgedAt` INTEGER")
        }
    }

    /**
     * v35（F22）：图片槽位由「本地路径 + 对象键」三列合并为一列 `imageHashes`。
     *
     * 为什么能合并：本地文件名（`item_<hash>.jpg`）与云端对象键（`[prefix/]images/<hash>.jpg`）
     * 都由同一个内容哈希推导，三列本就承载同一份信息。合并后不再需要「路径与键按下标对齐」
     * 这套约定，哨兵占位机制也一并消失。
     *
     * 为什么走「建新表 → 换名」而不是 `DROP COLUMN`：`DROP COLUMN` 需要 SQLite 3.35+，
     * 而本项目 minSdk 26，设备自带 SQLite 版本远低于此。
     *
     * 转换规则（纯字符串，不读文件，保证迁移同步完成）：
     *  1. 对象键末段若是 `<hash>.jpg` → 取该哈希（已上传的槽位最可靠）；
     *  2. 否则取本地文件名的 `item_<...>.jpg` 中间段，是 64 位十六进制就用它；
     *  3. 都不是（旧 UUID 命名的文件）→ 保留文件名本身作为槽位标识，**绝不丢槽位**，
     *     由后续同步算出真实哈希后回写（见 ImageCloudSyncManager 的回写路径）。
     */
    private val MIGRATION_34_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 先读旧值：DROP TABLE 之后再也拿不到，必须在这一步把槽位算完。
            val slotsById = mutableMapOf<Long, String?>()
            db.query("SELECT `id`, `imagePaths`, `imageObjectKeys` FROM `items`").use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val paths = (if (cursor.isNull(1)) "" else cursor.getString(1)).orEmpty()
                    val keys = (if (cursor.isNull(2)) "" else cursor.getString(2)).orEmpty()
                    slotsById[id] = mergeImageSlots(paths, keys)
                }
            }

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `items_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `barcode` TEXT,
                    `categoryId` INTEGER,
                    `locationId` INTEGER,
                    `quantity` INTEGER NOT NULL,
                    `consumedQuantity` INTEGER NOT NULL DEFAULT 0,
                    `unit` TEXT NOT NULL,
                    `price` REAL,
                    `expireTime` INTEGER,
                    `usageStatus` INTEGER NOT NULL,
                    `disposition` INTEGER NOT NULL,
                    `needRestock` INTEGER NOT NULL,
                    `forWhom` TEXT,
                    `reminderDays` TEXT,
                    `purchaseDate` INTEGER,
                    `productionDate` INTEGER,
                    `startUseTime` INTEGER,
                    `usageEndedAt` INTEGER,
                    `trackMode` INTEGER NOT NULL DEFAULT 0,
                    `rating` INTEGER,
                    `ratedAt` INTEGER,
                    `deletedAt` INTEGER,
                    `purgedAt` INTEGER,
                    `note` TEXT NOT NULL,
                    `imageHashes` TEXT,
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
                    `id`, `name`, `barcode`, `categoryId`, `locationId`, `quantity`,
                    `consumedQuantity`, `unit`, `price`, `expireTime`, `usageStatus`,
                    `disposition`, `needRestock`, `forWhom`, `reminderDays`, `purchaseDate`,
                    `productionDate`, `startUseTime`, `usageEndedAt`, `trackMode`, `rating`,
                    `ratedAt`, `deletedAt`, `purgedAt`, `note`, `createdAt`, `syncId`, `updatedAt`
                )
                SELECT
                    `id`, `name`, `barcode`, `categoryId`, `locationId`, `quantity`,
                    `consumedQuantity`, `unit`, `price`, `expireTime`, `usageStatus`,
                    `disposition`, `needRestock`, `forWhom`, `reminderDays`, `purchaseDate`,
                    `productionDate`, `startUseTime`, `usageEndedAt`, `trackMode`, `rating`,
                    `ratedAt`, `deletedAt`, `purgedAt`, `note`, `createdAt`, `syncId`, `updatedAt`
                FROM `items`
                """.trimIndent()
            )
            // 逐行写回算好的槽位（列少、行数就是物品数，代价可忽略）。
            slotsById.forEach { (id, slots) ->
                db.execSQL(
                    "UPDATE `items_new` SET `imageHashes` = ? WHERE `id` = ?",
                    arrayOf(slots, id)
                )
            }
            db.execSQL("DROP TABLE `items`")
            db.execSQL("ALTER TABLE `items_new` RENAME TO `items`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_items_categoryId` ON `items` (`categoryId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_items_locationId` ON `items` (`locationId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_items_usageStatus` ON `items` (`usageStatus`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_items_disposition` ON `items` (`disposition`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_items_barcode` ON `items` (`barcode`)"
            )
        }
    }

    /**
     * v36（F21）：给 `reminders` 加「永久删除」墓碑列，与物品同款（物品在 v34 已有）。
     * 旧行 purgedAt 为 NULL，语义正是「不是永久删除」，无需回填。
     */
    private val MIGRATION_35_36 = object : Migration(35, 36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `reminders` ADD COLUMN `purgedAt` INTEGER")
        }
    }

/**
 * 把 v34 的「本地路径行 + 对象键行」合并成 v35 的单列哈希（F22）。
 *
 * 逐行按下标配对——下标是两者唯一的关联方式，任何一侧缺失都要保留另一侧的信息，
 * **绝不能因为某一行取不出哈希就把这个槽位丢掉**，否则迁移后主图会漂移。
 *
 * 三级取法（越靠前越可靠）：
 *  1. 对象键末段形如 `<64位哈希>.jpg`；
 *  2. 本地路径的文件名形如 `item_<64位哈希>.jpg`；
 *  3. 都不匹配（旧 UUID 命名）→ 退回文件名本身当槽位标识，等同步算出真实哈希再回写。
 */
private fun mergeImageSlots(pathsEncoded: String, keysEncoded: String): String? {
    val paths = pathsEncoded.lineSequence().map { it.trim() }.toList()
    val keys = keysEncoded.lineSequence().map { it.trim() }.toList()
    val count = maxOf(paths.size, keys.size)
    val hashes = (0 until count).mapNotNull { index ->
        val key = keys.getOrNull(index).orEmpty()
        val path = paths.getOrNull(index).orEmpty()
        hashFromObjectKey(key)
            ?: hashFromItemFileName(path)
            ?: path.substringAfterLast('/').takeIf { it.isNotBlank() }
    }
    return hashes.joinToString("\n").ifBlank { null }
}

/** 取对象键末段的 64 位十六进制哈希；不为该格式时返回 null。 */
private fun hashFromObjectKey(objectKey: String): String? {
    if (objectKey.isBlank()) return null
    val name = objectKey.substringAfterLast('/')
    val stem = name.substringBeforeLast('.')
    return stem.takeIf { it.length == SHA256_HEX_LENGTH && it.isSha256Hex() }
}

/** 取本地文件名 `item_<hash>.jpg` 里的哈希；不是该格式时返回 null。 */
private fun hashFromItemFileName(path: String): String? {
    val name = path.substringAfterLast('/')
    if (!name.startsWith(ITEM_FILE_PREFIX) || !name.endsWith(ITEM_FILE_SUFFIX)) return null
    val hash = name.substring(ITEM_FILE_PREFIX.length, name.length - ITEM_FILE_SUFFIX.length)
    return hash.takeIf { it.length == SHA256_HEX_LENGTH && it.isSha256Hex() }
}

private fun String.isSha256Hex(): Boolean = all { it.isDigit() || it in 'a'..'f' }

/** 与 [com.goodyaoshi.lemonbox.util.ImageUtil] 的落盘命名保持一致。 */
private const val ITEM_FILE_PREFIX = "item_"

private const val ITEM_FILE_SUFFIX = ".jpg"

private const val SHA256_HEX_LENGTH = 64

/** 按版本顺序排列的全部迁移，供 [AppDatabase] 一次性装配。 */
    val ALL: Array<Migration> = arrayOf(
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
        MIGRATION_25_26,
        MIGRATION_26_27,
        MIGRATION_27_28,
        MIGRATION_28_29,
        MIGRATION_29_30,
        MIGRATION_30_31,
        MIGRATION_31_32,
        MIGRATION_32_33,
        MIGRATION_33_34,
        MIGRATION_34_35,
        MIGRATION_35_36
    )
}
