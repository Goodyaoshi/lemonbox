package com.goodyaoshi.lemonbox.data.settings

import com.goodyaoshi.lemonbox.data.meal.BUILT_IN_RECIPE_MAX_ID
import com.goodyaoshi.lemonbox.data.meal.DEFAULT_RECIPES
import com.goodyaoshi.lemonbox.data.meal.DishRole
import com.goodyaoshi.lemonbox.data.meal.MealDishSpec
import com.goodyaoshi.lemonbox.data.meal.MealSpec
import com.goodyaoshi.lemonbox.data.meal.Recipe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 结构化偏好（菜谱库、周菜单）的「编解码 + 版本迁移」单一来源。
 *
 * 为什么单独抽出来：这些数据需要按版本水位线做迁移（下架内置菜谱、刷新内置定义、增量补种、
 * 菜单搭配规则换代清空），历史上散落在 [AppPreferences] 的读写方法里——既难以单独验证，
 * 又容易在改动时漏掉某条水位线。抽成不依赖 Android 的纯函数后，可以用 JVM 单测直接覆盖每条分支；
 * SharedPreferences 继续作为物理载体承载这些 JSON，本对象只负责「字符串 <-> 数据」与迁移决策。
 */
internal object StructuredPreferenceMigrations {

    /** 结构化偏好共用的编解码器：忽略未知字段，旧版本读到新版本写入的数据也不会崩。 */
    private val json = Json { ignoreUnknownKeys = true }

    // ---- 菜谱库 ----

    /** 内置菜谱下架清理的水位线：每次下架内置菜谱时 +1。 */
    const val RECIPE_CLEANUP_VERSION = 3

    /**
     * 已下架的内置菜谱编号：葱油拌面/土豆丝饼/南瓜饼（主食）、红烧鱼/清蒸鱼/土豆炖牛肉（蛋白）。
     * 另含 42（早期版本的「白粥」草稿，现由 44 取代）。仅按内置编号删除，用户自建菜谱从 100 起，不受影响。
     */
    val REMOVED_BUILT_IN_RECIPE_IDS = setOf(13L, 14L, 17L, 31L, 33L, 34L, 42L)

    /** 内置菜谱定义刷新的水位线：内置菜谱的食材标注等基础规则调整时 +1。 */
    const val RECIPE_REFRESH_VERSION = 1

    /**
     * 升级时用最新定义覆盖的内置菜谱编号（不复活已被用户删除的）：
     * v1 是「葱姜蒜」分类拆成葱/姜/蒜后，同步更新蒜蓉类菜的食材标注。
     */
    val REFRESHED_BUILT_IN_RECIPE_IDS = setOf(3L, 7L, 21L)

    /** 菜谱库读取时看到的原始状态：已存 JSON（null 表示从未写入）与三条水位线读数。 */
    data class RecipeState(
        val storedJson: String?,
        val cleanupVersion: Int,
        val refreshVersion: Int,
        val seedVersion: Int
    )

    /**
     * 菜谱库迁移结果。
     *
     * @param recipes 迁移后的菜谱列表（原有菜谱在前，增量补种的追加在后）
     * @param writeRecipes 是否需要回写菜谱 JSON（内容发生了变化）
     * @param cleanupVersion / refreshVersion / seedVersion 迁移后的目标水位线
     * @param writeWatermarks 是否需要把三条水位线落盘
     */
    data class RecipeResult(
        val recipes: List<Recipe>,
        val writeRecipes: Boolean,
        val cleanupVersion: Int,
        val refreshVersion: Int,
        val seedVersion: Int,
        val writeWatermarks: Boolean
    )

    /**
     * 按水位线迁移菜谱库。三条水位线互相独立，各自只推进一次：
     * 1. 下架清理：删掉已下架的内置菜谱（按编号，用户自建从 100 起不受影响）；
     * 2. 定义刷新：只覆盖仍然存在的内置菜谱，被用户删除或自建的不受影响；
     * 3. 增量补种：只补编号大于已种入水位线的内置菜谱，避免把用户删掉的旧菜谱又加回来。
     *
     * 存储内容损坏（JSON 解析失败）时回退到内置菜谱，但不覆盖已有存储，留待后续写入自愈。
     */
    fun migrateRecipes(state: RecipeState): RecipeResult {
        // 首次启动：种入内置菜谱，并把三条水位线一起推到当前版本（此时数据即最新定义，无需再迁移）。
        if (state.storedJson == null) {
            return RecipeResult(
                recipes = DEFAULT_RECIPES,
                writeRecipes = true,
                cleanupVersion = RECIPE_CLEANUP_VERSION,
                refreshVersion = RECIPE_REFRESH_VERSION,
                seedVersion = BUILT_IN_RECIPE_MAX_ID.toInt(),
                writeWatermarks = true
            )
        }

        // 解析失败：以内存里的内置菜谱兜底，但不动存储，也不推水位线。
        var recipes = runCatching { json.decodeFromString<List<Recipe>>(state.storedJson) }
            .getOrNull()
            ?: return RecipeResult(
                recipes = DEFAULT_RECIPES,
                writeRecipes = false,
                cleanupVersion = state.cleanupVersion,
                refreshVersion = state.refreshVersion,
                seedVersion = state.seedVersion,
                writeWatermarks = false
            )

        var changed = false
        var cleanupVersion = state.cleanupVersion
        var refreshVersion = state.refreshVersion
        var seedVersion = state.seedVersion

        if (cleanupVersion < RECIPE_CLEANUP_VERSION) {
            val filtered = recipes.filterNot { it.id in REMOVED_BUILT_IN_RECIPE_IDS }
            if (filtered.size != recipes.size) {
                recipes = filtered
                changed = true
            }
            cleanupVersion = RECIPE_CLEANUP_VERSION
        }

        if (refreshVersion < RECIPE_REFRESH_VERSION) {
            val refreshedDefs = DEFAULT_RECIPES
                .filter { it.id in REFRESHED_BUILT_IN_RECIPE_IDS }
                .associateBy { it.id }
            val before = recipes
            recipes = recipes.map { refreshedDefs[it.id] ?: it }
            if (recipes != before) changed = true
            refreshVersion = RECIPE_REFRESH_VERSION
        }

        if (seedVersion < BUILT_IN_RECIPE_MAX_ID) {
            val existingIds = recipes.map { it.id }.toSet()
            val added = DEFAULT_RECIPES.filter {
                it.id > seedVersion && it.id !in existingIds
            }
            if (added.isNotEmpty()) {
                recipes = recipes + added
                changed = true
            }
            seedVersion = BUILT_IN_RECIPE_MAX_ID.toInt()
        }

        return RecipeResult(
            recipes = recipes,
            writeRecipes = changed,
            cleanupVersion = cleanupVersion,
            refreshVersion = refreshVersion,
            seedVersion = seedVersion,
            // 任一条水位线被推进才需要落盘，避免每次冷启动都写一遍偏好。
            writeWatermarks = cleanupVersion != state.cleanupVersion ||
                refreshVersion != state.refreshVersion ||
                seedVersion != state.seedVersion
        )
    }

    fun encodeRecipes(recipes: List<Recipe>): String = json.encodeToString(recipes)

    // ---- 周菜单 ----

    /** 菜单默认搭配规则的版本号：规则调整时 +1，会清空旧菜单让它按新规则重排。 */
    const val MEAL_PLAN_VERSION = 1

    /** 周菜单是否需要按新版搭配规则重置（旧规则生成的菜单先清空，已「做了」的记录保留）。 */
    fun shouldResetWeeklyMenu(storedVersion: Int): Boolean = storedVersion < MEAL_PLAN_VERSION

    fun encodeWeeklyMenu(menu: Map<String, MealSpec>): String = json.encodeToString(menu)

    /**
     * 解码周菜单：优先按当前格式（日期 → 一餐搭配），失败时回退兼容旧版
     * 「日期 → 单个菜谱 id」的存储格式（折算成仅含蛋白的一道菜）。两者都解析不了返回空表。
     */
    fun decodeWeeklyMenu(raw: String): Map<String, MealSpec> {
        runCatching { json.decodeFromString<Map<String, MealSpec>>(raw) }
            .getOrNull()
            ?.let { return it }
        return runCatching { json.decodeFromString<Map<String, Long>>(raw) }
            .getOrNull()
            ?.mapValues { (_, id) ->
                MealSpec(listOf(MealDishSpec(DishRole.PROTEIN.name, id)))
            }
            ?: emptyMap()
    }
}
