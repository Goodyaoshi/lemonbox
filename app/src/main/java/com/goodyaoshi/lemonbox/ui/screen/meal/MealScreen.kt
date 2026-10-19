package com.goodyaoshi.lemonbox.ui.screen.meal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.meal.MealSpec
import com.goodyaoshi.lemonbox.data.repository.CookedDaySnapshot
import com.goodyaoshi.lemonbox.data.repository.WeekMenuRepository
import com.goodyaoshi.lemonbox.data.repository.WeeklyMealDay
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.BottomBarClearance
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.HeaderActionPill
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.SkeletonList
import com.goodyaoshi.lemonbox.ui.components.rememberBottomBarClearance
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.LoadingState
import com.goodyaoshi.lemonbox.ui.viewmodel.RecipeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 吃饭 Tab 的 ViewModel：纯委托 [WeekMenuRepository]，
 * 菜单生成、扣料、补缺料进待买都收在仓库层，页面只管展示与触发。
 */
@HiltViewModel
class MealViewModel @Inject constructor(
    private val weekMenuRepository: WeekMenuRepository
) : ViewModel() {

    private val loading = LoadingState()

    /** 首帧加载标记：菜单读完之前显示骨架屏，避免「假空态」（见 [LoadingState]）。 */
    val isLoading: StateFlow<Boolean> = loading.isLoading

    /** 未来 N 天菜单。 */
    val weekPlan: StateFlow<List<WeeklyMealDay>> = weekMenuRepository.weekPlan
        .onEach { loading.markLoaded() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 计划天数（1..30）。 */
    val planDays: StateFlow<Int> = weekMenuRepository.planDays

    /** 已生成过「提醒准备」的日期集合。 */
    val mealPrepDays: StateFlow<Set<String>> = weekMenuRepository.mealPrepDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** 备菜提醒默认提前天数（-1 前一天 / 0 当天）。 */
    val mealPrepDefaultDayShift: StateFlow<Int> = weekMenuRepository.mealPrepDefaultDayShift

    /** 备菜提醒默认触发时刻。 */
    val mealPrepDefaultFireTime: StateFlow<String> = weekMenuRepository.mealPrepDefaultFireTime

    fun setPlanDays(days: Int) = weekMenuRepository.setPlanDays(days)

    /**
     * 一餐做完：仓库内幂等（已做不重复扣料），完成后回传扣料前的快照供「撤销」（GAP-12）；
     * 已做过或无可扣食材时回传 null，由页面决定是否提示。
     */
    fun markDayCooked(dateKey: String, onDone: (CookedDaySnapshot?) -> Unit = {}) {
        viewModelScope.launch { onDone(weekMenuRepository.markDayCooked(dateKey)) }
    }

    /** 撤销「做了」（GAP-12）：按快照把被扣食材写回、撤掉已做标记。 */
    fun undoMarkDayCooked(snapshot: CookedDaySnapshot) {
        viewModelScope.launch { weekMenuRepository.undoMarkDayCooked(snapshot) }
    }

    fun rerollDay(dateKey: String) {
        viewModelScope.launch { weekMenuRepository.rerollDay(dateKey) }
    }

    fun saveDayMeal(dateKey: String, spec: MealSpec) {
        viewModelScope.launch { weekMenuRepository.saveDayMeal(dateKey, spec) }
    }

    fun mealPrepTitle(dateKey: String, triggerDateKey: String): String? =
        weekMenuRepository.mealPrepTitle(dateKey, triggerDateKey)

    fun createMealPrepReminder(
        dateKey: String,
        dayShift: Int,
        fireTime: String,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            onResult(weekMenuRepository.createMealPrepReminder(dateKey, dayShift, fireTime))
        }
    }

    /** 取消某天的备菜提醒（P2-21）：已提醒的那天也能在本页直接撤掉。 */
    fun cancelMealPrepReminder(dateKey: String) {
        viewModelScope.launch { weekMenuRepository.cancelMealPrepReminder(dateKey) }
    }
}

/**
 * 吃饭页：本周安排即主体（日期条今天默认选中）+ 本周缺料速览条（跳待买）。
 * 低频的「菜谱库」弱化为右上角入口，进独立页管理；换一道/写菜触发的补缺料逻辑在 WeekMenuRepository。
 */
@Composable
fun MealScreen(
    onNavigateToToBuy: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToRecipeLibrary: () -> Unit,
    viewModel: MealViewModel = hiltViewModel(),
    recipeViewModel: RecipeViewModel = hiltViewModel()
) {
    val weekPlan by viewModel.weekPlan.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val planDays by viewModel.planDays.collectAsState()
    val mealPrepDays by viewModel.mealPrepDays.collectAsState()
    val prepDayShift by viewModel.mealPrepDefaultDayShift.collectAsState()
    val prepFireTime by viewModel.mealPrepDefaultFireTime.collectAsState()
    val recipes by recipeViewModel.recipes.collectAsState()
    val snackbar = LocalAppSnackbar.current

    var showPlanDays by remember { mutableStateOf(false) }
    var editingMealDateKey by remember { mutableStateOf<String?>(null) }
    var prepReminderDateKey by remember { mutableStateOf<String?>(null) }
    // 选中的日期下标：日期条固定在页头下方，菜单生成天数变化时收拢到有效范围。
    var selectedDayIndex by remember { mutableIntStateOf(0) }
    val selectedDay = weekPlan.getOrNull(
        selectedDayIndex.coerceIn(0, (weekPlan.size - 1).coerceAtLeast(0))
    )

    // 本周缺料：还没做的那几天里仍缺的主料，跨天去重后展示。
    val missingAll = remember(weekPlan) {
        weekPlan.filterNot { it.cooked }.flatMap { it.missing }.distinct()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "吃饭",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "排好一餐，缺的主料自动进待买；做完点「做了」扣库存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                // 低频入口统一「图标+文字」小胶囊（与记账页预算/统计同款）：菜谱库、计划天数
                HeaderActionPill(
                    icon = Icons.Default.RestaurantMenu,
                    label = "菜谱库",
                    onClick = onNavigateToRecipeLibrary
                )
                Spacer(modifier = Modifier.width(8.dp))
                HeaderActionPill(
                    icon = Icons.Default.CalendarMonth,
                    label = "$planDays 天",
                    onClick = { showPlanDays = true }
                )
            }

            // 固定日期条：常驻页头下方，往下翻菜单也能随时换天。
            if (weekPlan.isNotEmpty()) {
                MealDayTabRow(
                    days = weekPlan,
                    selectedIndex = selectedDayIndex.coerceIn(0, weekPlan.lastIndex),
                    onSelect = { selectedDayIndex = it },
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // 首帧（数据未到）：先画与菜单同构的骨架，避免把「还没读到」误判成「计划被清空了」。
                if (isLoading) {
                    SkeletonList(itemCount = 3)
                } else if (weekPlan.isEmpty()) {
                    // 一周菜单为空（新用户/清空计划）：日期条、菜单区、缺料条都会不渲染，
                    // 这里补一个带入口的空态，避免整页空白、用户无路可走。
                    EmptyState(
                        title = "还没有一周菜单",
                        message = "先去菜谱库添几道常做的菜，再回来把它们排进这一周。",
                        icon = Icons.Default.RestaurantMenu,
                        actionLabel = "去菜谱库添加菜谱",
                        onAction = onNavigateToRecipeLibrary
                    )
                } else {
                    MissingIngredientsBar(
                        missing = missingAll,
                        onClick = onNavigateToToBuy
                    )

                    selectedDay?.let { day ->
                        WeeklyMenuSection(
                            day = day,
                            onReroll = viewModel::rerollDay,
                            onCooked = { dateKey ->
                                // 回执改为可撤销（GAP-12）：扣库存是全 App 最大的一次隐性写操作，
                                // 点「做了」后按扣料前的快照可一键回滚，不再静默扣完就没了。
                                viewModel.markDayCooked(dateKey) { snapshot ->
                                    if (snapshot == null) {
                                        snackbar?.postMessage("这餐已经记过「做过」了")
                                    } else {
                                        val consumed = snapshot.previousItems.size
                                        snackbar?.showUndo(
                                            if (consumed == 0) {
                                                "已记「做过」"
                                            } else {
                                                "已记「做过」，消耗了 $consumed 样食材"
                                            },
                                            DeleteCopy.UNDO_ACTION
                                        ) {
                                            viewModel.undoMarkDayCooked(snapshot)
                                        }
                                    }
                                }
                            },
                            onEdit = { editingMealDateKey = it },
                            onPrepReminder = { prepReminderDateKey = it },
                            mealPrepDays = mealPrepDays,
                            onItemClick = { onNavigateToDetail(it.item.id) }
                        )
                    }
                }

                BottomBarClearance(modifier = Modifier, extra = 16.dp)
            }
        }
    }

    if (showPlanDays) {
        MealPlanDaysDialog(
            current = planDays,
            onDismiss = { showPlanDays = false },
            onConfirm = { days ->
                viewModel.setPlanDays(days)
                showPlanDays = false
            }
        )
    }

    val editingDay = editingMealDateKey?.let { key -> weekPlan.firstOrNull { it.dateKey == key } }
    if (editingDay != null) {
        MealEditorSheet(
            day = editingDay,
            recipes = recipes,
            // 某分类无菜谱时的「去菜谱库添加」入口（P3-15）。
            onNavigateToRecipeLibrary = onNavigateToRecipeLibrary,
            onDismiss = { editingMealDateKey = null },
            onSave = { spec ->
                viewModel.saveDayMeal(editingDay.dateKey, spec)
                editingMealDateKey = null
            }
        )
    }

    prepReminderDateKey?.let { dateKey ->
        // 保存失败的就地提示（I10）：留在对话框内显示，用户改完时间即可重试，不靠一闪而过的 Toast。
        var prepError by remember(dateKey) { mutableStateOf<String?>(null) }
        // 默认触发日按设置页「前一天/当天」换算，并收拢到今天~菜谱日之间。
        val defaultTrigger = remember(dateKey, prepDayShift) {
            val today = LocalDate.now()
            val cookingDate = runCatching { LocalDate.parse(dateKey) }.getOrDefault(today)
            val preferred = cookingDate.plusDays(prepDayShift.toLong())
            when {
                preferred.isBefore(today) -> today
                preferred.isAfter(cookingDate) -> cookingDate
                else -> preferred
            }
        }
        MealPrepDialog(
            dateKey = dateKey,
            suggestedTitle = viewModel.mealPrepTitle(dateKey, defaultTrigger.toString()) ?: "提前备菜",
            defaultDayShift = prepDayShift,
            defaultFireTime = prepFireTime,
            errorMessage = prepError,
            onDismiss = { prepReminderDateKey = null },
            onSuggestTitle = { dayShift ->
                viewModel.mealPrepTitle(
                    dateKey,
                    runCatching { LocalDate.parse(dateKey).plusDays(dayShift.toLong()).toString() }
                        .getOrDefault(dateKey)
                ) ?: "提前备菜"
            },
            onConfirm = { dayShift, fireTime ->
                prepError = null
                viewModel.createMealPrepReminder(dateKey, dayShift, fireTime) { saved ->
                    if (saved) {
                        prepReminderDateKey = null
                    } else {
                        // 保存失败只可能是提醒时刻已经过了：在对话框里就地说明。
                        prepError = "这个时间已经过了，换个时间吧"
                    }
                }
            },
            // 已提醒的那天再次点开（P2-21）：给「取消这条提醒」出口，不必退出去找提醒页。
            onCancelReminder = if (dateKey in mealPrepDays) {
                {
                    viewModel.cancelMealPrepReminder(dateKey)
                    prepReminderDateKey = null
                }
            } else {
                null
            }
        )
    }
}

/** 本周缺料速览条：还缺主料时展示数量与前三样预览，点击跳家当·待买（采购闭环的显性一环）。 */
@Composable
private fun MissingIngredientsBar(
    missing: List<String>,
    onClick: () -> Unit
) {
    if (missing.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            // 圆角走形状令牌（F6）：large 为 20dp。
            .clip(MaterialTheme.shapes.large)
            .background(TagOrange.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "本周菜单还缺 ${missing.size} 样主料",
                // 字号走主题字阶（F6）：titleSmall 为 14sp。
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TagOrangeText
            )
            Text(
                text = missing.take(3).joinToString("、") + if (missing.size > 3) "…" else "",
                // 字号走主题字阶（F6）：bodySmall 为 12sp。
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = "去待买",
            tint = TagOrangeText,
            modifier = Modifier.size(18.dp)
        )
    }
}
