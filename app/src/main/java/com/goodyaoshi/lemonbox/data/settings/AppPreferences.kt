package com.goodyaoshi.lemonbox.data.settings

import android.content.Context
import android.content.res.Configuration
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusCatalog
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusOption
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.data.meal.MealSpec
import com.goodyaoshi.lemonbox.data.meal.Recipe
import com.goodyaoshi.lemonbox.data.meal.RecipeIngredient
import com.goodyaoshi.lemonbox.data.ocr.OcrModelTier
import com.goodyaoshi.lemonbox.data.sync.SyncReport
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.MonotonicClock
import com.goodyaoshi.lemonbox.util.UserCopy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.LocalDate
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
 * 首页临期事项的当日忽略记录（H1）：key 集合只在记录当天有效，跨天自动失效。
 * key 与「今天的事」的 event.key 同构（如 "expiry:12"），避免两边各造一套标识。
 */
data class ExpiryIgnores(
    val date: String,
    val keys: Set<String>
) {
    /** 该 key 今天是否已被忽略；日期不是今天时一律视为未忽略。 */
    fun containsToday(key: String, today: String): Boolean =
        date == today && key in keys
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

    private val _nickname = MutableStateFlow(loadNickname())

    /** 她的昵称：首页问候、「我的」页与通知里的称呼都用它；可在「我的」页修改。 */
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    /** 更新昵称：去首尾空白并限长，留空则回落到默认昵称。 */
    fun setNickname(value: String) {
        val normalized = value.trim().take(MAX_NICKNAME_LENGTH).ifBlank { UserCopy.NICKNAME }
        preferences.edit().putString(KEY_NICKNAME, normalized).apply()
        _nickname.value = normalized
    }

    private fun loadNickname(): String =
        preferences.getString(KEY_NICKNAME, null)
            ?.trim()
            ?.take(MAX_NICKNAME_LENGTH)
            ?.ifBlank { null }
            ?: UserCopy.NICKNAME

    private val _togetherStartDate = MutableStateFlow(loadTogetherStartDate())

    /**
     * 「在一起」的起始日（yyyy-MM-dd）。设置后首页 Hero 用它算「第 N 天」；
     * 未设置（null）时首页回退到累计型纪念日派生，兼容老数据。
     */
    val togetherStartDate: StateFlow<String?> = _togetherStartDate.asStateFlow()

    private val _togetherUpdatedAt = MutableStateFlow(
        preferences.getLong(KEY_TOGETHER_UPDATED_AT, 0L)
    )

    /** 起始日最近一次修改的时间戳（毫秒）；多设备合并时按它「较新者胜」。 */
    val togetherUpdatedAt: StateFlow<Long> = _togetherUpdatedAt.asStateFlow()

    private val _togetherLabelName = MutableStateFlow(loadTogetherLabelName())

    /** 首页计数前缀（默认「我们」），拼成「我们第 N 天」；可在设置页修改。 */
    val togetherLabelName: StateFlow<String> = _togetherLabelName.asStateFlow()

    /** 设置起始日；传 null 表示清除。同时刷新 updatedAt，供多设备合并比较。 */
    fun setTogetherStartDate(date: String?) {
        persistTogetherStartDate(normalizeTogetherDate(date), MonotonicClock.now())
    }

    /** 设置首页计数前缀；留空回落到默认「我们」。 */
    fun setTogetherLabelName(value: String) {
        val normalized = value.trim().take(MAX_TOGETHER_LABEL_LENGTH).ifBlank { DEFAULT_TOGETHER_LABEL_NAME }
        preferences.edit().putString(KEY_TOGETHER_LABEL_NAME, normalized).apply()
        _togetherLabelName.value = normalized
    }

    private fun persistTogetherStartDate(date: String?, updatedAt: Long) {
        val editor = preferences.edit().putLong(KEY_TOGETHER_UPDATED_AT, updatedAt)
        if (date == null) {
            editor.remove(KEY_TOGETHER_START_DATE)
        } else {
            editor.putString(KEY_TOGETHER_START_DATE, date)
        }
        editor.apply()
        _togetherStartDate.value = date
        _togetherUpdatedAt.value = updatedAt
    }

    /** 归一化起始日：空白与非法日期一律视为未设置（null）。 */
    private fun normalizeTogetherDate(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { LocalDate.parse(it).toString() }.getOrNull() }

    private fun loadTogetherStartDate(): String? =
        normalizeTogetherDate(preferences.getString(KEY_TOGETHER_START_DATE, null))

    private fun loadTogetherLabelName(): String =
        preferences.getString(KEY_TOGETHER_LABEL_NAME, null)
            ?.trim()
            ?.take(MAX_TOGETHER_LABEL_LENGTH)
            ?.ifBlank { null }
            ?: DEFAULT_TOGETHER_LABEL_NAME

    private val _ocrModelTier = MutableStateFlow(
        OcrModelTier.fromId(preferences.getString(KEY_OCR_MODEL_TIER, null))
    )

    /** 识别模型档位（F01）：轻量版 / 精准版，默认轻量版。设备本地状态，不进备份。 */
    val ocrModelTier: StateFlow<OcrModelTier> = _ocrModelTier.asStateFlow()

    fun setOcrModelTier(tier: OcrModelTier) {
        preferences.edit().putString(KEY_OCR_MODEL_TIER, tier.id).apply()
        _ocrModelTier.value = tier
    }

    private val _budgetReminderEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_BUDGET_REMINDER_ENABLED, true)
    )

    /** 预算超支提醒开关：控制记账页/首页的超支提示条显隐，默认开。 */
    val budgetReminderEnabled: StateFlow<Boolean> = _budgetReminderEnabled.asStateFlow()

    fun setBudgetReminderEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_BUDGET_REMINDER_ENABLED, enabled).apply()
        _budgetReminderEnabled.value = enabled
    }

    private val _ledgerMonthStartDay = MutableStateFlow(
        preferences.getInt(KEY_LEDGER_MONTH_START_DAY, 1).coerceIn(1, 28)
    )

    /**
     * 记账月起始日（1-28），默认 1 即自然月。
     * 在记账页的「账期」入口可调；7 个账期窗口计算（统计/预算/趋势/年度回顾等）共用这一处。
     */
    val ledgerMonthStartDay: StateFlow<Int> = _ledgerMonthStartDay.asStateFlow()

    fun setLedgerMonthStartDay(day: Int) {
        val normalized = day.coerceIn(1, 28)
        preferences.edit().putInt(KEY_LEDGER_MONTH_START_DAY, normalized).apply()
        _ledgerMonthStartDay.value = normalized
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

    private val _bedtimeReminderEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_BEDTIME_REMINDER_ENABLED, false)
    )

    /** 就寝提醒开关（F15）：开启后每晚到点提醒该休息了。默认关闭。 */
    val bedtimeReminderEnabled: StateFlow<Boolean> = _bedtimeReminderEnabled.asStateFlow()

    private val _bedtimeTime = MutableStateFlow(
        normalizeBedtimeTime(preferences.getString(KEY_BEDTIME_TIME, null)) ?: DEFAULT_BEDTIME_TIME
    )

    /** 就寝时间（HH:mm），默认 22:30。 */
    val bedtimeTime: StateFlow<String> = _bedtimeTime.asStateFlow()

    fun setBedtimeReminderEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_BEDTIME_REMINDER_ENABLED, enabled).apply()
        _bedtimeReminderEnabled.value = enabled
    }

    fun setBedtimeTime(time: String) {
        val normalized = normalizeBedtimeTime(time) ?: return
        preferences.edit().putString(KEY_BEDTIME_TIME, normalized).apply()
        _bedtimeTime.value = normalized
    }

    /** 就寝提醒去重键（yyyy-MM-dd@HH:mm）；设备本地状态，不进备份。 */
    fun bedtimeNotifiedDate(): String? = preferences.getString(KEY_BEDTIME_NOTIFIED_DATE, null)

    fun setBedtimeNotifiedDate(value: String) {
        preferences.edit().putString(KEY_BEDTIME_NOTIFIED_DATE, value).apply()
    }

    private fun normalizeBedtimeTime(raw: String?): String? {
        val parts = raw?.trim()?.split(":") ?: return null
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return "%02d:%02d".format(hour, minute)
    }

    private val _appLockEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_APP_LOCK_ENABLED, false)
    )

    /** 应用锁开关（F19）：开启后每次进入需验证 PIN 或系统密码/指纹。默认关闭。 */
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    private val _appLockPinHash = MutableStateFlow(
        preferences.getString(KEY_APP_LOCK_PIN_HASH, null)
    )

    /** 应用锁 PIN 的哈希（不存明文）；null 表示尚未设置 PIN。 */
    val appLockPinHash: StateFlow<String?> = _appLockPinHash.asStateFlow()

    fun setAppLockEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply()
        _appLockEnabled.value = enabled
    }

    /** 设置应用锁 PIN：只存哈希，明文不落盘。 */
    fun setAppLockPin(pin: String) {
        val hash = hashPin(pin)
        preferences.edit().putString(KEY_APP_LOCK_PIN_HASH, hash).apply()
        _appLockPinHash.value = hash
    }

    /** 校验 PIN 是否与已存哈希一致。 */
    fun verifyAppLockPin(pin: String): Boolean =
        _appLockPinHash.value?.let { it == hashPin(pin) } ?: false

    private val _continuousEntry = MutableStateFlow(
        preferences.getBoolean(KEY_CONTINUOUS_ENTRY, false)
    )

    /** 连续录入：开启后保存成功会清空表单并自动唤起相机，方便一次录多件。默认关闭。 */
    val continuousEntry: StateFlow<Boolean> = _continuousEntry.asStateFlow()

    private val _editorAdvancedExpanded = MutableStateFlow(
        preferences.getBoolean(KEY_EDITOR_ADVANCED_EXPANDED, false)
    )

    /**
     * 物品录入 / 编辑页「更多信息（选填）」是否展开。
     * 默认折叠，把首屏留给名称、分类、位置这些高频字段；用户手动展开后记住其选择（I6），
     * 下次进来沿用，避免每次都要重新点开。
     */
    val editorAdvancedExpanded: StateFlow<Boolean> = _editorAdvancedExpanded.asStateFlow()

    fun setEditorAdvancedExpanded(expanded: Boolean) {
        preferences.edit().putBoolean(KEY_EDITOR_ADVANCED_EXPANDED, expanded).apply()
        _editorAdvancedExpanded.value = expanded
    }

    private val _expiryQuickOptions = MutableStateFlow(loadExpiryQuickOptions())

    /**
     * 家当录入页「有效期」的快捷选项（编码列表，形如 7d/2w/3m/1y），
     * 可由用户在设置页自定义常用档位。默认 [DateUtil.DEFAULT_EXPIRY_QUICK_CODES]。
     */
    val expiryQuickOptions: StateFlow<List<String>> = _expiryQuickOptions.asStateFlow()

    fun setExpiryQuickOptions(codes: List<String>) {
        val normalized = DateUtil.normalizeExpiryQuickCodes(codes)
        preferences.edit()
            .putString(KEY_EXPIRY_QUICK_OPTIONS, normalized.joinToString(EXPIRY_QUICK_SEPARATOR))
            .apply()
        _expiryQuickOptions.value = normalized
    }

    private fun loadExpiryQuickOptions(): List<String> {
        val stored = preferences.getString(KEY_EXPIRY_QUICK_OPTIONS, null)
            ?: return DateUtil.DEFAULT_EXPIRY_QUICK_CODES
        return DateUtil.normalizeExpiryQuickCodes(stored.split(EXPIRY_QUICK_SEPARATOR))
            .ifEmpty { DateUtil.DEFAULT_EXPIRY_QUICK_CODES }
    }

    private val _keepAliveEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_KEEP_ALIVE_ENABLED, true)
    )

    /** 提醒保活：常驻前台服务防止划卡清理把提醒闹钟一起杀掉。默认开启。 */
    val keepAliveEnabled: StateFlow<Boolean> = _keepAliveEnabled.asStateFlow()

    private val _autoStartConfirmed = MutableStateFlow(
        preferences.getBoolean(KEY_AUTO_START_CONFIRMED, false)
    )

    /**
     * 是否已前往系统「自启动」页设置过。
     *
     * 厂商自启动开关没有公开 API 可读取，无法真正探测；这里记录「用户已去过设置页并确认」
     * 这一事实，避免像以前那样永远显示「去设置」，同时文案如实说明是用户自述而非系统探测。
     */
    val autoStartConfirmed: StateFlow<Boolean> = _autoStartConfirmed.asStateFlow()

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

    // ---- H1：首页临期事项「今天忽略」----
    private val _expiryIgnores = MutableStateFlow(loadExpiryIgnores())

    /** 首页临期事项的当日忽略记录；读取时已按日期失效，跨天自动清空。 */
    val expiryIgnores: StateFlow<ExpiryIgnores> = _expiryIgnores.asStateFlow()

    /** 「今天忽略」一条临期事项：只影响今天，明天它会照常回来。 */
    fun ignoreExpiryForToday(key: String) {
        val today = LocalDate.now().toString()
        val keys = if (_expiryIgnores.value.date == today) {
            _expiryIgnores.value.keys + key
        } else {
            setOf(key)
        }
        saveExpiryIgnores(ExpiryIgnores(today, keys))
    }

    /** 撤销今天的忽略（Snackbar 撤销用）。 */
    fun undoExpiryIgnore(key: String) {
        val today = LocalDate.now().toString()
        if (_expiryIgnores.value.date != today) return
        saveExpiryIgnores(ExpiryIgnores(today, _expiryIgnores.value.keys - key))
    }

    private fun loadExpiryIgnores(): ExpiryIgnores {
        val storedDate = preferences.getString(KEY_EXPIRY_IGNORED_DATE, null)
            ?: return ExpiryIgnores("", emptySet())
        // 跨天后旧记录自然失效：读取时就判日期，不匹配直接给空。
        if (storedDate != LocalDate.now().toString()) return ExpiryIgnores("", emptySet())
        return ExpiryIgnores(
            date = storedDate,
            keys = preferences.getStringSet(KEY_EXPIRY_IGNORED_KEYS, emptySet()).orEmpty()
        )
    }

    private fun saveExpiryIgnores(value: ExpiryIgnores) {
        _expiryIgnores.value = value
        preferences.edit()
            .putString(KEY_EXPIRY_IGNORED_DATE, value.date.ifEmpty { null })
            .putStringSet(KEY_EXPIRY_IGNORED_KEYS, value.keys)
            .apply()
    }

    // ---- SE1：用户自定义筛选预设 ----
    private val _userFilterPresets = MutableStateFlow(loadUserFilterPresets())

    /**
     * 用户自定义筛选预设（SE1）：UI 层序列化好的 JSON 串集合，这里只存不解析，
     * 避免数据层依赖 UI 的筛选模型。
     */
    val userFilterPresets: StateFlow<Set<String>> = _userFilterPresets.asStateFlow()

    fun setUserFilterPresets(presets: Set<String>) {
        preferences.edit().putStringSet(KEY_USER_FILTER_PRESETS, presets).apply()
        _userFilterPresets.value = presets
    }

    private fun loadUserFilterPresets(): Set<String> =
        preferences.getStringSet(KEY_USER_FILTER_PRESETS, emptySet()).orEmpty()

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

    fun setAutoStartConfirmed(confirmed: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_START_CONFIRMED, confirmed).apply()
        _autoStartConfirmed.value = confirmed
    }

    private val _lastMaintenanceAt = MutableStateFlow(
        preferences.getLong(KEY_LAST_MAINTENANCE_AT, 0L)
    )

    /**
     * 上次执行「后台维护」（去重 / 清理墓碑）的时间戳（毫秒）。
     * 冷启动时据此判断是否已经算过，避免每次打开都重复跑这些重活。
     */
    val lastMaintenanceAt: StateFlow<Long> = _lastMaintenanceAt.asStateFlow()

    fun setLastMaintenanceAt(timestamp: Long) {
        preferences.edit().putLong(KEY_LAST_MAINTENANCE_AT, timestamp).apply()
        _lastMaintenanceAt.value = timestamp
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

    private val _syncReports = MutableStateFlow(loadSyncReports())

    /** 最近几次局域网同步的回执（S5）：新的在前，最多保留 [MAX_SYNC_REPORTS] 条。 */
    val syncReports: StateFlow<List<SyncReport>> = _syncReports.asStateFlow()

    /** 记一条同步回执：prepend 后截断到上限，避免历史无限增长。 */
    fun recordSyncReport(report: SyncReport) {
        val updated = (listOf(report) + _syncReports.value).take(MAX_SYNC_REPORTS)
        _syncReports.value = updated
        preferences.edit()
            .putString(
                KEY_SYNC_REPORTS,
                syncReportsJson.encodeToString(ListSerializer(SyncReport.serializer()), updated)
            )
            .apply()
    }

    private fun loadSyncReports(): List<SyncReport> =
        preferences.getString(KEY_SYNC_REPORTS, null)?.let { raw ->
            runCatching {
                syncReportsJson.decodeFromString(ListSerializer(SyncReport.serializer()), raw)
            }.getOrDefault(emptyList())
        } ?: emptyList()

    /** 同步历史的 JSON 编解码配置：容忍旧版本多出的字段。 */
    private val syncReportsJson = Json { ignoreUnknownKeys = true }

    private val _weeklyMenu = MutableStateFlow(loadWeeklyMenu())

    /** 未来一周菜单：日期（yyyy-MM-dd）→ 一餐搭配（主食/蛋白/蔬菜/汤饮）。 */
    val weeklyMenu: StateFlow<Map<String, MealSpec>> = _weeklyMenu.asStateFlow()

    /** 覆盖整周菜单（用于按天分配、手动编辑或「换一道」后持久化）。 */
    fun setWeeklyMenu(menu: Map<String, MealSpec>) {
        preferences.edit()
            .putString(KEY_WEEKLY_MENU, StructuredPreferenceMigrations.encodeWeeklyMenu(menu))
            .apply()
        _weeklyMenu.value = menu
    }

    /**
     * 读取整周菜单：编解码与旧格式兼容统一走 [StructuredPreferenceMigrations]，
     * 这里只负责「是否需要按新搭配规则重置」与读写。
     */
    private fun loadWeeklyMenu(): Map<String, MealSpec> {
        // 默认搭配规则调整（主食固定米饭+白粥、默认不配汤饮）后，旧菜单里可能仍带着旧搭配，
        // 清空一次让新默认重新生成；已「做了」的记录保留。
        val storedVersion = preferences.getInt(KEY_MEAL_PLAN_VERSION, 0)
        if (StructuredPreferenceMigrations.shouldResetWeeklyMenu(storedVersion)) {
            preferences.edit()
                .putInt(KEY_MEAL_PLAN_VERSION, StructuredPreferenceMigrations.MEAL_PLAN_VERSION)
                .remove(KEY_WEEKLY_MENU)
                .apply()
            return emptyMap()
        }
        val stored = preferences.getString(KEY_WEEKLY_MENU, null) ?: return emptyMap()
        return StructuredPreferenceMigrations.decodeWeeklyMenu(stored)
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

    /** 撤销「做了」（GAP-12）：把某天从已做集合里移除，配合库存快照一起回滚。 */
    fun unmarkMenuCooked(dateKey: String) {
        if (dateKey.isBlank() || dateKey !in _cookedMenuDates.value) return
        val updated = _cookedMenuDates.value - dateKey
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
            role = role,
            syncId = UUID.randomUUID().toString()
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
     * 读取菜谱库：首次启动种入内置菜谱，升级时按水位线做下架清理 / 定义刷新 / 增量补种。
     * 具体迁移规则集中在 [StructuredPreferenceMigrations]，这里只负责读取水位线、调用迁移并回写。
     */
    private fun loadRecipes(): List<Recipe> {
        val result = StructuredPreferenceMigrations.migrateRecipes(
            StructuredPreferenceMigrations.RecipeState(
                storedJson = preferences.getString(KEY_RECIPES, null),
                cleanupVersion = preferences.getInt(KEY_RECIPE_CLEANUP_VERSION, 0),
                refreshVersion = preferences.getInt(KEY_RECIPE_REFRESH_VERSION, 0),
                seedVersion = preferences.getInt(KEY_RECIPE_SEED_VERSION, 0)
            )
        )
        if (result.writeRecipes || result.writeWatermarks) {
            val editor = preferences.edit()
            if (result.writeRecipes) {
                editor.putString(KEY_RECIPES, encodeRecipes(result.recipes))
            }
            if (result.writeWatermarks) {
                editor.putInt(KEY_RECIPE_CLEANUP_VERSION, result.cleanupVersion)
                editor.putInt(KEY_RECIPE_REFRESH_VERSION, result.refreshVersion)
                editor.putInt(KEY_RECIPE_SEED_VERSION, result.seedVersion)
            }
            editor.apply()
        }
        return result.recipes
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

    private fun encodeRecipes(recipes: List<Recipe>): String =
        StructuredPreferenceMigrations.encodeRecipes(recipes)

    // ---- 备份/同步：偏好内容的导出与合并导入 ----

    /** 菜谱库快照（内置 + 自建），备份导出用。 */
    fun recipesSnapshot(): List<Recipe> = _recipes.value

    /**
     * 合并导入对方菜谱：
     * - 内置菜谱（编号 < [RECIPE_ID_BASE]）按编号跳过——本地已有同号内置定义，不覆盖；
     * - 用户自建按 syncId 对齐，对方没带 syncId 的退回按编号；冲突时保留本地编辑；
     * - 新菜谱原样加入；本地自增计数器顶到最大编号 +1，避免后续新增撞号。
     */
    fun applySyncedRecipes(incoming: List<Recipe>): Int {
        if (incoming.isEmpty()) return 0
        val result = _recipes.value.toMutableList()
        var added = 0
        incoming.forEach { recipe ->
            if (recipe.id < RECIPE_ID_BASE) return@forEach
            val exists = recipe.syncId?.takeIf { it.isNotBlank() }?.let { syncId ->
                result.any { it.syncId == syncId || it.id == recipe.id }
            } ?: (result.any { it.id == recipe.id })
            if (exists) return@forEach
            result += recipe
            added++
        }
        if (added == 0) return 0
        val maxId = result.maxOf { it.id }.coerceAtLeast(RECIPE_ID_BASE - 1L)
        preferences.edit()
            .putString(KEY_RECIPES, encodeRecipes(result))
            .putInt(KEY_NEXT_RECIPE_ID, (preferences.getInt(KEY_NEXT_RECIPE_ID, RECIPE_ID_BASE)).coerceAtLeast(maxId.toInt() + 1))
            .apply()
        _recipes.value = result
        return added
    }

    /** 周菜单快照，备份导出用。 */
    fun weeklyMenuSnapshot(): Map<String, MealSpec> = _weeklyMenu.value

    /** 合并导入周菜单：同日期以备份为准，本地独有日期保留。 */
    fun applySyncedWeeklyMenu(menu: Map<String, MealSpec>): Int {
        if (menu.isEmpty()) return 0
        val merged = _weeklyMenu.value + menu
        if (merged == _weeklyMenu.value) return 0
        setWeeklyMenu(merged)
        return menu.size
    }

    /** 已做日期快照，备份导出用。 */
    fun cookedMenuDatesSnapshot(): Set<String> = _cookedMenuDates.value

    /** 合并导入已做日期：取并集。 */
    fun applySyncedCookedDates(dates: Set<String>): Int {
        val fresh = dates - _cookedMenuDates.value
        if (fresh.isEmpty()) return 0
        val updated = _cookedMenuDates.value + fresh
        preferences.edit()
            .putString(KEY_COOKED_MENU_DATES, updated.joinToString(SEARCH_SEPARATOR))
            .apply()
        _cookedMenuDates.value = updated
        return fresh.size
    }

    /** 自定义状态快照，备份导出用。 */
    fun customStatusesSnapshot(): List<ItemStatusOption> = _customStatuses.value

    /**
     * 合并导入自定义状态：同一维度按名称去重后追加，保留对方编号；
     * 本地编号计数器顶到对方最大编号 +1，避免后续新增撞号。
     */
    fun applySyncedCustomStatuses(options: List<ItemStatusOption>): Int {
        val fresh = options.filter { incoming ->
            _customStatuses.value.none {
                it.dimension == incoming.dimension && it.label == incoming.label
            }
        }
        if (fresh.isEmpty()) return 0
        val updated = _customStatuses.value + fresh
        preferences.edit().putString(KEY_CUSTOM_STATUSES, encodeCustomStatuses(updated)).apply()
        _customStatuses.value = updated
        fresh.groupBy { it.dimension }.forEach { (dimension, optionsInDim) ->
            val maxCode = optionsInDim.maxOf { it.code }
            val key = nextCodeKey(dimension)
            preferences.edit()
                .putInt(key, (preferences.getInt(key, initialNextCode(dimension))).coerceAtLeast(maxCode + 1))
                .apply()
        }
        return fresh.size
    }

    /**
     * 应用对方备份里的提醒相关设置（提醒阶梯/时间点/备菜提醒）。
     * 非空即覆盖：提醒要在两台设备一致才不会漏响。
     */
    fun applySyncedReminderSettings(
        reminderLadder: List<Int>?,
        reminderTimes: List<String>?,
        customReminderTimes: Set<String>?,
        mealPrepDayShift: Int?,
        mealPrepFireTime: String?
    ): Boolean {
        var applied = false
        if (!reminderLadder.isNullOrEmpty()) {
            setReminderLadder(reminderLadder)
            applied = true
        }
        if (!reminderTimes.isNullOrEmpty()) {
            setReminderTimes(reminderTimes)
            applied = true
        }
        if (!customReminderTimes.isNullOrEmpty()) {
            val merged = _customReminderTimes.value + customReminderTimes
            preferences.edit()
                .putString(KEY_CUSTOM_REMINDER_TIMES, merged.joinToString(REMINDER_TIME_SEPARATOR))
                .apply()
            _customReminderTimes.value = merged
            applied = true
        }
        if (mealPrepDayShift != null) {
            setMealPrepDayShift(mealPrepDayShift)
            applied = true
        }
        if (mealPrepFireTime != null && mealPrepFireTime.matches(TIME_FORMAT)) {
            setMealPrepFireTime(mealPrepFireTime)
            applied = true
        }
        return applied
    }

    /** 「在一起」起始日快照，备份导出用。 */
    fun togetherStartDateSnapshot(): String? = _togetherStartDate.value

    /**
     * 合并导入「在一起」起始日：按 updatedAt「较新者胜」。
     * 对方时间戳不晚于本地（含双方都为 0 的旧备份）则不改动本地，天然兼容老数据。
     */
    fun applySyncedTogetherStartDate(date: String?, updatedAt: Long?): Boolean {
        val incomingAt = updatedAt ?: 0L
        if (incomingAt <= _togetherUpdatedAt.value) return false
        persistTogetherStartDate(normalizeTogetherDate(date), incomingAt)
        return true
    }

    /** 合并导入「在一起」计数前缀：非空即覆盖，留空忽略。 */
    fun applySyncedTogetherLabelName(name: String?): Boolean {
        val normalized = name?.trim()?.take(MAX_TOGETHER_LABEL_LENGTH)?.takeIf { it.isNotEmpty() } ?: return false
        if (normalized == _togetherLabelName.value) return false
        setTogetherLabelName(normalized)
        return true
    }

    /** 合并导入昵称：非空即覆盖；与其它标量偏好同款策略（单用户多设备，同一时刻只有一个写者）。 */
    fun applySyncedNickname(name: String?): Boolean {
        val normalized = name?.trim()?.take(MAX_NICKNAME_LENGTH)?.takeIf { it.isNotEmpty() } ?: return false
        if (normalized == _nickname.value) return false
        setNickname(normalized)
        return true
    }

    /** 合并导入记账月起始日。 */
    fun applySyncedLedgerMonthStartDay(day: Int?): Boolean {
        if (day == null) return false
        val normalized = day.coerceIn(1, 28)
        if (normalized == _ledgerMonthStartDay.value) return false
        setLedgerMonthStartDay(normalized)
        return true
    }

    /** 合并导入预算超支提醒开关。 */
    fun applySyncedBudgetReminderEnabled(enabled: Boolean?): Boolean {
        if (enabled == null || enabled == _budgetReminderEnabled.value) return false
        setBudgetReminderEnabled(enabled)
        return true
    }

    /** 合并导入「未来 N 天菜谱」天数。 */
    fun applySyncedMealPlanDays(days: Int?): Boolean {
        if (days == null) return false
        val normalized = days.coerceIn(MIN_MEAL_PLAN_DAYS, MAX_MEAL_PLAN_DAYS)
        if (normalized == _mealPlanDays.value) return false
        setMealPlanDays(normalized)
        return true
    }

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

    /**
     * 上一次写入全量 checkpoint（base）的时刻（毫秒）；从未写过为 null。
     * 用于决定「距上次 base 超过 N 天」时刷新一份。
     */
    fun cloudBaseAt(): Long? =
        preferences.getLong(KEY_CLOUD_BASE_AT, NO_WATERMARK).takeIf { it != NO_WATERMARK }

    fun setCloudBaseAt(value: Long) {
        preferences.edit().putLong(KEY_CLOUD_BASE_AT, value).apply()
    }

    /**
     * 上次云同步遇到的冲突条数。
     *
     * 持久化的必要性：云同步多在后台自动进行（约 6 小时一次），用户往往不当场打开
     * 设置页；不落盘的话「有内容被较新一方覆盖」这件事就永远不会被看到——
     * 而它恰恰是用户唯一有机会察觉「数据被自动取舍了」的线索。
     */
    fun cloudLastConflicts(): Int = preferences.getInt(KEY_CLOUD_LAST_CONFLICTS, 0)

    fun setCloudLastConflicts(value: Int) {
        preferences.edit().putInt(KEY_CLOUD_LAST_CONFLICTS, value).apply()
    }

    /**
     * 自上次写 base 以来成功推送的增量快照数；用于决定「攒够 N 次变更」时刷新一份 base。
     * 增量攒得多，新设备从 base 起步才划算（否则要重放很多 rev）。
     */
    fun cloudPushesSinceBase(): Int = preferences.getInt(KEY_CLOUD_PUSHES_SINCE_BASE, 0)

    fun setCloudPushesSinceBase(value: Int) {
        preferences.edit().putInt(KEY_CLOUD_PUSHES_SINCE_BASE, value).apply()
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

        /** 默认就寝提醒时间（F15）。 */
        const val DEFAULT_BEDTIME_TIME = "22:30"

        /** 应用锁 PIN 的加盐盐值（F19）：只存哈希，不存明文。 */
        private const val PIN_SALT = "lemonbox::applock::v1"

        /** 计算 PIN 的加盐 SHA-256 十六进制串（F19）。 */
        fun hashPin(pin: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest((PIN_SALT + pin).toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** 提醒时间点的候选档位（HH:mm），设置页与调度器共用。 */
        val REMINDER_TIME_OPTIONS = listOf(
            "07:00", "08:00", "09:00", "12:00", "18:00", "19:00", "20:00", "21:00"
        )

        const val DAY_MS = 24 * 60 * 60 * 1000L

        /** 昵称最大长度（按字符计），避免过长的称呼撑坏标题与通知。 */
        const val MAX_NICKNAME_LENGTH = 12

        /** 「在一起」计数前缀的默认值。 */
        const val DEFAULT_TOGETHER_LABEL_NAME = "我们"

        /** 「在一起」计数前缀的最大长度（按字符计），避免撑坏首页 Hero。 */
        const val MAX_TOGETHER_LABEL_LENGTH = 8

        /** 水位线缺失时的哨兵值。 */
        private const val NO_WATERMARK = Long.MIN_VALUE

        /** 从未同步过的哨兵值。 */
        private const val NO_SYNC_AT = Long.MIN_VALUE

        /** 已做菜单日期的分隔符：用换行避免与日期串冲突。 */
        private const val SEARCH_SEPARATOR = "\n"

        /** 自定义状态的分隔符：整体按换行分行，每行内以竖线分隔编号与名称。 */
        private const val STATUS_SEPARATOR = "\n"
        private const val STATUS_FIELD_SEPARATOR = "|"

        private const val PREFS_NAME = "lemon_preferences"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_TOGETHER_START_DATE = "together_start_date"
        private const val KEY_TOGETHER_UPDATED_AT = "together_start_date_updated_at"
        private const val KEY_TOGETHER_LABEL_NAME = "together_label_name"
        private const val KEY_REMINDER_DAYS = "reminder_days"
        private const val KEY_REMINDER_LADDER = "reminder_ladder"
        private const val REMINDER_LADDER_SEPARATOR = ","
        private const val KEY_REMINDER_TIMES = "reminder_times"
        private const val REMINDER_TIME_SEPARATOR = ","
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CONTINUOUS_ENTRY = "continuous_entry"
        private const val KEY_EDITOR_ADVANCED_EXPANDED = "editor_advanced_expanded"
        private const val KEY_EXPIRY_QUICK_OPTIONS = "expiry_quick_options"
        private const val EXPIRY_QUICK_SEPARATOR = ","
        private const val KEY_KEEP_ALIVE_ENABLED = "keep_alive_enabled"
        private const val KEY_AUTO_START_CONFIRMED = "auto_start_confirmed"
        private const val KEY_LAST_MAINTENANCE_AT = "last_maintenance_at"
        private const val KEY_CUSTOM_REMINDER_TIMES = "custom_reminder_times"
        private const val KEY_BEDTIME_REMINDER_ENABLED = "bedtime_reminder_enabled"
        private const val KEY_BEDTIME_TIME = "bedtime_time"
        private const val KEY_BEDTIME_NOTIFIED_DATE = "bedtime_notified_date"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_APP_LOCK_PIN_HASH = "app_lock_pin_hash"
        private const val KEY_OCR_MODEL_TIER = "ocr_model_tier"
        private const val KEY_MEAL_PREP_DAY_SHIFT = "meal_prep_day_shift"
        private const val KEY_MEAL_PREP_FIRE_TIME = "meal_prep_fire_time"

        /** HH:mm 时间格式（24 小时制），用于校验自定义时间输入。 */
        private val TIME_FORMAT = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_SYNC_REPORTS = "sync_reports"

        /** 同步历史上限：足够回看最近一段时间的同步，又不至于无限膨胀。 */
        private const val MAX_SYNC_REPORTS = 20
        private const val KEY_EXPIRY_NOTIFIED_DATE = "expiry_notified_date"
        private const val KEY_EXPIRY_IGNORED_DATE = "expiry_ignored_date"
        private const val KEY_EXPIRY_IGNORED_KEYS = "expiry_ignored_keys"
        private const val KEY_USER_FILTER_PRESETS = "user_filter_presets"
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
        private const val KEY_BUDGET_REMINDER_ENABLED = "budget_reminder_enabled"
        private const val KEY_LEDGER_MONTH_START_DAY = "ledger_month_start_day"

        /**
         * 用户新增菜谱的起始编号，避开内置菜谱占用的编号段。
         * 注：内置菜谱的下架 / 刷新 / 补种水位线，以及菜单搭配规则版本号，
         * 已随结构化偏好的迁移逻辑一并移至 [StructuredPreferenceMigrations]。
         */
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

        /** 上次写全量 checkpoint 的时刻，以及自那时起推送的增量快照数（见 cloudBaseAt 等）。 */
        private const val KEY_CLOUD_BASE_AT = "cloud_base_at"
        private const val KEY_CLOUD_PUSHES_SINCE_BASE = "cloud_pushes_since_base"

        /** 上次云同步的冲突条数；落盘以便后台同步的冲突也能被用户看到。 */
        private const val KEY_CLOUD_LAST_CONFLICTS = "cloud_last_conflicts"

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