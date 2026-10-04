package com.goodyaoshi.lemonbox.data.settings

import android.content.Context
import android.content.res.Configuration
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusCatalog
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusOption
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.data.meal.BUILT_IN_RECIPE_MAX_ID
import com.goodyaoshi.lemonbox.data.meal.DEFAULT_RECIPES
import com.goodyaoshi.lemonbox.data.meal.DishRole
import com.goodyaoshi.lemonbox.data.meal.MealDishSpec
import com.goodyaoshi.lemonbox.data.meal.MealSpec
import com.goodyaoshi.lemonbox.data.meal.Recipe
import com.goodyaoshi.lemonbox.data.meal.RecipeIngredient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 外观模式：跟随系统 / 始终浅色 / 始终深色。 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * 轻量偏好设置：基于 SharedPreferences，避免为此引入 DataStore 依赖。
 * 承载「提前几天提醒」「外观模式」等用户可配置项。
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext context: Context
) {

    private val preferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        preferences.getString(KEY_THEME_MODE, null)
            ?.let { stored -> runCatching { ThemeMode.valueOf(stored) }.getOrNull() }
            ?: ThemeMode.SYSTEM
    )

    /** 外观模式，默认跟随系统。 */
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        preferences.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    private val _reminderLadder = MutableStateFlow(loadReminderLadder())

    /**
     * 全局默认的到期提醒阶梯（天），默认 [DEFAULT_REMINDER_LADDER]。
     * 单件物品可在编辑页覆盖为各自的阶梯，未设置时沿用这份默认。
     */
    val reminderLadder: StateFlow<List<Int>> = _reminderLadder.asStateFlow()

    private val _reminderDays = MutableStateFlow(_reminderLadder.value.maxOrNull() ?: DEFAULT_REMINDER_DAYS)

    /**
     * 到期提醒的提前天数（取阶梯中的最大值）。
     * 首页「即将过期」与搜索的临期条件沿用它作为窗口上限。
     */
    val reminderDays: StateFlow<Int> = _reminderDays.asStateFlow()

    /** 更新全局默认提醒阶梯；去重、排序并夹在合法区间内。 */
    fun setReminderLadder(days: List<Int>) {
        val normalized = days
            .filter { it in MIN_REMINDER_DAYS..MAX_REMINDER_DAYS }
            .distinct()
            .sorted()
        preferences.edit()
            .putString(KEY_REMINDER_LADDER, normalized.joinToString(REMINDER_LADDER_SEPARATOR))
            .apply()
        _reminderLadder.value = normalized
        _reminderDays.value = normalized.maxOrNull() ?: DEFAULT_REMINDER_DAYS
    }

    /**
     * 读取全局默认阶梯；首次升级自旧的单一「提前 N 天」设置时，
     * 取默认档位中不超过旧值的一档，保证开箱即有合理的阶梯。
     */
    private fun loadReminderLadder(): List<Int> {
        val stored = preferences.getString(KEY_REMINDER_LADDER, null)
        if (stored != null) {
            val parsed = stored.split(REMINDER_LADDER_SEPARATOR)
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in MIN_REMINDER_DAYS..MAX_REMINDER_DAYS }
                .distinct()
                .sorted()
            if (parsed.isNotEmpty()) return parsed
        }
        val legacy = preferences.getInt(KEY_REMINDER_DAYS, DEFAULT_REMINDER_DAYS)
        return DEFAULT_REMINDER_LADDER.filter { it <= legacy }.ifEmpty { listOf(legacy) }
    }

    private val _reminderTimes = MutableStateFlow(loadReminderTimes())

    /**
     * 每天固定的到期提醒时间点（HH:mm），默认早上 8:00、晚上 19:00。
     * 每个时间点各触发一次检查，命中提醒阶梯的物品会在该时间点收到汇总通知。
     */
    val reminderTimes: StateFlow<List<String>> = _reminderTimes.asStateFlow()

    /** 更新提醒时间点；只保留候选档位内的时间，去重并排序。清空表示不再发送到期提醒。 */
    fun setReminderTimes(times: List<String>) {
        val normalized = normalizeReminderTimes(times)
        preferences.edit()
            .putString(KEY_REMINDER_TIMES, normalized.joinToString(REMINDER_TIME_SEPARATOR))
            .apply()
        _reminderTimes.value = normalized
    }

    private fun loadReminderTimes(): List<String> {
        val stored = preferences.getString(KEY_REMINDER_TIMES, null)
            ?: return DEFAULT_REMINDER_TIMES
        return normalizeReminderTimes(stored.split(REMINDER_TIME_SEPARATOR))
    }

    private fun normalizeReminderTimes(times: List<String>): List<String> =
        times.map { it.trim() }
            .filter { it in REMINDER_TIME_OPTIONS }
            .distinct()
            .sorted()

    private val _continuousEntry = MutableStateFlow(
        preferences.getBoolean(KEY_CONTINUOUS_ENTRY, false)
    )

    /** 连续录入：开启后保存成功会清空表单并自动唤起相机，方便一次录多件。默认关闭。 */
    val continuousEntry: StateFlow<Boolean> = _continuousEntry.asStateFlow()

    private val _keepAliveEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_KEEP_ALIVE_ENABLED, true)
    )

    /** 提醒保活：常驻前台服务防止划卡清理把提醒闹钟一起杀掉。默认开启。 */
    val keepAliveEnabled: StateFlow<Boolean> = _keepAliveEnabled.asStateFlow()

    private val _customReminderTimes = MutableStateFlow(
        preferences.getString(KEY_CUSTOM_REMINDER_TIMES, null)
            ?.split(REMINDER_TIME_SEPARATOR)
            ?.filter { it.matches(TIME_FORMAT) }
            ?.toSet()
            ?: emptySet()
    )

    /** 用户自定义的提醒时间点候选（补充固定档位）；展示与勾选时与固定档位合并。 */
    val customReminderTimes: StateFlow<Set<String>> = _customReminderTimes.asStateFlow()

    private val _mealPrepDayShift = MutableStateFlow(
        preferences.getInt(KEY_MEAL_PREP_DAY_SHIFT, -1)
    )

    /** 备菜提醒默认提前天数：-1 = 前一天，0 = 当天。 */
    val mealPrepDayShift: StateFlow<Int> = _mealPrepDayShift.asStateFlow()

    private val _mealPrepFireTime = MutableStateFlow(
        preferences.getString(KEY_MEAL_PREP_FIRE_TIME, "19:00")?.takeIf {
            it.matches(TIME_FORMAT)
        } ?: "19:00"
    )

    /** 备菜提醒默认触发时间（HH:mm）。 */
    val mealPrepFireTime: StateFlow<String> = _mealPrepFireTime.asStateFlow()

    private val _searchHistory = MutableStateFlow(loadSearchHistory())

    /** 搜索历史（最近 8 条，新的在前），持久化以免杀进程后丢失。 */
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _lastSyncAt = MutableStateFlow(
        preferences.getLong(KEY_LAST_SYNC_AT, NO_SYNC_AT).takeIf { it != NO_SYNC_AT }
    )

    /** 上一次成功同步的时间戳（毫秒），从未同步过时为 null。 */
    val lastSyncAt: StateFlow<Long?> = _lastSyncAt.asStateFlow()

    /** 本机标识，首次访问时生成并持久化；用于标记备份来自哪台设备。 */
    val deviceId: String = preferences.getString(KEY_DEVICE_ID, null)
        ?: UUID.randomUUID().toString().also { generated ->
            preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
        }

    fun setContinuousEntry(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CONTINUOUS_ENTRY, enabled).apply()
        _continuousEntry.value = enabled
    }

    fun setKeepAliveEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_KEEP_ALIVE_ENABLED, enabled).apply()
        _keepAliveEnabled.value = enabled
    }

    /** 新增自定义提醒时间点并自动勾选；已是固定档位或已存在的自定义值时只勾选。 */
    fun addCustomReminderTime(time: String) {
        if (!time.matches(TIME_FORMAT)) return
        if (time !in REMINDER_TIME_OPTIONS && time !in _customReminderTimes.value) {
            _customReminderTimes.value = _customReminderTimes.value + time
            preferences.edit()
                .putString(KEY_CUSTOM_REMINDER_TIMES, _customReminderTimes.value.joinToString(REMINDER_TIME_SEPARATOR))
                .apply()
        }
        setReminderTimes((reminderTimes.value + time).sorted())
    }

    /** 移除自定义提醒时间点（取消勾选自定义 chip 时调用），固定档位不受影响。 */
    fun removeCustomReminderTime(time: String) {
        if (time !in _customReminderTimes.value) return
        _customReminderTimes.value = _customReminderTimes.value - time
        preferences.edit()
            .putString(KEY_CUSTOM_REMINDER_TIMES, _customReminderTimes.value.joinToString(REMINDER_TIME_SEPARATOR))
            .apply()
        setReminderTimes(reminderTimes.value - time)
    }

    fun setMealPrepDayShift(dayShift: Int) {
        preferences.edit().putInt(KEY_MEAL_PREP_DAY_SHIFT, dayShift).apply()
        _mealPrepDayShift.value = dayShift
    }

    fun setMealPrepFireTime(time: String) {
        if (!time.matches(TIME_FORMAT)) return
        preferences.edit().putString(KEY_MEAL_PREP_FIRE_TIME, time).apply()
        _mealPrepFireTime.value = time
    }

    fun setLastSyncAt(timestamp: Long) {
        preferences.edit().putLong(KEY_LAST_SYNC_AT, timestamp).apply()
        _lastSyncAt.value = timestamp
    }

    fun setSearchHistory(history: List<String>) {
        _searchHistory.value = history
        preferences.edit().putString(KEY_SEARCH_HISTORY, history.joinToString(SEARCH_SEPARATOR)).apply()
    }

    private fun loadSearchHistory(): List<String> =
        preferences.getString(KEY_SEARCH_HISTORY, null)
            ?.split(SEARCH_SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private val recipeJson = Json { ignoreUnknownKeys = true }

    private val _weeklyMenu = MutableStateFlow(loadWeeklyMenu())

    /** 未来一周菜单：日期（yyyy-MM-dd）→ 一餐搭配（主食/蛋白/蔬菜/汤饮）。 */
    val weeklyMenu: StateFlow<Map<String, MealSpec>> = _weeklyMenu.asStateFlow()

    /** 覆盖整周菜单（用于按天分配、手动编辑或「换一道」后持久化）。 */
    fun setWeeklyMenu(menu: Map<String, MealSpec>) {
        preferences.edit().putString(KEY_WEEKLY_MENU, recipeJson.encodeToString(menu)).apply()
        _weeklyMenu.value = menu
    }

    /** 读取整周菜单；兼容旧版「日期 → 单个菜谱 id」的存储格式。 */
    private fun loadWeeklyMenu(): Map<String, MealSpec> {
        // 默认搭配规则调整（主食固定米饭+白粥、默认不配汤饮）后，旧菜单里可能仍带着旧搭配，
        // 清空一次让新默认重新生成；已「做了」的记录保留。
        if (preferences.getInt(KEY_MEAL_PLAN_VERSION, 0) < MEAL_PLAN_VERSION) {
            preferences.edit()
                .putInt(KEY_MEAL_PLAN_VERSION, MEAL_PLAN_VERSION)
                .remove(KEY_WEEKLY_MENU)
                .apply()
            return emptyMap()
        }
        val stored = preferences.getString(KEY_WEEKLY_MENU, null) ?: return emptyMap()
        runCatching { recipeJson.decodeFromString<Map<String, MealSpec>>(stored) }
            .getOrNull()
            ?.let { return it }
        return runCatching { recipeJson.decodeFromString<Map<String, Long>>(stored) }
            .getOrNull()
            ?.mapValues { (_, id) ->
                MealSpec(listOf(MealDishSpec(DishRole.PROTEIN.name, id)))
            }
            ?: emptyMap()
    }

    private val _cookedMenuDates = MutableStateFlow(loadCookedMenuDates())

    /** 一周菜单里已经点过「做了」的日期集合。 */
    val cookedMenuDates: StateFlow<Set<String>> = _cookedMenuDates.asStateFlow()

    /** 记录某天已经做过（该天卡片改显示「已做」，不会重复扣料）。 */
    fun markMenuCooked(dateKey: String) {
        if (dateKey.isBlank()) return
        val updated = _cookedMenuDates.value + dateKey
        preferences.edit()
            .putString(KEY_COOKED_MENU_DATES, updated.joinToString(SEARCH_SEPARATOR))
            .apply()
        _cookedMenuDates.value = updated
    }

    private fun loadCookedMenuDates(): Set<String> =
        preferences.getString(KEY_COOKED_MENU_DATES, null)
            ?.split(SEARCH_SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    private val _customStatuses = MutableStateFlow(loadCustomStatuses())

    /** 用户自定义的物品状态（两个维度混存，按添加顺序排列），消费方按维度筛选。 */
    val customStatuses: StateFlow<List<ItemStatusOption>> = _customStatuses.asStateFlow()

    /**
     * 新增一个自定义状态，同一维度内名称重复（与内置或已有自定义同名）时返回 false。
     * 编号自该维度的起始编号起单调递增，删除后也不复用，
     * 避免历史物品的旧编号被新状态顶替导致展示错乱。
     */
    fun addCustomStatus(label: String, dimension: StatusDimension): Boolean {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return false
        val taken = ItemStatusCatalog.builtInFor(dimension).any { it.label == trimmed } ||
            _customStatuses.value.any { it.dimension == dimension && it.label == trimmed }
        if (taken) return false

        val nextCode = preferences.getInt(
            nextCodeKey(dimension),
            initialNextCode(dimension)
        )
        val updated = _customStatuses.value + ItemStatusOption(
            code = nextCode,
            label = trimmed,
            dimension = dimension,
            isBuiltIn = false
        )
        preferences.edit()
            .putString(KEY_CUSTOM_STATUSES, encodeCustomStatuses(updated))
            .putInt(nextCodeKey(dimension), nextCode + 1)
            .apply()
        _customStatuses.value = updated
        return true
    }

    /** 删除一个自定义状态；已使用该状态的物品会退化为「状态 N」的兜底文案。 */
    fun removeCustomStatus(code: Int) {
        val updated = _customStatuses.value.filterNot { it.code == code }
        if (updated.size == _customStatuses.value.size) return
        preferences.edit().putString(KEY_CUSTOM_STATUSES, encodeCustomStatuses(updated)).apply()
        _customStatuses.value = updated
    }

    /**
     * 重命名一个自定义状态，编号保持不变，已挂在该状态上的物品文案会跟着变。
     * 同一维度内与内置或其它自定义重名时返回 false。
     */
    fun updateCustomStatus(code: Int, label: String): Boolean {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return false
        val target = _customStatuses.value.firstOrNull { it.code == code } ?: return false
        val taken = ItemStatusCatalog.builtInFor(target.dimension).any { it.label == trimmed } ||
            _customStatuses.value.any {
                it.code != code && it.dimension == target.dimension && it.label == trimmed
            }
        if (taken) return false

        val updated = _customStatuses.value.map {
            if (it.code == code) it.copy(label = trimmed) else it
        }
        preferences.edit().putString(KEY_CUSTOM_STATUSES, encodeCustomStatuses(updated)).apply()
        _customStatuses.value = updated
        return true
    }

    /**
     * 解析已存的自定义状态。旧格式只有 `编号|名称` 两段，
     * 统一迁入「使用进度」维度（旧的自定义编号段与使用进度自定义段一致）。
     */
    private fun loadCustomStatuses(): List<ItemStatusOption> =
        preferences.getString(KEY_CUSTOM_STATUSES, null)
            ?.split(STATUS_SEPARATOR)
            ?.mapNotNull { row ->
                val parts = row.split(STATUS_FIELD_SEPARATOR)
                val code = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val dimension = parts.getOrNull(1)
                    ?.let { stored -> runCatching { StatusDimension.valueOf(stored) }.getOrNull() }
                    ?: StatusDimension.USAGE
                val labelIndex = if (parts.size >= 3) 2 else 1
                val label = parts.getOrNull(labelIndex)?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ItemStatusOption(
                    code = code,
                    label = label,
                    dimension = dimension,
                    isBuiltIn = false
                )
            }
            ?: emptyList()

    private fun encodeCustomStatuses(options: List<ItemStatusOption>): String =
        options.joinToString(STATUS_SEPARATOR) {
            "${it.code}$STATUS_FIELD_SEPARATOR${it.dimension.name}" +
                "$STATUS_FIELD_SEPARATOR${it.label}"
        }

    private fun nextCodeKey(dimension: StatusDimension): String = when (dimension) {
        StatusDimension.USAGE -> KEY_NEXT_CUSTOM_USAGE_CODE
        StatusDimension.DISPOSITION -> KEY_NEXT_CUSTOM_DISPOSITION_CODE
    }

    /** 使用进度沿用旧的自增计数器，保证升级后编号不倒退。 */
    private fun initialNextCode(dimension: StatusDimension): Int = when (dimension) {
        StatusDimension.USAGE -> preferences.getInt(
            KEY_NEXT_CUSTOM_USAGE_CODE,
            preferences.getInt(
                KEY_NEXT_CUSTOM_STATUS_CODE,
                ItemStatusCatalog.CUSTOM_USAGE_BASE
            )
        )
        StatusDimension.DISPOSITION -> preferences.getInt(
            KEY_NEXT_CUSTOM_DISPOSITION_CODE,
            ItemStatusCatalog.CUSTOM_DISPOSITION_BASE
        )
    }

    private val _recipes = MutableStateFlow(loadRecipes())

    /** 用户菜谱库（内置 + 自建），「今天吃什么」与菜谱页共用这一份。 */
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    /** 新增一道菜谱；名称为空时返回 false。 */
    fun addRecipe(name: String, ingredients: List<RecipeIngredient>, role: String = ""): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val nextId = preferences.getInt(KEY_NEXT_RECIPE_ID, RECIPE_ID_BASE)
        val updated = _recipes.value + Recipe(
            id = nextId.toLong(),
            name = trimmed,
            ingredients = normalizeIngredients(ingredients),
            role = role
        )
        preferences.edit()
            .putString(KEY_RECIPES, encodeRecipes(updated))
            .putInt(KEY_NEXT_RECIPE_ID, nextId + 1)
            .apply()
        _recipes.value = updated
        return true
    }

    /** 修改一道菜谱；名称为空或菜谱不存在时返回 false。 */
    fun updateRecipe(
        id: Long,
        name: String,
        ingredients: List<RecipeIngredient>,
        role: String = ""
    ): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        if (_recipes.value.none { it.id == id }) return false
        val updated = _recipes.value.map {
            if (it.id == id) {
                it.copy(name = trimmed, ingredients = normalizeIngredients(ingredients), role = role)
            } else {
                it
            }
        }
        preferences.edit().putString(KEY_RECIPES, encodeRecipes(updated)).apply()
        _recipes.value = updated
        return true
    }

    /** 删除一道菜谱。 */
    fun removeRecipe(id: Long) {
        val updated = _recipes.value.filterNot { it.id == id }
        if (updated.size == _recipes.value.size) return
        preferences.edit().putString(KEY_RECIPES, encodeRecipes(updated)).apply()
        _recipes.value = updated
    }

    /**
     * 首次启动种入内置菜谱；升级时把本次新增的内置菜谱补进来（按编号增量合并），
     * 已存在或被用户删掉的旧菜谱不会重复追加，之后仍以用户编辑过的内容为准。
     */
    private fun loadRecipes(): List<Recipe> {
        val stored = preferences.getString(KEY_RECIPES, null)
        if (stored == null) {
            preferences.edit()
                .putString(KEY_RECIPES, encodeRecipes(DEFAULT_RECIPES))
                .putInt(KEY_RECIPE_SEED_VERSION, BUILT_IN_RECIPE_MAX_ID.toInt())
                .putInt(KEY_RECIPE_CLEANUP_VERSION, RECIPE_CLEANUP_VERSION)
                .apply()
            return DEFAULT_RECIPES
        }
        var result = runCatching { recipeJson.decodeFromString<List<Recipe>>(stored) }
            .getOrElse { return DEFAULT_RECIPES }
        var changed = false

        // 一次性下架已移除的内置菜谱（按编号，用户自建从 100 起不受影响）。
        if (preferences.getInt(KEY_RECIPE_CLEANUP_VERSION, 0) < RECIPE_CLEANUP_VERSION) {
            val filtered = result.filterNot { it.id in REMOVED_BUILT_IN_RECIPE_IDS }
            if (filtered.size != result.size) {
                result = filtered
                changed = true
            }
            preferences.edit().putInt(KEY_RECIPE_CLEANUP_VERSION, RECIPE_CLEANUP_VERSION).apply()
        }

        // 一次性刷新指定内置菜谱的定义（食材标注等基础规则调整）：
        // 只覆盖仍存在的内置菜谱，已被用户删除或自建的菜谱不受影响。
        if (preferences.getInt(KEY_RECIPE_REFRESH_VERSION, 0) < RECIPE_REFRESH_VERSION) {
            val refreshedDefs = DEFAULT_RECIPES
                .filter { it.id in REFRESHED_BUILT_IN_RECIPE_IDS }
                .associateBy { it.id }
            val before = result
            result = result.map { refreshedDefs[it.id] ?: it }
            if (result != before) changed = true
            preferences.edit().putInt(KEY_RECIPE_REFRESH_VERSION, RECIPE_REFRESH_VERSION).apply()
        }

        // 增量补入新增的内置菜谱：编号大于已种入水位线的才补，避免把用户删掉的旧菜谱又加回来。
        val seededVersion = preferences.getInt(KEY_RECIPE_SEED_VERSION, 0)
        if (seededVersion < BUILT_IN_RECIPE_MAX_ID) {
            val existingIds = result.map { it.id }.toSet()
            val added = DEFAULT_RECIPES.filter {
                it.id > seededVersion && it.id !in existingIds
            }
            if (added.isNotEmpty()) {
                result = result + added
                changed = true
            }
            preferences.edit().putInt(KEY_RECIPE_SEED_VERSION, BUILT_IN_RECIPE_MAX_ID.toInt()).apply()
        }

        if (changed) {
            preferences.edit().putString(KEY_RECIPES, encodeRecipes(result)).apply()
        }
        return result
    }

    private val _mealPlanDays = MutableStateFlow(
        preferences.getInt(KEY_MEAL_PLAN_DAYS, DEFAULT_MEAL_PLAN_DAYS)
            .coerceIn(MIN_MEAL_PLAN_DAYS, MAX_MEAL_PLAN_DAYS)
    )

    /** 「未来 N 天菜谱」要展示的天数，可由用户自定义（默认一周）。 */
    val mealPlanDays: StateFlow<Int> = _mealPlanDays.asStateFlow()

    fun setMealPlanDays(days: Int) {
        val normalized = days.coerceIn(MIN_MEAL_PLAN_DAYS, MAX_MEAL_PLAN_DAYS)
        preferences.edit().putInt(KEY_MEAL_PLAN_DAYS, normalized).apply()
        _mealPlanDays.value = normalized
    }

    private fun encodeRecipes(recipes: List<Recipe>): String = recipeJson.encodeToString(recipes)

    /** 去掉空白食材并清理首尾空格，保证展示与匹配都干净。 */
    private fun normalizeIngredients(ingredients: List<RecipeIngredient>): List<RecipeIngredient> =
        ingredients.mapNotNull { ingredient ->
            val label = ingredient.label.trim()
            if (label.isEmpty()) {
                null
            } else {
                ingredient.copy(
                    label = label,
                    keywords = ingredient.keywords.map { it.trim() }.filter { it.isNotEmpty() }
                )
            }
        }

    /** 最近一次已发送到期汇总提醒的去重键（yyyy-MM-dd 或 yyyy-MM-dd@HH:mm），用于按天/按时间点去重。 */
    fun expiryNotifiedDate(): String? = preferences.getString(KEY_EXPIRY_NOTIFIED_DATE, null)

    fun setExpiryNotifiedDate(date: String) {
        preferences.edit().putString(KEY_EXPIRY_NOTIFIED_DATE, date).apply()
    }

    /**
     * 拉取水位线：上次成功导入某个同伴数据时，对方备份里的 exportedAt。
     * 下次只向对方索取 updatedAt 晚于它的记录，避免每次全量传输。
     */
    fun pullWatermark(peerId: String): Long? =
        preferences.getLong(pullKey(peerId), NO_WATERMARK).takeIf { it != NO_WATERMARK }

    /**
     * 推送水位线：上次成功把本机数据推给某个同伴时的本机时间。
     * 下次只向对方推送晚于它的变更。
     */
    fun pushWatermark(peerId: String): Long? =
        preferences.getLong(pushKey(peerId), NO_WATERMARK).takeIf { it != NO_WATERMARK }

    fun setPullWatermark(peerId: String, value: Long) {
        preferences.edit().putLong(pullKey(peerId), value).apply()
    }

    fun setPushWatermark(peerId: String, value: Long) {
        preferences.edit().putLong(pushKey(peerId), value).apply()
    }

    private fun pullKey(peerId: String) = "$KEY_SYNC_PULL_PREFIX$peerId"

    private fun pushKey(peerId: String) = "$KEY_SYNC_PUSH_PREFIX$peerId"

    companion object {
        const val DEFAULT_REMINDER_DAYS = 7
        const val MIN_REMINDER_DAYS = 1
        const val MAX_REMINDER_DAYS = 90

        /** 默认的全局提醒阶梯（天）。 */
        val DEFAULT_REMINDER_LADDER = listOf(1, 3, 7, 14, 30)

        /** 提醒阶梯的候选档位，设置页与物品编辑页共用。 */
        val REMINDER_LADDER_OPTIONS = listOf(1, 3, 7, 14, 30, 60, 90)

        /** 默认的每日提醒时间点：早上 8:00、晚上 19:00。 */
        val DEFAULT_REMINDER_TIMES = listOf("08:00", "19:00")

        /** 提醒时间点的候选档位（HH:mm），设置页与调度器共用。 */
        val REMINDER_TIME_OPTIONS = listOf(
            "07:00", "08:00", "09:00", "12:00", "18:00", "19:00", "20:00", "21:00"
        )

        const val DAY_MS = 24 * 60 * 60 * 1000L

        /** 水位线缺失时的哨兵值。 */
        private const val NO_WATERMARK = Long.MIN_VALUE

        /** 从未同步过的哨兵值。 */
        private const val NO_SYNC_AT = Long.MIN_VALUE

        /** 搜索历史的分隔符：用换行避免与查询词冲突。 */
        private const val SEARCH_SEPARATOR = "\n"

        /** 自定义状态的分隔符：整体按换行分行，每行内以竖线分隔编号与名称。 */
        private const val STATUS_SEPARATOR = "\n"
        private const val STATUS_FIELD_SEPARATOR = "|"

        private const val PREFS_NAME = "lemon_preferences"
        private const val KEY_REMINDER_DAYS = "reminder_days"
        private const val KEY_REMINDER_LADDER = "reminder_ladder"
        private const val REMINDER_LADDER_SEPARATOR = ","
        private const val KEY_REMINDER_TIMES = "reminder_times"
        private const val REMINDER_TIME_SEPARATOR = ","
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CONTINUOUS_ENTRY = "continuous_entry"
        private const val KEY_KEEP_ALIVE_ENABLED = "keep_alive_enabled"
        private const val KEY_CUSTOM_REMINDER_TIMES = "custom_reminder_times"
        private const val KEY_MEAL_PREP_DAY_SHIFT = "meal_prep_day_shift"
        private const val KEY_MEAL_PREP_FIRE_TIME = "meal_prep_fire_time"

        /** HH:mm 时间格式（24 小时制），用于校验自定义时间输入。 */
        private val TIME_FORMAT = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_EXPIRY_NOTIFIED_DATE = "expiry_notified_date"
        private const val KEY_SEARCH_HISTORY = "search_history"
        private const val KEY_WEEKLY_MENU = "weekly_menu"
        private const val KEY_COOKED_MENU_DATES = "cooked_menu_dates"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_CUSTOM_STATUSES = "custom_statuses"
        private const val KEY_RECIPES = "recipes"
        private const val KEY_NEXT_RECIPE_ID = "next_recipe_id"
        private const val KEY_RECIPE_SEED_VERSION = "recipe_seed_version"
        private const val KEY_RECIPE_CLEANUP_VERSION = "recipe_cleanup_version"
        private const val KEY_RECIPE_REFRESH_VERSION = "recipe_refresh_version"
        private const val KEY_MEAL_PLAN_DAYS = "meal_plan_days"
        private const val KEY_MEAL_PLAN_VERSION = "meal_plan_version"

        /** 内置菜谱下架清理的版本号，每次下架内置菜谱时 +1。 */
        private const val RECIPE_CLEANUP_VERSION = 3

        /**
         * 已下架的内置菜谱编号：葱油拌面/土豆丝饼/南瓜饼（主食）、红烧鱼/清蒸鱼/土豆炖牛肉（蛋白）。
         * 另含 42（早期版本的「白粥」草稿，现由 44 取代）。仅按内置编号删除，用户自建菜谱从 100 起，不受影响。
         */
        private val REMOVED_BUILT_IN_RECIPE_IDS = setOf(13L, 14L, 17L, 31L, 33L, 34L, 42L)

        /** 内置菜谱定义刷新的版本号，内置菜谱的食材标注等基础规则调整时 +1。 */
        private const val RECIPE_REFRESH_VERSION = 1

        /**
         * 升级时用最新定义覆盖的内置菜谱编号（不复活已被用户删除的）：
         * v1 是「葱姜蒜」分类拆成葱/姜/蒜后，同步更新蒜蓉类菜的食材标注。
         */
        private val REFRESHED_BUILT_IN_RECIPE_IDS = setOf(3L, 7L, 21L)

        /** 菜单默认搭配规则的版本号，规则调整时 +1，会清空旧菜单让它按新规则重排。 */
        private const val MEAL_PLAN_VERSION = 1

        /** 用户新增菜谱的起始编号，避开内置菜谱占用的编号段。 */
        private const val RECIPE_ID_BASE = 100

        /** 「未来 N 天菜谱」的默认与边界天数（不再固定一周）。 */
        const val DEFAULT_MEAL_PLAN_DAYS = 7
        const val MIN_MEAL_PLAN_DAYS = 1
        const val MAX_MEAL_PLAN_DAYS = 30

        /** 旧版单一计数器，升级后仅作为「使用进度」计数器的初值来源。 */
        private const val KEY_NEXT_CUSTOM_STATUS_CODE = "next_custom_status_code"
        private const val KEY_NEXT_CUSTOM_USAGE_CODE = "next_custom_usage_code"
        private const val KEY_NEXT_CUSTOM_DISPOSITION_CODE = "next_custom_disposition_code"
        private const val KEY_SYNC_PULL_PREFIX = "sync_pull_"
        private const val KEY_SYNC_PUSH_PREFIX = "sync_push_"

        /**
         * 启动最早期的深色判定（Hilt 注入之前），用于选择启动主题，避免开屏闪白。
         * 手动固定时按固定值，跟随系统时读取系统 uiMode。
         */
        fun shouldStartDark(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return when (prefs.getString(KEY_THEME_MODE, null)) {
                ThemeMode.DARK.name -> true
                ThemeMode.LIGHT.name -> false
                else -> {
                    val nightMask = context.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK
                    nightMask == Configuration.UI_MODE_NIGHT_YES
                }
            }
        }
    }
}