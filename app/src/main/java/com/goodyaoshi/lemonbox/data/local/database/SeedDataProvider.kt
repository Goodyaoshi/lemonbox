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

    /**
     * 默认房间层级与真实家居布局一致：名称、层级都按实际摆放确定，
     * 避免 App 启动时补种出与云端对不上的节点。
     */
    private val roomHierarchy = linkedMapOf(
        "客厅" to listOf(
            "门后货架", "靠冰箱货架", "靠书桌货架",
            "门后高货架1", "门后高货架2", "镜子下矮货架",
            "时天大行李箱", "时天小行李箱", "柠檬行李箱",
            "书桌", "冰箱", "烘干机", "客厅晾衣架", "鞋架", "脏衣篮"
        ),
        "卧室" to listOf("床", "卧室晾衣架", "收纳箱", "桌子"),
        "厨房" to listOf("台面", "橱柜", "水槽下柜", "置物架", "米箱", "垃圾桶位"),
        "卫生间" to listOf("洗衣机", "内衣洗衣机", "收纳柜", "清洁工具位"),
        "阳台" to listOf("大衣柜", "杂物区", "阳台晾衣架", "阳台晾衣区")
    )

    /** 大衣柜内部的三个分区是唯一需要补种到第三层的位置。 */
    private val roomGrandchildren = mapOf(
        "大衣柜" to listOf("挂衣区", "叠放区", "抽屉")
    )

    /** 客厅里按用途拆出来的分区：服务端把它们的父级改回客厅后，本机要跟上。 */
    private val livingRoomZones = listOf("储物区", "家具区", "行李区", "洗衣区")

    /**
     * 早期版本默认种下的多余房间（书房/储物间）：现行布局是客厅/卧室/厨房/卫生间/阳台，
     * 升级时把这两个房间连同其子位置下线。客厅是真实房间，不在这里清理。
     */
    private val obsoleteRooms = listOf("书房", "储物间")

    /**
     * 下线多余房间及其子位置：用软删除写墓碑（删除可随备份/同步传递）。
     * 只在这些位置整棵子树都没有物品时才处理，避免误删用户已归置的物品。
     */
    fun pruneObsoleteRooms(db: SupportSQLiteDatabase) {
        obsoleteRooms.forEach { room ->
            val roomId = db.query(
                "SELECT id FROM locations WHERE name = '${room.sql()}' " +
                    "AND parentId IS NOT NULL AND deletedAt IS NULL LIMIT 1"
            ).useCursor { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            } ?: return@forEach

            val ids = collectLocationSubtreeIds(db, roomId)
            val inClause = ids.joinToString(",")
            val itemCount = db.query(
                "SELECT COUNT(*) FROM items WHERE locationId IN ($inClause) AND deletedAt IS NULL"
            ).useCursor { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            if (itemCount > 0) return@forEach

            db.execSQL("UPDATE locations SET deletedAt = $nowMillisSql WHERE id IN ($inClause)")
        }
    }

    /** 收集某个位置及其全部下级位置的 id（按 parentId 逐层展开）。 */
    private fun collectLocationSubtreeIds(db: SupportSQLiteDatabase, rootId: Long): List<Long> {
        val ids = mutableListOf<Long>()
        val pending = ArrayDeque<Long>().apply { add(rootId) }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!ids.add(id)) continue
            db.query("SELECT id FROM locations WHERE parentId = $id").useCursor { cursor ->
                while (cursor.moveToNext()) pending.add(cursor.getLong(0))
            }
        }
        return ids
    }

    fun ensureSeedData(db: SupportSQLiteDatabase) {
        ensureSeedCategories(db)

        // 房间本身就是顶级位置：不再套一层「我的家」。
        // 多一层根会让选择器与位置管理每次都先点进一个没有信息的节点，
        // 而「家」这个层级对只有一套房子的场景没有任何区分作用。
        roomHierarchy.forEach { (room, children) ->
            val roomId = ensureLocation(db, room, null)
            children.forEach { child ->
                val childId = ensureLocation(db, child, roomId)
                roomGrandchildren[child]?.forEach { grandChild ->
                    ensureLocation(db, grandChild, childId)
                }
            }
        }
    }

    /**
     * v39：把历史数据里的「我的家」根层级拍平。
     *
     * 旧版把客厅/卧室/厨房/卫生间/阳台都挂在「我的家」下面，选择器与位置管理因此
     * 多出一层空节点。这里把根的子位置提升为顶级，根上的物品解除归属，
     * 再给根写软删除墓碑——删除会随同步传给其它设备，避免它们下次启动又补种出来。
     */
    fun flattenHomeRoots(db: SupportSQLiteDatabase) {
        val rootNames = LegacyTextNormalizer.aliases(LegacyTextNormalizer.HOME_ROOT_NAME)
            .joinToString { "'${it.sql()}'" }
        val rootIds = mutableListOf<Long>()
        db.query(
            "SELECT id FROM locations WHERE parentId IS NULL AND deletedAt IS NULL " +
                "AND name IN ($rootNames)"
        ).useCursor { cursor ->
            while (cursor.moveToNext()) rootIds += cursor.getLong(0)
        }
        if (rootIds.isEmpty()) return

        rootIds.forEach { rootId ->
            db.execSQL(
                "UPDATE locations SET parentId = NULL, updatedAt = $nowMillisSql, dirty = 1 " +
                    "WHERE parentId = $rootId AND deletedAt IS NULL"
            )
            db.execSQL(
                "UPDATE items SET locationId = NULL, updatedAt = $nowMillisSql, dirty = 1 " +
                    "WHERE locationId = $rootId"
            )
            db.execSQL(
                "UPDATE locations SET deletedAt = $nowMillisSql, updatedAt = $nowMillisSql, dirty = 1 " +
                    "WHERE id = $rootId"
            )
        }
    }

    /**
     * v40：把「大衣柜」的三个分区重新挂回衣柜。
     *
     * 旧版拉取合并遇到解析不到的父级引用时会把 parentId 写成 NULL；v39 拍平「我的家」后，
     * 抽屉 / 挂衣区 / 叠放区 就与五个房间一起出现在一级目录。这三层的父子关系在种子结构里
     * 唯一确定（抽屉等 → 大衣柜 → 阳台），按名字补回即可；写 updatedAt + dirty，
     * 让修正随下一轮同步传到其它设备，不再被服务端的旧父级覆盖。
     */
    fun relinkWardrobeSections(db: SupportSQLiteDatabase) {
        val wardrobeId = queryLocationId(
            db,
            "SELECT l.id FROM locations l WHERE l.name = '大衣柜' AND l.deletedAt IS NULL " +
                "AND l.parentId IN (SELECT id FROM locations WHERE name = '阳台' " +
                "AND parentId IS NULL AND deletedAt IS NULL) ORDER BY l.id LIMIT 1"
        ) ?: queryLocationId(
            db,
            "SELECT id FROM locations WHERE name = '大衣柜' AND deletedAt IS NULL ORDER BY id LIMIT 1"
        ) ?: return

        // 衣柜自己若被冲成顶级，先挂回阳台（拍平后阳台才是顶级房间）。
        queryLocationId(
            db,
            "SELECT id FROM locations WHERE name = '阳台' AND parentId IS NULL " +
                "AND deletedAt IS NULL ORDER BY id LIMIT 1"
        )?.let { balconyId ->
            db.execSQL(
                "UPDATE locations SET parentId = ?, updatedAt = $nowMillisSql, dirty = 1 " +
                    "WHERE name = '大衣柜' AND parentId IS NULL AND deletedAt IS NULL",
                arrayOf<Any>(balconyId)
            )
        }

        roomGrandchildren["大衣柜"].orEmpty().forEach { section ->
            db.execSQL(
                "UPDATE locations SET parentId = ?, updatedAt = $nowMillisSql, dirty = 1 " +
                    "WHERE name = ? AND parentId IS NULL AND deletedAt IS NULL AND id <> ?",
                arrayOf<Any>(wardrobeId, section, wardrobeId)
            )
        }
    }

    /**
     * v41：把客厅按用途拆出的四个分区重新挂回客厅。
     *
     * 拉取合并原先只在时间戳允许时才改父级，服务端把 储物区 / 家具区 / 行李区 / 洗衣区
     * 移到客厅下之后，本机这几行仍是顶级，管理页一级目录就多出四个分组。
     * 这里按名字补回（只动仍是顶级且未删的行），写 updatedAt + dirty，
     * 让修正随后续同步收敛，也避免被服务端的旧父级再冲回顶级。
     */
    fun relinkLivingRoomZones(db: SupportSQLiteDatabase) {
        val livingRoomId = queryLocationId(
            db,
            "SELECT id FROM locations WHERE name = '客厅' AND parentId IS NULL " +
                "AND deletedAt IS NULL ORDER BY id LIMIT 1"
        ) ?: return
        livingRoomZones.forEach { zone ->
            db.execSQL(
                "UPDATE locations SET parentId = ?, updatedAt = $nowMillisSql, dirty = 1 " +
                    "WHERE name = ? AND parentId IS NULL AND deletedAt IS NULL AND isProtected = 0",
                arrayOf<Any>(livingRoomId, zone)
            )
        }
    }

    private fun queryLocationId(db: SupportSQLiteDatabase, sql: String): Long? =
        db.query(sql).useCursor { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    /**
     * 受保护的基础数据（依赖 isProtected 列）。
     * 补种「食品 → 子分类 → 具体分类」三级结构并把整棵树标为受保护，
     * 同时把「冰箱」位置标为受保护。
     *
     * 该方法设计为**可重复执行**：既在 v14+ 的建库/迁移里调用，也在每次
     * 备份导入 / 局域网同步合并后调用一次，确保内置食材分类在任何情况下
     * 丢失（被同步改写、被误删）都能自动补齐，而不依赖远端是否还带着它们。
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
        // 「冰箱」是「今天吃什么」的取材位置。同名节点在多设备合并/旧数据里可能不止一个，
        // 若一律保护，合并策略会跳过它们的远端墓碑，被删掉的冰箱会一轮轮复活。
        // 只认一个：优先客厅里的那个，其次最早创建的那个；其余的解除保护，让删除能落地。
        val canonicalFridgeId = canonicalFridgeId(db)
        if (canonicalFridgeId != null) {
            db.execSQL(
                "UPDATE locations SET isProtected = 1, deletedAt = NULL WHERE id = ?",
                arrayOf<Any>(canonicalFridgeId)
            )
            db.execSQL(
                "UPDATE locations SET isProtected = 0 " +
                    "WHERE name = '${MEAL_LOCATION.sql()}' AND id <> ? AND deletedAt IS NULL",
                arrayOf<Any>(canonicalFridgeId)
            )
        }
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
     * 唯一受保护冰箱的本地 id：优先客厅里的那个，没有时退化为最早创建的一个。
     *
     * 返回 null 表示库里一个活着的冰箱都没有（此时不写入保护位，等默认层级补种后再来一次）。
     */
    private fun canonicalFridgeId(db: SupportSQLiteDatabase): Long? {
        val inLivingRoom = db.query(
            """
            SELECT l.id FROM locations l JOIN locations p ON l.parentId = p.id
            WHERE l.name = '${MEAL_LOCATION.sql()}' AND p.name = '客厅' AND l.deletedAt IS NULL
            ORDER BY l.id LIMIT 1
            """.trimIndent()
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        if (inLivingRoom != null) return inLivingRoom
        return db.query(
            "SELECT MIN(id) FROM locations " +
                "WHERE name = '${MEAL_LOCATION.sql()}' AND deletedAt IS NULL"
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
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
     * 按「名称 + 父级」查找或创建受保护分类；已存在时补上保护位并清除墓碑。
     * 返回该分类的 id，便于继续挂子分类。
     *
     * 该方法必须可重复执行（同步/导入后无条件补种一次），否则内置食材分类一旦被
     * 同步合并改写或软删就再也回不来：
     *  - 精确命中（同名同父级）：补保护位 + 清墓碑（复活被误删的内置分类）；
     *  - 未精确命中但存在同名「孤立」分类（同步时先以一级分类落库、父级还没修正）：
     *    重新挂回正确父级，避免留下顶级孤儿的「番茄」；
     *  - 都找不到才插入新记录兜底。
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
            db.execSQL(
                "UPDATE categories SET isProtected = 1, deletedAt = NULL WHERE id = $existing"
            )
            return existing
        }
        // 同步合并时缺失的分类会先以一级分类（parentId = NULL）落库，随后才修正父级；
        // 此处按名称找这类孤立分类重新挂回正确父级。排除 parentId 自身，防止自环。
        if (parentId != null) {
            val orphanId = db.query(
                "SELECT id FROM categories WHERE name = '${name.sql()}' " +
                    "AND parentId IS NULL AND id <> $parentId LIMIT 1"
            ).useCursor { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
            if (orphanId != null) {
                db.execSQL(
                    "UPDATE categories SET parentId = $parentId, isProtected = 1, " +
                        "deletedAt = NULL, updatedAt = $nowMillisSql WHERE id = $orphanId"
                )
                return orphanId
            }
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
