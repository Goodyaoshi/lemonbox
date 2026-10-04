package com.goodyaoshi.lemonbox.data.meal

import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealSuggesterTest {

    private val now = 1_700_000_000_000L

    @Test
    fun suggest_fallsBackToRandomWhenPoolIsEmpty() {
        // 家当里没有可吃的食材：「今天吃什么」依然随机推菜谱，不为空。
        val result = MealSuggester.suggest(DEFAULT_RECIPES, emptyList(), now = now, random = Random(1))

        assertEquals(3, result.size)
        assertTrue(result.all { it.available.isEmpty() && it.missing.isNotEmpty() })
        // 换一批能换出不同的组合。
        val rerolled = MealSuggester.suggest(DEFAULT_RECIPES, emptyList(), now = now, random = Random(7))
        assertTrue(result.map { it.name } != rerolled.map { it.name })
    }

    @Test
    fun suggest_completesRecipeWhenAllIngredientsPresent() {
        val pool = listOf(
            itemDetail(id = 1L, name = "番茄"),
            itemDetail(id = 2L, name = "鸡蛋")
        )

        val result = MealSuggester.suggest(DEFAULT_RECIPES, pool, now = now, random = Random(1))

        val tomatoEgg = result.firstOrNull { it.name == "番茄炒蛋" }
        assertTrue(tomatoEgg != null)
        assertEquals(emptyList<String>(), tomatoEgg!!.missing)
        assertEquals(listOf("番茄", "鸡蛋"), tomatoEgg.available.map { it.first })
    }

    @Test
    fun suggest_fallsBackToPartialMatchBeforeRandom() {
        // 家里只有蒜：蒜蓉青菜 / 清炒时蔬只对上可选的「蒜」，也算「部分匹配」，排在随机兜底之前。
        val pool = listOf(
            itemDetail(id = 1L, name = "蒜")
        )

        val result = MealSuggester.suggest(
            DEFAULT_RECIPES,
            pool,
            now = now,
            random = Random(3),
            limit = 4
        )

        assertEquals(4, result.size)
        // 前三条是部分匹配（用上了可选的蒜，但仍缺主料），最后一条是完全没命中的随机兜底。
        assertTrue(result.take(3).all { it.available.any { pair -> pair.first == "蒜" } })
        assertTrue(result.take(3).all { it.missing.isNotEmpty() })
        assertTrue(result.last().available.isEmpty())
    }

    @Test
    fun suggest_fallsBackToRandomWhenNothingMatches() {
        // 家里只有饮料：谁也对不上，但「今天吃什么」不应该空，随机推几道菜谱。
        val pool = listOf(
            itemDetail(id = 1L, name = "可乐"),
            itemDetail(id = 2L, name = "啤酒"),
            itemDetail(id = 3L, name = "气泡水")
        )

        val result = MealSuggester.suggest(DEFAULT_RECIPES, pool, now = now, random = Random(3))

        assertEquals(3, result.size)
        assertTrue(result.all { it.available.isEmpty() && it.missing.isNotEmpty() })
    }

    @Test
    fun suggest_pinsCookedRecipesOnReroll() {
        // 今天做过的菜：即使食材已经用掉，换一批也钉在推荐最前。
        val pool = listOf(itemDetail(id = 1L, name = "可乐"))

        val result = MealSuggester.suggest(
            DEFAULT_RECIPES,
            pool,
            pinned = setOf("番茄炒蛋"),
            now = now,
            random = Random(3)
        )

        assertEquals(3, result.size)
        assertEquals("番茄炒蛋", result.first().name)
    }

    @Test
    fun suggest_reportsMissingIngredients() {
        val pool = listOf(itemDetail(id = 1L, name = "番茄"))

        val result = MealSuggester.suggest(DEFAULT_RECIPES, pool, now = now, random = Random(1))

        val tomatoEgg = result.first { it.name == "番茄炒蛋" }
        assertEquals(listOf("鸡蛋"), tomatoEgg.missing)
        assertEquals(listOf("番茄"), tomatoEgg.available.map { it.first })
    }

    @Test
    fun suggest_ranksCompleteRecipesFirst() {
        val pool = listOf(
            itemDetail(id = 1L, name = "番茄"),
            itemDetail(id = 2L, name = "鸡蛋"),
            itemDetail(id = 3L, name = "排骨")
        )

        val result = MealSuggester.suggest(DEFAULT_RECIPES, pool, now = now, random = Random(9))

        // 食材齐全的菜谱排在前面（可能不止一道），番茄炒蛋一定在齐活之列。
        assertTrue(result.first().missing.isEmpty())
        assertTrue(result.any { it.name == "番茄炒蛋" && it.missing.isEmpty() })
    }

    @Test
    fun suggest_onlyReturnsItemsFromGivenPool() {
        val pool = listOf(
            itemDetail(id = 1L, name = "土豆"),
            itemDetail(id = 2L, name = "牛肉")
        )

        val result = MealSuggester.suggest(DEFAULT_RECIPES, pool, now = now, random = Random(3))

        assertTrue(
            result.flatMap { it.available }.all { (_, detail) ->
                pool.any { it.item.id == detail.item.id }
            }
        )
    }

    @Test
    fun suggest_deduplicatesItemsWithSameName() {
        val pool = listOf(
            itemDetail(id = 1L, name = "牛奶"),
            itemDetail(id = 2L, name = "牛奶"),
            itemDetail(id = 3L, name = "燕麦")
        )

        val result = MealSuggester.suggest(DEFAULT_RECIPES, pool, now = now, random = Random(1))

        // 同名的重复物品只会保留一份，因此 id=2 的那份牛奶不会被任何菜谱用到。
        val usedIds = result.flatMap { it.available }.map { it.second.item.id }
        assertFalse(usedIds.contains(2L))
        // 单条推荐内部不会重复使用同一件物品。
        result.forEach { suggestion ->
            val ids = suggestion.available.map { it.second.item.id }
            assertEquals(ids.distinct().size, ids.size)
        }
    }

    @Test
    fun suggest_flagsExpiringWhenMatchedItemIsNearExpiry() {
        val fresh = listOf(
            itemDetail(id = 1L, name = "番茄"),
            itemDetail(id = 2L, name = "鸡蛋")
        )
        val expiring = listOf(
            itemDetail(id = 1L, name = "番茄", expireTime = now + 12 * 60 * 60 * 1000L),
            itemDetail(id = 2L, name = "鸡蛋")
        )

        val freshSuggestion = MealSuggester
            .suggest(DEFAULT_RECIPES, fresh, now = now, random = Random(1))
            .first { it.name == "番茄炒蛋" }
        val expiringSuggestion = MealSuggester
            .suggest(DEFAULT_RECIPES, expiring, now = now, random = Random(1))
            .first { it.name == "番茄炒蛋" }

        assertFalse(freshSuggestion.expiring)
        assertTrue(expiringSuggestion.expiring)
    }

    @Test
    fun rankRecipes_prioritizesEarliestExpiryOverMatchCount() {
        val day = 24L * 60 * 60 * 1000
        // 可乐鸡翅只用一样食材，但鸡翅明天就过期；番茄炒蛋命中更多却都不急——
        // 按最早过期日期精确排序，先做快过期的，而不是命中更多的。
        val pool = listOf(
            itemDetail(id = 1L, name = "鸡翅", expireTime = now + day),
            itemDetail(id = 2L, name = "番茄", expireTime = now + 10 * day),
            itemDetail(id = 3L, name = "鸡蛋", expireTime = now + 10 * day)
        )

        val ranked = MealSuggester.rankRecipes(DEFAULT_RECIPES, pool, now = now, random = Random(1))

        assertEquals("可乐鸡翅", ranked.first().name)
        // 最早过期为空（没填有效期）的菜排在同层最后。
        val firstExpiry = ranked.first().let {
            MealSuggester.evaluateRecipe(it, pool, now = now).earliestExpiry
        }
        assertTrue(ranked.drop(1).all {
            val expiry = MealSuggester.evaluateRecipe(it, pool, now = now).earliestExpiry
            expiry == null || expiry >= (firstExpiry ?: Long.MAX_VALUE)
        })
    }

    @Test
    fun suggest_respectsLimit() {
        val pool = listOf(
            itemDetail(id = 1L, name = "番茄"),
            itemDetail(id = 2L, name = "鸡蛋"),
            itemDetail(id = 3L, name = "黄瓜"),
            itemDetail(id = 4L, name = "木耳"),
            itemDetail(id = 5L, name = "土豆"),
            itemDetail(id = 6L, name = "牛肉")
        )

        val result = MealSuggester.suggest(DEFAULT_RECIPES, pool, now = now, random = Random(1), limit = 2)

        assertEquals(2, result.size)
    }

    @Test
    fun suggest_matchesByCategoryName() {
        val pool = listOf(
            ItemDetail(
                item = Item(id = 1L, name = "神秘食材", createdAt = 1L),
                categoryName = "番茄"
            ),
            itemDetail(id = 2L, name = "鸡蛋")
        )

        val result = MealSuggester.suggest(DEFAULT_RECIPES, pool, now = now, random = Random(1))

        assertTrue(result.any { it.name == "番茄炒蛋" && it.missing.isEmpty() })
    }

    @Test
    fun suggest_matchesByAncestorCategoryChain() {
        // 物品挂在「蔬菜 → 番茄」下：食材「番茄」通过分类链命中，不要求物品名字里带“番茄”。
        val pool = listOf(
            ItemDetail(
                item = Item(id = 1L, name = "胭脂红鲜果", categoryId = 9L, createdAt = 1L)
            ),
            itemDetail(id = 2L, name = "鸡蛋")
        )

        val result = MealSuggester.suggest(
            DEFAULT_RECIPES,
            pool,
            categoryTexts = mapOf(9L to "食品 蔬菜 番茄"),
            now = now,
            random = Random(1)
        )

        val tomatoEgg = result.firstOrNull { it.name == "番茄炒蛋" }
        assertTrue(tomatoEgg != null)
        assertTrue(tomatoEgg!!.available.any { it.second.item.id == 1L })
    }

    @Test
    fun dishRoleClassifier_classifiesPurelyByName() {
        // 一道菜可能同时命中多个角色（「鸡蛋面」既是主食也有蛋白）。
        assertTrue(DishRole.PROTEIN in DishRoleClassifier.byName("青椒炒肉"))
        assertTrue(DishRole.STAPLE in DishRoleClassifier.byName("鸡蛋面"))
        assertTrue(DishRole.PROTEIN in DishRoleClassifier.byName("鸡蛋面"))
        assertTrue(DishRole.SOUP in DishRoleClassifier.byName("紫菜蛋花汤"))
        // 「豆角」出现在清炒时蔬的食材里，但角色只看菜名，仍归为蔬菜而不是蛋白。
        assertEquals(setOf(DishRole.VEGETABLE), DishRoleClassifier.byName("清炒时蔬"))
    }

    @Test
    fun recipe_explicitRoleOverridesNameHeuristic() {
        // 按菜名「汤」会判成汤饮，但显式角色优先，可被用户/内置数据纠正。
        val recipe = Recipe(id = 1L, name = "豆腐汤", role = DishRole.PROTEIN.name)
        assertEquals(setOf(DishRole.PROTEIN), recipe.dishRoles)

        // 多个角色用逗号串持久化：饺子一道就覆盖主食 / 蛋白 / 蔬菜。
        val multi = Recipe(
            id = 2L,
            name = "猪肉白菜饺子",
            role = encodeRoles(setOf(DishRole.STAPLE, DishRole.PROTEIN, DishRole.VEGETABLE))
        )
        assertEquals(
            setOf(DishRole.STAPLE, DishRole.PROTEIN, DishRole.VEGETABLE),
            multi.dishRoles
        )

        val fallback = Recipe(id = 3L, name = "豆腐汤")
        assertTrue(DishRole.SOUP in fallback.dishRoles)
    }

    @Test
    fun defaultRecipes_coverEveryRoleForBalancedMeals() {
        val roles = DEFAULT_RECIPES.flatMap { it.dishRoles }.toSet()
        assertEquals(DishRole.entries.toSet(), roles)
    }

    @Test
    fun planWeek_defaultsToRicePorridgeProteinAndSauteedVegetable() {
        val meal = MealPlanner.planWeek(DEFAULT_RECIPES, dayCount = 1).single()
        val labels = meal.dishes.map { it.label }

        // 主食固定米饭 + 白粥，两样每天都上；且都指向菜谱（能按「米」做食材检查）。
        assertTrue(labels.contains("米饭"))
        assertTrue(labels.contains("白粥"))
        assertTrue(
            meal.dishes.filter { it.dishRole == DishRole.STAPLE }.all { it.recipeId != null }
        )
        // 蔬菜固定清炒时蔬。
        assertTrue(labels.contains("清炒时蔬"))
        // 蛋白配一道。
        assertTrue(meal.dishes.any { it.dishRole == DishRole.PROTEIN })
        // 默认不配汤饮。
        assertTrue(meal.dishes.none { it.dishRole == DishRole.SOUP })
    }

    @Test
    fun planWeek_balancesEveryMealAcrossAllRoles() {
        val byId = DEFAULT_RECIPES.associateBy { it.id }
        val plan = MealPlanner.planWeek(DEFAULT_RECIPES, dayCount = 7)

        assertEquals(7, plan.size)
        plan.forEach { meal ->
            val covered = meal.dishes.flatMap { dish ->
                dish.recipeId?.let { byId[it]?.dishRoles } ?: setOf(dish.dishRole)
            }.toSet()
            // 主食（固定米饭/白粥）+ 蛋白是硬性要求；蔬菜默认清炒时蔬，汤饮默认不配。
            assertTrue(covered.containsAll(listOf(DishRole.STAPLE, DishRole.PROTEIN)))
            assertTrue(covered.contains(DishRole.VEGETABLE))
            // 每道菜要么来自菜谱库，要么是基础主食/蔬菜（有名字）。
            assertTrue(meal.dishes.all { it.recipeId != null || it.label.isNotBlank() })
        }
    }

    @Test
    fun planWeek_proteinNeverFallsBackToSoup() {
        val recipes = listOf(
            Recipe(id = 1L, name = "可乐鸡翅", role = DishRole.PROTEIN.name),
            Recipe(
                id = 2L,
                name = "紫菜蛋花汤",
                role = encodeRoles(setOf(DishRole.SOUP, DishRole.PROTEIN))
            )
        )

        // 非汤蛋白只有一道：轮完了宁可天天重复，也不能拿汤品凑蛋白。
        val plan = MealPlanner.planWeek(recipes, dayCount = 3)
        plan.forEach { meal ->
            val proteins = meal.dishes.filter { it.dishRole == DishRole.PROTEIN }
            assertEquals(listOf("可乐鸡翅"), proteins.map { it.label })
        }

        // 只有汤蛋白时宁缺：不配蛋白，也不把汤标成蛋白。
        val soupOnly = MealPlanner.planWeek(listOf(recipes[1]), dayCount = 1).single()
        assertTrue(soupOnly.dishes.none { it.dishRole == DishRole.PROTEIN })
    }

    private fun itemDetail(
        id: Long,
        name: String,
        expireTime: Long? = null,
        rating: Int? = null
    ): ItemDetail {
        return ItemDetail(
            item = Item(
                id = id,
                name = name,
                expireTime = expireTime,
                rating = rating,
                createdAt = id
            )
        )
    }
}