package com.goodyaoshi.lemonbox.data.meal

import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import kotlin.random.Random

/**
 * 一条菜谱推荐：一条推荐 = 一道菜，而不是一堆散装食材。
 *
 * @param name 菜名，例如「番茄炒蛋」。
 * @param available 已经能用的食材：食材名 → 命中的在库物品。
 * @param missing 还差的主料名称（可选配料不计入）。
 * @param expiring 用到的食材里是否有临期或已过期的。
 * @param earliestExpiry 命中食材里最早过期的日期（毫秒时间戳），没填有效期的食材不算；
 *   供「先做快过期的」按最早过期日期精确排序，没带有效期时为 null（排最后）。
 */
data class RecipeSuggestion(
    val id: Long,
    val name: String,
    val available: List<Pair<String, ItemDetail>>,
    val missing: List<String>,
    val expiring: Boolean,
    val earliestExpiry: Long? = null
)

/**
 * 「今天吃什么」的菜谱推荐。
 *
 * 思路：拿用户菜谱库（内置 + 自建）去和在库食材逐条比对，一条推荐 = 一整道菜，
 * 天然不会出现整屏都是饮料、调料或同一种肉。
 *
 * 推荐按三层兜底，保证只要菜谱库非空，「今天吃什么」就不会空：
 * 1. 命中主料的菜（真正开得了火）；
 * 2. 只命中了部分食材 / 配料的菜（有部分物品能对上菜单）；
 * 3. 完全没命中时随机推菜谱（看着办，缺什么买什么）。
 * 食材池为空（家当里没有可吃的）时全部菜谱都落在第 3 层，随机推荐不缺席。
 *
 * [pinned] 里点名的菜（今天实际做过的）永远排在最前，换一批也不会被换掉。
 *
 * 纯函数、无副作用，随机源与当前时间都可注入，便于单测。
 */
object MealSuggester {

    /** 一次最多推荐几道菜。 */
    const val RECOMMEND_COUNT = 3

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** 命中食材距过期不足这个天数即视为「临期」。 */
    private const val EXPIRING_WINDOW_DAYS = 7

    /** 评估层级：命中了主料，开得了火。 */
    private const val TIER_MAIN = 1

    /** 评估层级：只命中了部分食材或配料，凑合能对上菜单。 */
    private const val TIER_PARTIAL = 2

    /** 评估层级：完全没命中，作为「今天吃什么」的随机兜底。 */
    private const val TIER_RANDOM = 3

    /**
     * 从 [recipes] 里挑出当前 [pool] 能做的菜，最多返回 [limit] 条。
     *
     * @param categoryTexts 物品分类 id → 「父分类名 子分类名 …」链文本，
     *   让食材可以按上层分类匹配（物品挂在「蔬菜 → 番茄」时，「番茄」食材直接命中）。
     * @param pinned 今天已经做过的菜名，永远保留在推荐里且排最前。
     * @param now 判定食材是否临期用的当前时间。
     * @param random 随机源，测试时可注入固定种子。
     */
    fun suggest(
        recipes: List<Recipe>,
        pool: List<ItemDetail>,
        categoryTexts: Map<Long, String> = emptyMap(),
        pinned: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
        random: Random = Random.Default,
        limit: Int = RECOMMEND_COUNT
    ): List<RecipeSuggestion> {
        // 食材池为空也算合法输入：所有菜谱都进随机兜底层，推荐不缺席。
        if (recipes.isEmpty() || limit <= 0) return emptyList()

        val candidates = buildCandidates(pool)

        val ranked = rankByTier(
            recipes.map { recipe -> evaluate(recipe, candidates, categoryTexts, now) },
            random
        )
        // 做过的菜钉在最前：换一批只换掉剩下的位置。
        return (ranked.filter { it.name in pinned } + ranked.filterNot { it.name in pinned })
            .take(limit)
    }

    /** 同名物品只保留最临期的一份，避免同一种食材重复出现在推荐里。 */
    private fun buildCandidates(pool: List<ItemDetail>): List<ItemDetail> = pool
        .groupBy { it.item.name }
        .values
        .map { sameName -> sameName.minBy { it.item.expireTime ?: Long.MAX_VALUE } }

    /**
     * 按「能开火的优先」为全部菜谱排序（不截断），供「未来一周菜谱」按天分配且不重复。
     * 排序规则与 [suggest] 一致：主料全齐 → 最早过期的先做 → 命中食材多 → 随机。
     */
    fun rankRecipes(
        recipes: List<Recipe>,
        pool: List<ItemDetail>,
        categoryTexts: Map<Long, String> = emptyMap(),
        now: Long = System.currentTimeMillis(),
        random: Random = Random.Default
    ): List<Recipe> {
        if (recipes.isEmpty()) return emptyList()
        val byId = recipes.associateBy { it.id }
        val candidates = buildCandidates(pool)
        return rankByTier(
            recipes.map { recipe -> evaluate(recipe, candidates, categoryTexts, now) },
            random
        ).mapNotNull { byId[it.id] }
    }

    /**
     * 用当前食材池评估单道菜谱，得到「能用什么 / 还差什么」。
     * 周菜单里的每一天按已定菜谱重新评估，库存变动后状态实时刷新。
     */
    fun evaluateRecipe(
        recipe: Recipe,
        pool: List<ItemDetail>,
        categoryTexts: Map<Long, String> = emptyMap(),
        now: Long = System.currentTimeMillis()
    ): RecipeSuggestion = evaluate(recipe, buildCandidates(pool), categoryTexts, now).first

    /** 三层兜底的排序：主料命中的菜 → 部分匹配的菜 → 随机菜谱。 */
    private fun rankByTier(
        evaluated: List<Pair<RecipeSuggestion, Int>>,
        random: Random
    ): List<RecipeSuggestion> {
        val main = evaluated.filter { it.second == TIER_MAIN }.map { it.first }
        val partial = evaluated.filter { it.second == TIER_PARTIAL }.map { it.first }
        val fallback = evaluated.filter { it.second == TIER_RANDOM }.map { it.first }

        // 第一层：主料全齐 → 命中食材里最早过期的先做（精确到日期，没填有效期排最后）
        // → 命中食材多 → 随机。
        val mainRanked = main
            .map { it to random.nextDouble() }
            .sortedWith(
                compareByDescending<Pair<RecipeSuggestion, Double>> { it.first.missing.isEmpty() }
                    .thenBy { it.first.earliestExpiry ?: Long.MAX_VALUE }
                    .thenByDescending { it.first.available.size }
                    .thenBy { it.second }
            )
            .map { it.first }
        // 第二层：最早过期的先做 → 命中食材多 → 随机。
        val partialRanked = partial
            .map { it to random.nextDouble() }
            .sortedWith(
                compareBy<Pair<RecipeSuggestion, Double>> { it.first.earliestExpiry ?: Long.MAX_VALUE }
                    .thenByDescending { it.first.available.size }
                    .thenBy { it.second }
            )
            .map { it.first }
        // 第三层：反正都缺料，随机给几道换换口味。
        return mainRanked + partialRanked + fallback.shuffled(random)
    }

    /**
     * 评估一道菜在当前食材池下的完成度，并给出兜底层级。
     */
    private fun evaluate(
        recipe: Recipe,
        pool: List<ItemDetail>,
        categoryTexts: Map<Long, String>,
        now: Long
    ): Pair<RecipeSuggestion, Int> {
        val used = mutableSetOf<Long>()
        val available = mutableListOf<Pair<String, ItemDetail>>()
        val missing = mutableListOf<String>()
        var matchedRequired = false

        recipe.ingredients.forEach { ingredient ->
            val matched = pool.firstOrNull { detail ->
                detail.item.id !in used && matches(ingredient, detail, categoryTexts)
            }
            when {
                matched != null -> {
                    used += matched.item.id
                    available += ingredient.label to matched
                    if (!ingredient.optional) matchedRequired = true
                }

                !ingredient.optional -> missing += ingredient.label
            }
        }

        val tier = when {
            matchedRequired -> TIER_MAIN
            available.isNotEmpty() -> TIER_PARTIAL
            else -> TIER_RANDOM
        }
        return RecipeSuggestion(
            id = recipe.id,
            name = recipe.name,
            available = available,
            missing = missing,
            expiring = available.any { isExpiring(it.second.item.expireTime, now) },
            earliestExpiry = available.mapNotNull { it.second.item.expireTime }
                .filter { it > 0L }
                .minOrNull()
        ) to tier
    }

    /** 物品名称、所属分类名、上级分类链任一命中关键词即可。 */
    fun matches(
        ingredient: RecipeIngredient,
        detail: ItemDetail,
        categoryTexts: Map<Long, String>
    ): Boolean {
        val text = buildString {
            append(detail.item.name)
            append(' ')
            append(detail.categoryName.orEmpty())
            detail.item.categoryId?.let { id ->
                categoryTexts[id]?.let { chain ->
                    append(' ')
                    append(chain)
                }
            }
        }.lowercase()
        return ingredient.matchKeywords.any { text.contains(it.lowercase()) }
    }

    private fun isExpiring(expireTime: Long?, now: Long): Boolean {
        if (expireTime == null) return false
        return expireTime - now <= EXPIRING_WINDOW_DAYS * DAY_MS
    }
}