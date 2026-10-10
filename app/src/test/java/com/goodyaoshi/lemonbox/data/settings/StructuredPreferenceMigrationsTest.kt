package com.goodyaoshi.lemonbox.data.settings

import com.goodyaoshi.lemonbox.data.meal.BUILT_IN_RECIPE_MAX_ID
import com.goodyaoshi.lemonbox.data.meal.DEFAULT_RECIPES
import com.goodyaoshi.lemonbox.data.meal.DishRole
import com.goodyaoshi.lemonbox.data.meal.MealDishSpec
import com.goodyaoshi.lemonbox.data.meal.MealSpec
import com.goodyaoshi.lemonbox.data.meal.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结构化偏好迁移（菜谱水位线、周菜单解码）的回归测试。
 * 这些分支原本内嵌在 AppPreferences 的读写路径里无法单独验证，抽成纯函数后在这里逐条覆盖。
 */
class StructuredPreferenceMigrationsTest {

    /** 走完所有水位线的「已是最新」状态，便于单点验证某一条分支。 */
    private fun upToDateRecipes(recipes: List<Recipe>) = StructuredPreferenceMigrations.RecipeState(
        storedJson = StructuredPreferenceMigrations.encodeRecipes(recipes),
        cleanupVersion = StructuredPreferenceMigrations.RECIPE_CLEANUP_VERSION,
        refreshVersion = StructuredPreferenceMigrations.RECIPE_REFRESH_VERSION,
        seedVersion = BUILT_IN_RECIPE_MAX_ID.toInt()
    )

    @Test
    fun migrateRecipes_firstRunSeedsBuiltInsAndWatermarks() {
        val result = StructuredPreferenceMigrations.migrateRecipes(
            StructuredPreferenceMigrations.RecipeState(
                storedJson = null,
                cleanupVersion = 0,
                refreshVersion = 0,
                seedVersion = 0
            )
        )

        assertEquals(DEFAULT_RECIPES, result.recipes)
        assertTrue(result.writeRecipes)
        assertTrue(result.writeWatermarks)
        assertEquals(StructuredPreferenceMigrations.RECIPE_CLEANUP_VERSION, result.cleanupVersion)
        assertEquals(BUILT_IN_RECIPE_MAX_ID.toInt(), result.seedVersion)
    }

    @Test
    fun migrateRecipes_cleanupRemovesRetiredBuiltInsButKeepsUserRecipes() {
        val retiredId = StructuredPreferenceMigrations.REMOVED_BUILT_IN_RECIPE_IDS.first()
        val userRecipe = Recipe(id = 100L, name = "用户自建菜")
        val stored = listOf(Recipe(id = retiredId, name = "已下架的内置菜"), userRecipe)

        val result = StructuredPreferenceMigrations.migrateRecipes(
            upToDateRecipes(stored).copy(cleanupVersion = 0)
        )

        assertTrue(result.recipes.none { it.id == retiredId })
        assertTrue(result.recipes.contains(userRecipe))
        assertTrue(result.writeRecipes)
        assertEquals(StructuredPreferenceMigrations.RECIPE_CLEANUP_VERSION, result.cleanupVersion)
    }

    @Test
    fun migrateRecipes_cleanupDoesNotRunTwiceWhenWatermarkAdvanced() {
        // 水位线已推进过：即使存储里仍留着下架编号，也不再删（避免与用户手动恢复的菜谱来回打架）。
        val retiredId = StructuredPreferenceMigrations.REMOVED_BUILT_IN_RECIPE_IDS.first()
        val stored = listOf(Recipe(id = retiredId, name = "已下架的内置菜"))

        val result = StructuredPreferenceMigrations.migrateRecipes(upToDateRecipes(stored))

        assertEquals(stored, result.recipes)
        assertFalse(result.writeRecipes)
        assertFalse(result.writeWatermarks)
    }

    @Test
    fun migrateRecipes_refreshOverwritesExistingBuiltInWithoutResurrectingDeleted() {
        val refreshedId = StructuredPreferenceMigrations.REFRESHED_BUILT_IN_RECIPE_IDS.first()
        assertTrue(DEFAULT_RECIPES.any { it.id == refreshedId })
        val officialName = DEFAULT_RECIPES.first { it.id == refreshedId }.name
        val stored = listOf(Recipe(id = refreshedId, name = "过期定义"))

        val result = StructuredPreferenceMigrations.migrateRecipes(
            upToDateRecipes(stored).copy(refreshVersion = 0)
        )

        assertEquals(officialName, result.recipes.first { it.id == refreshedId }.name)
        // 未出现在存储里的内置菜谱（被用户删过）不会被刷新分支复活。
        assertEquals(stored.size, result.recipes.size)
        assertTrue(result.writeRecipes)
    }

    @Test
    fun migrateRecipes_seedAddsOnlyAboveWatermark() {
        val watermark = 10
        val deletedBuiltInId = 5L
        val userRecipe = Recipe(id = 100L, name = "用户自建菜")
        val stored = listOf(userRecipe)

        val result = StructuredPreferenceMigrations.migrateRecipes(
            upToDateRecipes(stored).copy(seedVersion = watermark)
        )

        val resultIds = result.recipes.map { it.id }.toSet()
        // 水位线之上的内置菜谱补入。
        assertTrue(resultIds.containsAll(DEFAULT_RECIPES.map { it.id }.filter { it > watermark }))
        // 水位线之下的内置菜谱（本例中被用户删掉的 5 号）不复活。
        assertFalse(resultIds.contains(deletedBuiltInId))
        // 用户自建菜谱始终保留。
        assertTrue(resultIds.contains(userRecipe.id))
        assertEquals(BUILT_IN_RECIPE_MAX_ID.toInt(), result.seedVersion)
    }

    @Test
    fun migrateRecipes_corruptJsonFallsBackWithoutTouchingStorage() {
        val result = StructuredPreferenceMigrations.migrateRecipes(
            StructuredPreferenceMigrations.RecipeState(
                storedJson = "{ not json",
                cleanupVersion = 0,
                refreshVersion = 0,
                seedVersion = 0
            )
        )

        assertEquals(DEFAULT_RECIPES, result.recipes)
        // 不覆盖已有存储、也不推进水位线，留给后续正常写入自愈。
        assertFalse(result.writeRecipes)
        assertFalse(result.writeWatermarks)
    }

    @Test
    fun weeklyMenu_resetsOnlyWhenVersionOutdated() {
        assertTrue(
            StructuredPreferenceMigrations.shouldResetWeeklyMenu(
                StructuredPreferenceMigrations.MEAL_PLAN_VERSION - 1
            )
        )
        assertFalse(
            StructuredPreferenceMigrations.shouldResetWeeklyMenu(
                StructuredPreferenceMigrations.MEAL_PLAN_VERSION
            )
        )
    }

    @Test
    fun weeklyMenu_roundTripsCurrentFormat() {
        val menu = mapOf(
            "2026-10-10" to MealSpec(
                listOf(
                    MealDishSpec(DishRole.STAPLE.name),
                    MealDishSpec(DishRole.PROTEIN.name, recipeId = 3L)
                )
            )
        )

        val decoded = StructuredPreferenceMigrations.decodeWeeklyMenu(
            StructuredPreferenceMigrations.encodeWeeklyMenu(menu)
        )

        assertEquals(menu, decoded)
    }

    @Test
    fun weeklyMenu_decodesLegacyIdMapAsProteinDish() {
        val decoded = StructuredPreferenceMigrations.decodeWeeklyMenu("""{"2026-10-10":3}""")

        assertEquals(
            mapOf(
                "2026-10-10" to MealSpec(listOf(MealDishSpec(DishRole.PROTEIN.name, 3L)))
            ),
            decoded
        )
    }

    @Test
    fun weeklyMenu_returnsEmptyForUnparsablePayload() {
        assertEquals(emptyMap<String, MealSpec>(), StructuredPreferenceMigrations.decodeWeeklyMenu("oops"))
    }
}
