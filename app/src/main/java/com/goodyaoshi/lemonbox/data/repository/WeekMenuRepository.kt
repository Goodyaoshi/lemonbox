package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.local.entity.Reminder
import com.goodyaoshi.lemonbox.data.local.entity.ReminderRepeatType
import com.goodyaoshi.lemonbox.data.local.entity.ReminderSource
import com.goodyaoshi.lemonbox.data.meal.DishRole
import com.goodyaoshi.lemonbox.data.meal.MealCombo
import com.goodyaoshi.lemonbox.data.meal.MealDish
import com.goodyaoshi.lemonbox.data.meal.MealPlanner
import com.goodyaoshi.lemonbox.data.meal.MealSuggester
import com.goodyaoshi.lemonbox.data.meal.MealSpec
import com.goodyaoshi.lemonbox.data.meal.Recipe
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.DateUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** 「未来 N 天菜单」里的一天：日期、展示名、一餐搭配与是否已做。 */
data class WeeklyMealDay(
    val dateKey: String,
    val dayLabel: String,
    val shortDate: String,
    val combo: MealCombo,
    val cooked: Boolean
) {
    /** 「番茄炒蛋 + 蒜蓉青菜 + 清汤」这样的组合标题。 */
    val title: String get() = combo.title

    /** 这一餐还缺的主料（去重）。 */
    val missing: List<String> get() = combo.missing
}

/** init 里 combine 多个数据源的中间载体。 */
private data class WeeklyInput(
    val recipes: List<Recipe>,
    val items: List<ItemDetail>,
    val stored: Map<String, MealSpec>,
    val cooked: Set<String>,
    val planDays: Int
)

/**
 * 吃饭域的数据仓库：未来 N 天菜单的生成与编辑、「做了」扣料、
 * 备菜提醒、以及缺料自动补进待买（采购闭环的起点，saveDayMeal 与 rerollDay 统一触发）。
 */
@Singleton
class WeekMenuRepository @Inject constructor(
    private val itemRepository: ItemRepository,
    private val categoryRepository: CategoryRepository,
    private val locationRepository: LocationRepository,
    private val reminderRepository: ReminderRepository,
    private val appPreferences: AppPreferences
) {

    /** 当前食材池，供搭配与扣减食材复用；随 [weekPlan] 被收集时刷新。 */
    private var mealPool: List<ItemDetail> = emptyList()

    /** 食材池物品的分类链文本，用于按分类匹配食材、识别调味品。 */
    private var mealCategoryTexts: Map<Long, String> = emptyMap()

    /** 当前菜谱库，供搭配与扣减复用。 */
    private var recipes: List<Recipe> = emptyList()

    /** 最近一次菜单计算的快照，供同步文案（如备菜提醒标题）复用。 */
    private var latestDays: List<WeeklyMealDay> = emptyList()

    /**
     * 未来 N 天菜单：每天默认「米饭+白粥+蛋白菜+清炒时蔬」（不配汤饮），可手动编辑；库存变动后「能用/还差」实时刷新。
     * 这是冷流，由各 ViewModel 在自身的 viewModelScope 内收集（仓库不再自建协程作用域）；
     * 收集过程顺带刷新 [recipes]/[mealPool] 缓存，供扣料、补缺料与备菜文案复用。
     */
    val weekPlan: Flow<List<WeeklyMealDay>> = combine(
        appPreferences.recipes,
        itemRepository.getActiveItems(),
        appPreferences.weeklyMenu,
        appPreferences.cookedMenuDates,
        appPreferences.mealPlanDays
    ) { latestRecipes, items, stored, cooked, planDays ->
        WeeklyInput(latestRecipes, items, stored, cooked, planDays)
    }.map { input ->
        recipes = input.recipes
        mealPool = buildMealPool(input.items)
        val plan = ensureWeekPlan(input.recipes, input.stored, input.planDays)
        buildWeekDays(input.recipes, plan, input.cooked).also { latestDays = it }
    }

    /** 菜谱库，供手动编辑某一餐时挑选菜品。 */
    val recipeLibrary: StateFlow<List<Recipe>> = appPreferences.recipes

    /** 「未来 N 天菜谱」的天数，用户可自定义（默认一周）。 */
    val planDays: StateFlow<Int> = appPreferences.mealPlanDays

    /** 已经生成过「提醒准备」的菜谱日期集合，周菜谱据此把按钮显示为「已提醒」；由调用方自行 stateIn。 */
    val mealPrepDays: Flow<Set<String>> = reminderRepository.getMealPrepKeys()
        .map { keys ->
            keys.mapNotNull { key ->
                key.removePrefix(MEAL_PREP_KEY_PREFIX).takeIf(String::isNotBlank)
            }.toSet()
        }

    /** 备菜提醒的默认提前天数（-1 前一天 / 0 当天）与触发时间，在设置页配置。 */
    val mealPrepDefaultDayShift: StateFlow<Int> = appPreferences.mealPrepDayShift

    val mealPrepDefaultFireTime: StateFlow<String> = appPreferences.mealPrepFireTime

    /** 修改计划天数（1..30），菜单会按新天数自动补齐或裁剪。 */
    fun setPlanDays(days: Int) {
        appPreferences.setMealPlanDays(days)
    }

    /** 一餐做完（今天/未来某天）：扣掉这一天所有菜品的食材并标记已做；已做过的不重复扣（幂等）。 */
    suspend fun markDayCooked(dateKey: String) {
        if (dateKey in appPreferences.cookedMenuDates.value) return
        val day = latestDays.firstOrNull { it.dateKey == dateKey } ?: return
        // 扣不扣只看食材所属分类（主食粮油/调味品不扣），与菜品本身是主食还是蛋白无关。
        val recipeIds = day.combo.dishes.mapNotNull { it.recipeId }
        recipeIds.forEach { id ->
            recipes.firstOrNull { it.id == id }?.let { deductRecipeIngredients(it) }
        }
        appPreferences.markMenuCooked(dateKey)
    }

    /** 换一天：给指定日期整套换一份不与其它天重复的搭配，并照旧把缺的主料补进待买。 */
    suspend fun rerollDay(dateKey: String) {
        val plan = appPreferences.weeklyMenu.value
        val allRecipes = appPreferences.recipes.value
        if (allRecipes.isEmpty()) return
        val usedElsewhere = plan.filterKeys { it != dateKey }
            .values
            .flatMap { spec -> spec.dishes.mapNotNull { it.recipeId } }
            .toSet()
        val rankedAll = MealSuggester.rankRecipes(allRecipes, mealPool, mealCategoryTexts)
        val ranked = rankedAll.filterNot { it.id in usedElsewhere }
        val spec = MealPlanner.planWeek(ranked.ifEmpty { rankedAll }, 1).firstOrNull() ?: return
        appPreferences.setWeeklyMenu(plan + (dateKey to spec))
        addMissingIngredientsToBuy(spec)
    }

    /** 手动保存某天的一餐：覆盖菜单，并把仍然缺的主料自动加入待买清单。 */
    suspend fun saveDayMeal(dateKey: String, spec: MealSpec) {
        if (dateKey.isBlank() || spec.dishes.isEmpty()) return
        appPreferences.setWeeklyMenu(appPreferences.weeklyMenu.value + (dateKey to spec))
        addMissingIngredientsToBuy(spec)
    }

    /**
     * 某天一餐的备菜提醒预填文案：只有蛋白质角色那道菜才可能带冻肉/水产，
     * 才需要提前解冻，提醒文案也指蛋白菜。例如「记得解冻肉，明天要做辣椒炒肉」。
     * 相对词按 [triggerDateKey]（提醒触发日）为基准换算：触发日在明天、菜谱在后天，
     * 就要说「明天要做」而不是「后天要做」，否则触发时读者会理解错日子。
     */
    fun mealPrepTitle(dateKey: String, triggerDateKey: String): String? {
        val day = latestDays.firstOrNull { it.dateKey == dateKey } ?: return null
        val proteinDishes = day.combo.dishes.filter { DishRole.PROTEIN in it.displayRoles }
        val proteinRecipes = proteinDishes
            .mapNotNull { it.recipeId }
            .mapNotNull { id -> recipes.firstOrNull { it.id == id } }
        // 蛋白菜的主料带肉/排骨/鸡/鱼/虾等（鸡蛋除外）才算需要解冻。
        val thawRecipe = proteinRecipes.firstOrNull { recipe ->
            recipe.ingredients.any { ingredient ->
                ingredient.matchKeywords.any { keyword ->
                    !keyword.contains("蛋") &&
                        MEAT_KEYWORD_HINTS.any { keyword.contains(it) }
                }
            }
        }
        val mainName = thawRecipe?.name
            ?: proteinDishes.firstOrNull()?.label
            ?: return null
        val cookingDayLabel = runCatching {
            DateUtil.relativeDayLabel(LocalDate.parse(dateKey), LocalDate.parse(triggerDateKey))
        }.getOrDefault(day.dayLabel)
        return if (thawRecipe != null) {
            "记得解冻肉，${cookingDayLabel}要做$mainName"
        } else {
            "提前备菜：${cookingDayLabel}要做$mainName"
        }
    }

    /**
     * 为某天的一餐生成备菜提醒（一次性）：[dayShift] 相对菜谱日（-1 前一天、0 当天）。
     * 一天最多一条（sourceKey 去重）；时刻已过时返回 false 由页面提示。
     */
    suspend fun createMealPrepReminder(dateKey: String, dayShift: Int, fireTime: String): Boolean {
        val targetDate = runCatching {
            LocalDate.parse(dateKey).plusDays(dayShift.toLong()).toString()
        }.getOrNull()
        val title = mealPrepTitle(dateKey, targetDate ?: dateKey) ?: return false
        val note = latestDays.firstOrNull { it.dateKey == dateKey }?.title.orEmpty()
        return reminderRepository.create(
            Reminder(
                title = title,
                note = note,
                repeatType = ReminderRepeatType.ONCE.name,
                fireTime = fireTime,
                targetDate = targetDate,
                source = ReminderSource.MEAL_PREP.name,
                sourceKey = MEAL_PREP_KEY_PREFIX + dateKey
            )
        )
    }

    /** 把一餐里还缺的主料写入待买清单，已经在待买里的不重复添加（采购闭环第一环）。 */
    private suspend fun addMissingIngredientsToBuy(spec: MealSpec) {
        val byId = recipes.associateBy { it.id }
        val missing = spec.dishes
            .mapNotNull { it.recipeId }
            .mapNotNull { byId[it] }
            .flatMap { recipe ->
                MealSuggester.evaluateRecipe(recipe, mealPool, mealCategoryTexts).missing
            }
            .distinct()
        missing.forEach { name ->
            itemRepository.addToBuyItemIfAbsent(name, 1, DEFAULT_BUY_UNIT, null)
        }
    }

    /**
     * 保证未来一周每天都有搭配：保留仍有效（菜谱还在）的已定菜单，
     * 缺失的天按「能开火的优先」重新搭配。结果持久化，避免每次打开都重新洗牌。
     */
    private fun ensureWeekPlan(
        allRecipes: List<Recipe>,
        stored: Map<String, MealSpec>,
        dayCount: Int
    ): Map<String, MealSpec> {
        val dateKeys = weekDateKeys(dayCount)
        val byId = allRecipes.associateBy { it.id }
        val result = linkedMapOf<String, MealSpec>()

        dateKeys.forEach { key ->
            val spec = stored[key] ?: return@forEach
            // 覆盖到的角色取并集：一道多角色菜（如饺子）可以一次补齐主食/蛋白/蔬菜。
            val covered = spec.dishes.flatMap { dish ->
                dish.recipeId?.let { byId[it]?.dishRoles } ?: setOf(dish.dishRole)
            }.toSet()
            // 必须是一份完整的一餐（主食 + 蛋白），且引用的菜谱都还在；
            // 旧版「一天一道菜」的存储不满足时会被重新搭配成完整一餐。
            val valid = spec.dishes.isNotEmpty() &&
                REQUIRED_MEAL_ROLES.all { it in covered } &&
                spec.dishes.all { it.recipeId == null || byId.containsKey(it.recipeId) }
            if (valid) result[key] = spec
        }

        val missingKeys = dateKeys.filterNot { result.containsKey(it) }
        if (missingKeys.isNotEmpty()) {
            val ranked = MealSuggester.rankRecipes(allRecipes, mealPool, mealCategoryTexts)
            val fresh = MealPlanner.planWeek(ranked, missingKeys.size)
            missingKeys.forEachIndexed { index, key ->
                fresh.getOrNull(index)?.let { result[key] = it }
            }
        }

        val ordered = dateKeys.mapNotNull { key -> result[key]?.let { key to it } }.toMap()
        if (ordered != stored) {
            appPreferences.setWeeklyMenu(ordered)
        }
        return ordered
    }

    /** 把「日期 → 一餐搭配」映射为可展示的每一天：每道菜的能用/还差与是否已做。 */
    private fun buildWeekDays(
        allRecipes: List<Recipe>,
        plan: Map<String, MealSpec>,
        cooked: Set<String>
    ): List<WeeklyMealDay> {
        val byId = allRecipes.associateBy { it.id }
        return plan.entries
            .sortedBy { it.key }
            .map { (dateKey, spec) ->
                val dishes = spec.dishes.map { dish ->
                    val recipe = dish.recipeId?.let { byId[it] }
                    MealDish(
                        role = dish.dishRole,
                        label = recipe?.name ?: dish.label.ifBlank { dish.dishRole.label },
                        recipeId = dish.recipeId,
                        suggestion = recipe?.let {
                            MealSuggester.evaluateRecipe(it, mealPool, mealCategoryTexts)
                        },
                        roles = recipe?.dishRoles.orEmpty()
                    )
                }
                WeeklyMealDay(
                    dateKey = dateKey,
                    dayLabel = dayLabel(dateKey),
                    shortDate = shortDate(dateKey),
                    combo = MealCombo(dishes),
                    cooked = dateKey in cooked
                )
            }
    }

    /** 从今天起 [dayCount] 天的日期 key（默认一周，可自定义为 1..30 天）。 */
    private fun weekDateKeys(dayCount: Int): List<String> {
        val today = LocalDate.now()
        return (0 until dayCount).map { today.plusDays(it.toLong()).toString() }
    }

    private fun dayLabel(dateKey: String): String {
        val date = runCatching { LocalDate.parse(dateKey) }.getOrNull() ?: return dateKey
        return DateUtil.relativeDayLabel(date)
    }

    /** 日期 Tabs 上的短日期，如「10/04」。 */
    private fun shortDate(dateKey: String): String =
        runCatching { LocalDate.parse(dateKey) }
            .map { "${it.monthValue}/${it.dayOfMonth}" }
            .getOrDefault(dateKey)

    /**
     * 扣掉一道菜用到的食材：每样匹配上的主料扣 1 件，减到 0 自动记为已用完。
     * 是否扣减只看食材所属分类：主食粮油、调味品、辅料不扣；菜谱里标为「可选」的配料也不扣。
     */
    private suspend fun deductRecipeIngredients(recipe: Recipe) {
        val used = mutableSetOf<Long>()
        recipe.ingredients
            .filterNot { it.optional }
            .forEach { ingredient ->
                val matched = mealPool.firstOrNull { detail ->
                    detail.item.id !in used &&
                        MealSuggester.matches(ingredient, detail, mealCategoryTexts)
                } ?: return@forEach
                if (isNonDeductible(matched)) return@forEach
                used += matched.item.id
                itemRepository.consumeOne(matched.item.id)
            }
    }

    /** 主食粮油（米面油）、调味品（酱油/盐/醋）、辅料（葱姜蒜辣椒）不参与扣减：看物品所属分类链。 */
    private fun isNonDeductible(detail: ItemDetail): Boolean {
        if (detail.categoryName in NON_DEDUCTIBLE_CATEGORIES) return true
        val chain = detail.item.categoryId?.let { mealCategoryTexts[it] }.orEmpty()
        return NON_DEDUCTIBLE_CATEGORIES.any { chain.contains(it) }
    }

    /**
     * 食材池：在库（`disposition == 0`）且未用完的物品中，
     * 分类落在「食品」子树或位置落在「冰箱」子树的并集。
     * 同时为池内物品准备分类链文本，供菜谱按分类匹配食材。
     */
    private suspend fun buildMealPool(items: List<ItemDetail>): List<ItemDetail> {
        mealCategoryTexts = emptyMap()
        val edible = items.filter {
            it.item.disposition == Item.DISPOSITION_IN_STOCK &&
                it.item.usageStatus != Item.USAGE_USED_UP
        }
        if (edible.isEmpty()) return emptyList()

        val allCategories =
            categoryRepository.getAllCategoriesSnapshot().filter { it.deletedAt == null }
        val allLocations =
            locationRepository.getAllLocationsSnapshot().filter { it.deletedAt == null }
        val foodRoot = allCategories.firstOrNull { it.name == MEAL_CATEGORY_NAME }
        val fridgeRoot = allLocations.firstOrNull { it.name == MEAL_LOCATION_NAME }
        val foodCategoryIds = foodRoot?.let {
            collectSubtreeIds(allCategories, it.id, idOf = { c -> c.id }, parentIdOf = { c -> c.parentId })
        } ?: emptySet()
        val fridgeLocationIds = fridgeRoot?.let {
            collectSubtreeIds(allLocations, it.id, idOf = { l -> l.id }, parentIdOf = { l -> l.parentId })
        } ?: emptySet()
        if (foodCategoryIds.isEmpty() && fridgeLocationIds.isEmpty()) return emptyList()

        mealCategoryTexts = buildCategoryTexts(allCategories)
        return edible.filter { detail ->
            val categoryId = detail.item.categoryId
            val locationId = detail.item.locationId
            (categoryId != null && categoryId in foodCategoryIds) ||
                (locationId != null && locationId in fridgeLocationIds)
        }
    }

    /** 每个分类 id → 「父分类名 子分类名 …」链文本，菜谱按这条链匹配上层分类。 */
    private fun buildCategoryTexts(categories: List<Category>): Map<Long, String> {
        val byId = categories.associateBy { it.id }
        return categories.associate { category ->
            // 从当前节点一路走到根；take 防御异常数据造成的环。
            val chain = generateSequence(category) { byId[it.parentId] }
                .take(MAX_CATEGORY_DEPTH)
                .map { it.name }
                .toList()
                .asReversed()
            category.id to chain.joinToString(" ")
        }
    }

    /** 取 [rootId] 及其全部后代（不含墓碑）的 id 集合。 */
    private fun <T> collectSubtreeIds(
        all: List<T>,
        rootId: Long,
        idOf: (T) -> Long,
        parentIdOf: (T) -> Long?
    ): Set<Long> {
        val childrenMap = all.groupBy { parentIdOf(it) }
        val result = mutableSetOf(rootId)
        val pending = ArrayDeque<Long>().apply { add(rootId) }
        while (pending.isNotEmpty()) {
            childrenMap[pending.removeFirst()].orEmpty().forEach { child ->
                if (result.add(idOf(child))) {
                    pending += idOf(child)
                }
            }
        }
        return result
    }

    private companion object {
        /** 分类链回溯的深度上限，防御异常数据成环。 */
        const val MAX_CATEGORY_DEPTH = 8

        /** 「今天吃什么」取材的根分类名，命中其整棵子树。 */
        const val MEAL_CATEGORY_NAME = "食品"

        /** 「今天吃什么」取材的根位置名，命中其整棵子树。 */
        const val MEAL_LOCATION_NAME = "冰箱"

        /** 点「做了」扣料时跳过的一级食材分类：主食粮油、调味品与辅料（葱/姜/蒜/辣椒）。 */
        val NON_DEDUCTIBLE_CATEGORIES = setOf("主食粮油", "调味品", "辅料")

        /** 一餐必须包含的角色：主食 + 蛋白；蔬菜与汤饮优先配但可选。 */
        val REQUIRED_MEAL_ROLES = listOf(DishRole.STAPLE, DishRole.PROTEIN)

        /** 手动编辑一餐后自动补进待买清单的默认单位。 */
        const val DEFAULT_BUY_UNIT = "件"

        /** 菜谱准备提醒的来源键前缀（meal_prep:日期），一天最多生成一条。 */
        const val MEAL_PREP_KEY_PREFIX = "meal_prep:"

        /** 判断菜谱主料是否需要解冻的关键词（含「蛋」的不算，鸡蛋不用解冻）。 */
        val MEAT_KEYWORD_HINTS = listOf("肉", "排骨", "肋排", "鸡", "牛", "羊", "鱼", "虾")
    }
}
