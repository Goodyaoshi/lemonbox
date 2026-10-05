package com.goodyaoshi.lemonbox.ui.screen.meal

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.goodyaoshi.lemonbox.data.meal.MealSpec
import com.goodyaoshi.lemonbox.data.repository.WeekMenuRepository
import com.goodyaoshi.lemonbox.data.repository.WeeklyMealDay
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.HeaderActionPill
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.RecipeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * 吃饭 Tab 的 ViewModel：纯委托 [WeekMenuRepository]，
 * 菜单生成、扣料、补缺料进待买都收在仓库层，页面只管展示与触发。
 */
@HiltViewModel
class MealViewModel @Inject constructor(
    private val weekMenuRepository: WeekMenuRepository
) : ViewModel() {

    /** 未来 N 天菜单。 */
    val weekPlan: StateFlow<List<WeeklyMealDay>> = weekMenuRepository.weekPlan

    /** 计划天数（1..30）。 */
    val planDays: StateFlow<Int> = weekMenuRepository.planDays

    /** 已生成过「提醒准备」的日期集合。 */
    val mealPrepDays: StateFlow<Set<String>> = weekMenuRepository.mealPrepDays

    /** 备菜提醒默认提前天数（-1 前一天 / 0 当天）。 */
    val mealPrepDefaultDayShift: StateFlow<Int> = weekMenuRepository.mealPrepDefaultDayShift

    /** 备菜提醒默认触发时刻。 */
    val mealPrepDefaultFireTime: StateFlow<String> = weekMenuRepository.mealPrepDefaultFireTime

    fun setPlanDays(days: Int) = weekMenuRepository.setPlanDays(days)

    fun markDayCooked(dateKey: String) = weekMenuRepository.markDayCooked(dateKey)

    fun rerollDay(dateKey: String) = weekMenuRepository.rerollDay(dateKey)

    fun saveDayMeal(dateKey: String, spec: MealSpec) = weekMenuRepository.saveDayMeal(dateKey, spec)

    fun mealPrepTitle(dateKey: String, triggerDateKey: String): String? =
        weekMenuRepository.mealPrepTitle(dateKey, triggerDateKey)

    fun createMealPrepReminder(
        dateKey: String,
        dayShift: Int,
        fireTime: String,
        onResult: (Boolean) -> Unit
    ) = weekMenuRepository.createMealPrepReminder(dateKey, dayShift, fireTime, onResult)
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
    val planDays by viewModel.planDays.collectAsState()
    val mealPrepDays by viewModel.mealPrepDays.collectAsState()
    val prepDayShift by viewModel.mealPrepDefaultDayShift.collectAsState()
    val prepFireTime by viewModel.mealPrepDefaultFireTime.collectAsState()
    val recipes by recipeViewModel.recipes.collectAsState()

    val context = LocalContext.current
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
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "排好一餐，缺的主料自动进待买；做完点「做了」扣库存。",
                        fontSize = 12.sp,
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
                MissingIngredientsBar(
                    missing = missingAll,
                    onClick = onNavigateToToBuy
                )

                selectedDay?.let { day ->
                    WeeklyMenuSection(
                        day = day,
                        onReroll = viewModel::rerollDay,
                        onCooked = viewModel::markDayCooked,
                        onEdit = { editingMealDateKey = it },
                        onPrepReminder = { prepReminderDateKey = it },
                        mealPrepDays = mealPrepDays,
                        onItemClick = { onNavigateToDetail(it.item.id) }
                    )
                }

                Spacer(modifier = Modifier.height(110.dp))
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
            onDismiss = { editingMealDateKey = null },
            onSave = { spec ->
                viewModel.saveDayMeal(editingDay.dateKey, spec)
                editingMealDateKey = null
            }
        )
    }

    prepReminderDateKey?.let { dateKey ->
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
            onDismiss = { prepReminderDateKey = null },
            onSuggestTitle = { dayShift ->
                viewModel.mealPrepTitle(
                    dateKey,
                    runCatching { LocalDate.parse(dateKey).plusDays(dayShift.toLong()).toString() }
                        .getOrDefault(dateKey)
                ) ?: "提前备菜"
            },
            onConfirm = { dayShift, fireTime ->
                viewModel.createMealPrepReminder(dateKey, dayShift, fireTime) { saved ->
                    if (!saved) {
                        Toast.makeText(context, "这个时间已经过了，换个时间吧", Toast.LENGTH_SHORT).show()
                    }
                }
                prepReminderDateKey = null
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
            .clip(RoundedCornerShape(18.dp))
            .background(TagOrange.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "本周菜单还缺 ${missing.size} 样主料",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TagOrangeText
            )
            Text(
                text = missing.take(3).joinToString("、") + if (missing.size > 3) "…" else "",
                fontSize = 12.sp,
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
