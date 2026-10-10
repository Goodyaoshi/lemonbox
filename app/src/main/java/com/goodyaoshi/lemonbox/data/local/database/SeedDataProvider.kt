package com.goodyaoshi.lemonbox.data.local.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.goodyaoshi.lemonbox.util.LegacyTextNormalizer

/**
 * 建库与迁移共用的种子数据 / 历史数据归一化逻辑（F9）。
 *
 * 这些方法原本内嵌在 `AppDatabase.kt`，与数据库装配混在一起，
 * 单文件因此膨胀到千行量级。种子数据只关心「首次建库 / 升级时要补什么」，
 * 与装配职责正交：独立成文件后便于单独审阅，也能脱离 Room 单独测试。
 */
internal object SeedDataProvider {
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
