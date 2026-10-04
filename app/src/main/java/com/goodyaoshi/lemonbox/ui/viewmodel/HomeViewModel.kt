package com.goodyaoshi.lemonbox.ui.viewmodel

import android.util.Log
import com.goodyaoshi.lemonbox.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.dao.CategoryDao
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.local.entity.Location
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
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.data.repository.LocationRepository
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import com.goodyaoshi.lemonbox.data.search.LocalSearchParser
import com.goodyaoshi.lemonbox.data.search.SearchCriteria
import com.goodyaoshi.lemonbox.data.search.SearchEngine
import com.goodyaoshi.lemonbox.data.search.SearchIntentKind
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.goodyaoshi.lemonbox.util.DateUtil
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val categoryDao: CategoryDao,
    private val categoryRepository: CategoryRepository,
    private val locationRepository: LocationRepository,
    private val reminderRepository: ReminderRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    val activeItems: StateFlow<List<ItemDetail>> = itemRepository.getActiveItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allItems: StateFlow<List<ItemDetail>> = itemRepository.getAllItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 临期物品：在库、有有效期，且剩余天数落在它自己（或全局）提醒阶梯的最大窗口内。 */
    val expiringItems: StateFlow<List<ItemDetail>> =
        combine(activeItems, appPreferences.reminderLadder) { items, ladder ->
            items.filter { isExpiringSoon(it.item, ladder) }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val expiringCount: StateFlow<Int> = expiringItems
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 逐件判定是否临期：用物品自己的阶梯窗口（未设置则跟随全局）。 */
    private fun isExpiringSoon(item: Item, globalLadder: List<Int>): Boolean {
        val expireTime = item.expireTime ?: return false
        if (expireTime <= 0L) return false
        val window = Item.reminderWindowDays(item.reminderDays, globalLadder)
        return DateUtil.daysUntil(expireTime) <= window
    }

    val toBuyCount: StateFlow<Int> = itemRepository.getToBuyCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 今天要做的家务提醒数（含之前错过但还没触发的）。 */
    val todosTodayCount: StateFlow<Int> = reminderRepository.getActiveReminders()
        .map { list ->
            val endOfToday = LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            list.count { it.nextFireAt < endOfToday }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 已经生成过「提醒准备」的菜谱日期集合，周菜谱据此把按钮显示为「已提醒」。 */
    val mealPrepDays: StateFlow<Set<String>> = reminderRepository.getMealPrepKeys()
        .map { keys ->
            keys.mapNotNull { key ->
                key.removePrefix(MEAL_PREP_KEY_PREFIX).takeIf(String::isNotBlank)
            }.toSet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val categories: StateFlow<List<Category>> = itemRepository.getActiveItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        .let { activeItemsFlow ->
            combine(
                activeItemsFlow,
                categoryDao.getAllCategories()
            ) { items, categories ->
                val activeCategoryIds = items.mapNotNull { it.item.categoryId }.toSet()
                categories.filter { it.id in activeCategoryIds }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locations: StateFlow<List<Location>> = locationRepository.getAllLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _weekPlan = MutableStateFlow<List<WeeklyMealDay>>(emptyList())

    /** 未来 N 天菜单：每天默认「米饭+白粥+蛋白菜+清炒时蔬」（不配汤饮），可手动编辑；库存变动后「能用/还差」实时刷新。 */
    val weekPlan: StateFlow<List<WeeklyMealDay>> = _weekPlan.asStateFlow()

    /** 菜谱库，供手动编辑某一餐时挑选菜品。 */
    val recipeLibrary: StateFlow<List<Recipe>> = appPreferences.recipes

    /** 「未来 N 天菜谱」的天数，用户可自定义（默认一周）。 */
    val planDays: StateFlow<Int> = appPreferences.mealPlanDays

    /** 修改计划天数（1..30），菜单会按新天数自动补齐或裁剪。 */
    fun setPlanDays(days: Int) {
        appPreferences.setMealPlanDays(days)
    }

    /** 当前食材池，供搭配与扣减食材复用。 */
    private var mealPool: List<ItemDetail> = emptyList()

    /** 食材池物品的分类链文本，用于按分类匹配食材、识别调味品。 */
    private var mealCategoryTexts: Map<Long, String> = emptyMap()

    /** 当前菜谱库，供搭配与扣减复用。 */
    private var recipes: List<Recipe> = emptyList()

    init {
        viewModelScope.launch {
            combine(
                appPreferences.recipes,
                itemRepository.getActiveItems(),
                appPreferences.weeklyMenu,
                appPreferences.cookedMenuDates,
                appPreferences.mealPlanDays
            ) { latestRecipes, items, stored, cooked, planDays ->
                WeeklyInput(latestRecipes, items, stored, cooked, planDays)
            }.collect { input ->
                recipes = input.recipes
                mealPool = buildMealPool(input.items)
                val plan = ensureWeekPlan(input.recipes, input.stored, input.planDays)
                _weekPlan.value = buildWeekDays(input.recipes, plan, input.cooked)
            }
        }
    }

    /** 一餐做完（今天/未来某天）：扣掉这一天所有菜品的食材并标记已做，不弹窗。 */
    fun markDayCooked(dateKey: String) {
        if (dateKey in appPreferences.cookedMenuDates.value) return
        val day = _weekPlan.value.firstOrNull { it.dateKey == dateKey } ?: return
        // 扣不扣只看食材所属分类（主食粮油/调味品不扣），与菜品本身是主食还是蛋白无关。
        val recipeIds = day.combo.dishes
            .mapNotNull { it.recipeId }
        viewModelScope.launch {
            recipeIds.forEach { id ->
                recipes.firstOrNull { it.id == id }?.let { deductRecipeIngredients(it) }
            }
            appPreferences.markMenuCooked(dateKey)
        }
    }

    /** 换一天：给指定日期整套换一份不与其它天重复的搭配。 */
    fun rerollDay(dateKey: String) {
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
    }

    /** 手动保存某天的一餐：覆盖菜单，并把仍然缺的主料自动加入待买清单。 */
    fun saveDayMeal(dateKey: String, spec: MealSpec) {
        if (dateKey.isBlank() || spec.dishes.isEmpty()) return
        appPreferences.setWeeklyMenu(appPreferences.weeklyMenu.value + (dateKey to spec))
        viewModelScope.launch { addMissingIngredientsToBuy(spec) }
    }

    /**
     * 某天一餐的备菜提醒预填文案：只有蛋白质角色那道菜才可能带冻肉/水产，
     * 才需要提前解冻，提醒文案也指蛋白菜。例如「记得解冻肉，明天要做辣椒炒肉」。
     */
    fun mealPrepTitle(dateKey: String): String? {
        val day = _weekPlan.value.firstOrNull { it.dateKey == dateKey } ?: return null
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
        return if (thawRecipe != null) {
            "记得解冻肉，${day.dayLabel}要做$mainName"
        } else {
            "提前备菜：${day.dayLabel}要做$mainName"
        }
    }

    /**
     * 为某天的一餐生成备菜提醒（一次性）：[dayShift] -1 表示前一天晚上、0 表示当天早上。
     * 一天最多一条（sourceKey 去重）；时刻已过时返回 false 由页面提示。
     */
    fun createMealPrepReminder(
        dateKey: String,
        dayShift: Int,
        fireTime: String,
        onResult: (Boolean) -> Unit
    ) {
        val title = mealPrepTitle(dateKey)
        if (title == null) {
            onResult(false)
            return
        }
        val note = _weekPlan.value.firstOrNull { it.dateKey == dateKey }?.title.orEmpty()
        viewModelScope.launch {
            val saved = reminderRepository.create(
                Reminder(
                    title = title,
                    note = note,
                    repeatType = ReminderRepeatType.ONCE.name,
                    fireTime = fireTime,
                    targetDate = runCatching {
                        LocalDate.parse(dateKey).plusDays(dayShift.toLong()).toString()
                    }.getOrNull(),
                    source = ReminderSource.MEAL_PREP.name,
                    sourceKey = MEAL_PREP_KEY_PREFIX + dateKey
                )
            )
            onResult(saved)
        }
    }

    /** 把一餐里还缺的主料写入待买清单，已经在待买里的不重复添加。 */
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
        return when (date) {
            LocalDate.now() -> "今天"
            LocalDate.now().plusDays(1) -> "明天"
            LocalDate.now().plusDays(2) -> "后天"
            else -> WEEKDAY_LABELS.getOrElse(date.dayOfWeek.value - 1) { dateKey }
        }
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

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val searchHistory: StateFlow<List<String>> = appPreferences.searchHistory

    private val searchEngine = SearchEngine()
    private val localSearchParser = LocalSearchParser()

    private val _searchCriteria = MutableStateFlow<SearchCriteria?>(null)
    val searchCriteria: StateFlow<SearchCriteria?> = _searchCriteria.asStateFlow()

    private val _searchMessage = MutableStateFlow<String?>(null)
    val searchMessage: StateFlow<String?> = _searchMessage.asStateFlow()

    val searchResults: StateFlow<List<ItemDetail>> = combine(
        allItems,
        categories,
        locations,
        _searchQuery,
        _searchCriteria
    ) { items, categories, locations, query, criteria ->
        buildSearchResults(
            query = query,
            items = items,
            categories = categories,
            locations = locations,
            criteria = criteria
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _searchCriteria.value = null
        _searchMessage.value = null
    }

    fun rememberSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        appPreferences.setSearchHistory(
            listOf(normalized) +
                appPreferences.searchHistory.value
                    .filterNot { it.equals(normalized, ignoreCase = true) }
                    .take(MAX_SEARCH_HISTORY - 1)
        )
    }

    fun clearSearchHistory() {
        appPreferences.setSearchHistory(emptyList())
    }

    fun runSmartSearch() {
        val query = _searchQuery.value.trim()
        if (query.isBlank()) return

        rememberSearch(query)
        val criteria = parseCriteria(query, categories.value, locations.value)
        _searchCriteria.value = criteria
        _searchMessage.value = criteria.summary ?: DEFAULT_SEARCH_MESSAGE
    }

    private fun parseCriteria(
        query: String,
        categories: List<Category>,
        locations: List<Location>
    ): SearchCriteria {
        return localSearchParser.parse(
            query = query,
            categoryIdsByName = categories.associate { it.name to it.id },
            locationIdsByName = locations.associate { it.name to it.id },
            expiringWithinDays = appPreferences.reminderDays.value
        ).copy(reminderLadder = appPreferences.reminderLadder.value)
    }

    private fun buildSearchResults(
        query: String,
        items: List<ItemDetail>,
        categories: List<Category>,
        locations: List<Location>,
        criteria: SearchCriteria?
    ): List<ItemDetail> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()
        val resolvedCriteria = criteria ?: parseCriteria(normalizedQuery, categories, locations)
        val results = searchEngine.filter(items, locations, resolvedCriteria)
        val finalResults = if (results.isEmpty() && resolvedCriteria.canFallbackToPlainText()) {
            searchEngine.filter(items, locations, SearchCriteria(text = normalizedQuery))
        } else {
            results
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                SEARCH_UI_LOG_TAG,
                "query=$normalizedQuery items=${items.size} criteria=$resolvedCriteria results=${finalResults.size}"
            )
        }
        return finalResults
    }

    private fun SearchCriteria.canFallbackToPlainText(): Boolean {
        return kind == SearchIntentKind.PLAIN_TEXT
    }

    fun moveToTrash(item: Item) {
        viewModelScope.launch {
            itemRepository.moveToTrash(item)
        }
    }

    fun markAsUsed(id: Long) {
        viewModelScope.launch {
            itemRepository.markAsUsed(id)
        }
    }

    /** 「用1件」：数量减 1，减到 0 自动记为已用完。 */
    fun consumeOne(id: Long) {
        viewModelScope.launch {
            itemRepository.consumeOne(id)
        }
    }

    fun setUsageStatus(id: Long, usageStatus: Int) {
        viewModelScope.launch {
            itemRepository.setUsageStatus(id, usageStatus)
        }
    }

    fun setDisposition(id: Long, disposition: Int) {
        viewModelScope.launch {
            itemRepository.setDisposition(id, disposition)
        }
    }

    fun setNeedRestock(id: Long, needRestock: Boolean) {
        viewModelScope.launch {
            itemRepository.setNeedRestock(id, needRestock)
        }
    }

    private companion object {
        const val SEARCH_UI_LOG_TAG = "SearchUi"
        const val MAX_SEARCH_HISTORY = 8
        const val DEFAULT_SEARCH_MESSAGE = "已在本地按名称、分类、位置和状态完成搜索"

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

        /** 周几的展示名，索引 = dayOfWeek.value - 1。 */
        val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}
