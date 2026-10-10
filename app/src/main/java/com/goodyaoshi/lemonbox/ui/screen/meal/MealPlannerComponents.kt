package com.goodyaoshi.lemonbox.ui.screen.meal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.meal.BASE_SOUP_LABEL
import com.goodyaoshi.lemonbox.data.meal.BASE_STAPLES
import com.goodyaoshi.lemonbox.data.meal.DishRole
import com.goodyaoshi.lemonbox.data.meal.MealDish
import com.goodyaoshi.lemonbox.data.meal.MealDishSpec
import com.goodyaoshi.lemonbox.data.meal.MealSpec
import com.goodyaoshi.lemonbox.data.meal.Recipe
import com.goodyaoshi.lemonbox.data.repository.WeeklyMealDay
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorSectionLabel
import com.goodyaoshi.lemonbox.ui.components.EditorSelectionChip
import com.goodyaoshi.lemonbox.ui.components.InlineNotice
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SegmentedTabs
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TagBlue
import com.goodyaoshi.lemonbox.ui.theme.TagBlueText
import com.goodyaoshi.lemonbox.ui.theme.TagGreen
import com.goodyaoshi.lemonbox.ui.theme.TagGreenText
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TagRed
import com.goodyaoshi.lemonbox.ui.theme.TagRedText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.util.DateUtil
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 横向日期选择条（今天默认选中）：固定在页头下方，不随内容滚动；
 * 点哪一天，内容区就切到哪天的菜品。
 */
@Composable
internal fun MealDayTabRow(
    days: List<WeeklyMealDay>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(days, key = { _, item -> item.dateKey }) { itemIndex, item ->
            MealDayTab(
                day = item,
                selected = itemIndex == selectedIndex,
                onClick = { onSelect(itemIndex) }
            )
        }
    }
}

/** 选中那天的一餐：双列网格展示该日期对应的菜品，点「做了」自动扣当天食材。 */
@Composable
internal fun WeeklyMenuSection(
    day: WeeklyMealDay,
    onReroll: (String) -> Unit,
    onCooked: (String) -> Unit,
    onEdit: (String) -> Unit,
    onPrepReminder: (String) -> Unit,
    mealPrepDays: Set<String>,
    onItemClick: (ItemDetail) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DayMealGrid(day = day, onItemClick = onItemClick)

        DayMealActionRow(
            day = day,
            hasPrepReminder = day.dateKey in mealPrepDays,
            onCooked = { onCooked(day.dateKey) },
            onReroll = { onReroll(day.dateKey) },
            onEdit = { onEdit(day.dateKey) },
            onPrepReminder = { onPrepReminder(day.dateKey) }
        )
    }
}

/** 日期 Tab：上排「今天/周三」，下排「10/04」。 */
@Composable
private fun MealDayTab(
    day: WeeklyMealDay,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) OrangeStart else SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = day.dayLabel,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else TextSecondary
        )
        Text(
            text = day.shortDate,
            // 字号走主题字阶（F6），并满足说明文字 ≥12sp（F7）。
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White.copy(alpha = 0.85f) else TextHint,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

/** 一餐菜品：双列网格，每行两道，落单的补一个空位保持左右对齐。 */
@Composable
internal fun DayMealGrid(
    day: WeeklyMealDay,
    onItemClick: (ItemDetail) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        day.combo.dishes.chunked(2).forEach { rowDishes ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowDishes.forEach { dish ->
                    MealDishCard(
                        dish = dish,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onItemClick = onItemClick
                    )
                }
                if (rowDishes.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 网格里的一道菜：角色标签 + 菜名 + 食材齐否；有现成食材时点标签可跳到物品。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealDishCard(
    dish: MealDish,
    modifier: Modifier = Modifier,
    onItemClick: (ItemDetail) -> Unit
) {
    val available = dish.suggestion?.available.orEmpty()
    val missing = dish.suggestion?.missing.orEmpty()
    val expiring = dish.suggestion?.expiring == true

    AppSurfaceCard(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(12.dp),
        containerColor = CardWhite.copy(alpha = 0.8f),
        shadowElevation = 6.dp
    ) {
        Text(
            text = dish.displayRoles.joinToString("·") { it.label },
            // 字号走主题字阶（F6），并满足说明文字 ≥12sp（F7）。
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = dish.label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 2
        )
        Spacer(modifier = Modifier.height(6.dp))
        when {
            // 基础项（米饭/白粥/清汤）：家里默认就有，不参与食材匹配，也不扣食材。
            dish.recipeId == null -> PillTag(
                text = "基础",
                backgroundColor = TagBlue,
                contentColor = TagBlueText
            )
            expiring -> PillTag(
                text = "有临期食材",
                backgroundColor = TagRed,
                contentColor = TagRedText
            )
            missing.isEmpty() -> PillTag(
                text = "食材齐",
                backgroundColor = TagGreen,
                contentColor = TagGreenText
            )
            else -> Text(
                text = "缺：${missing.joinToString("、")}",
                // 字号走主题字阶（F6），并满足说明文字 ≥12sp（F7）。
                style = MaterialTheme.typography.bodySmall,
                color = TagOrangeText,
                maxLines = 2
            )
        }
        if (available.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                available.take(3).forEach { (label, detail) ->
                    PillTag(
                        text = label,
                        backgroundColor = TagGreen,
                        contentColor = TagGreenText,
                        onClick = { onItemClick(detail) }
                    )
                }
            }
        }
    }
}

/**
 * 选中那天的操作区：状态提示 + 备菜提醒放上面一行，「做了 / 换一道 / 编辑一餐」
 * 改成整行大按钮（做了为主操作），不再挤在卡片右下角用小胶囊难点。
 */
@Composable
internal fun DayMealActionRow(
    day: WeeklyMealDay,
    hasPrepReminder: Boolean,
    onCooked: () -> Unit,
    onReroll: () -> Unit,
    onEdit: () -> Unit,
    onPrepReminder: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (day.missing.isEmpty()) "现有食材够做这一餐" else "缺 ${day.missing.size} 样主料",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            if (day.cooked) {
                PillTag(text = "已做", backgroundColor = TagGreen, contentColor = TagGreenText)
            } else {
                // 备菜提醒（如提前一晚解冻肉）：一天一条，生成后显示「已提醒」。
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (hasPrepReminder) TagGreen else SurfaceWarmDeep)
                        .clickable(enabled = !hasPrepReminder, onClick = onPrepReminder)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (hasPrepReminder) "已提醒" else "提醒准备",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (hasPrepReminder) TagGreenText else TextSecondary
                    )
                }
            }
        }
        if (day.cooked) {
            MealActionButton(
                label = "编辑一餐",
                primary = false,
                modifier = Modifier.fillMaxWidth(),
                onClick = onEdit
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MealActionButton(
                    label = "做了",
                    primary = true,
                    modifier = Modifier.weight(1.3f),
                    onClick = onCooked
                )
                MealActionButton(
                    label = "换一道",
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = onReroll
                )
                MealActionButton(
                    label = "编辑一餐",
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = onEdit
                )
            }
        }
    }
}

/** 菜单操作大按钮：主操作橙底白字，次操作灰底；整行等宽更好点。 */
@Composable
private fun MealActionButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (primary) OrangeStart else SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) Color.White else TextSecondary
        )
    }
}

/** 自定义计划天数：常用档位一键切换，也可用加减微调（1..30 天）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MealPlanDaysDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var days by remember(current) { mutableIntStateOf(current) }
    AppDialog(
        title = "计划天数",
        subtitle = "从今天起排几天的菜单（1-30 天）；改完会自动补齐新的一天或裁剪多余的天。",
        onDismissRequest = onDismiss,
        confirmText = "保存",
        onConfirm = { onConfirm(days) }
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PLAN_DAY_PRESETS.forEach { preset ->
                EditorSelectionChip(
                    text = "$preset 天",
                    selected = days == preset,
                    onClick = { days = preset }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "自定义天数", fontSize = 13.sp, color = TextSecondary)
            Spacer(modifier = Modifier.weight(1f))
            PlanDayStepButton(text = "−") {
                days = (days - 1).coerceAtLeast(MIN_PLAN_DAYS)
            }
            Text(
                text = "$days",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            PlanDayStepButton(text = "+") {
                days = (days + 1).coerceAtMost(MAX_PLAN_DAYS)
            }
        }
    }
}

@Composable
private fun PlanDayStepButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceWarmDeep)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = OrangeStart)
    }
}

/** 计划天数的常用档位。 */
private val PLAN_DAY_PRESETS = listOf(3, 5, 7, 10, 14)

/** 计划天数的边界。 */
private const val MIN_PLAN_DAYS = 1
private const val MAX_PLAN_DAYS = 30

/** 手动编辑某天的一餐：底部弹层里按角色 Tabs 挑菜，避免一屏平铺太多菜谱选不到。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MealEditorSheet(
    day: WeeklyMealDay,
    recipes: List<Recipe>,
    onDismiss: () -> Unit,
    onSave: (MealSpec) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selection by remember(day.dateKey) {
        mutableStateOf(
            day.combo.dishes.map { dish ->
                MealDishSpec(dish.role.name, dish.recipeId, dish.label)
            }
        )
    }
    var tabIndex by remember { mutableIntStateOf(0) }

    // 一餐覆盖到的角色取并集：多角色菜（饺子等）能一次补齐多个角色。
    val covered = selection.flatMap { spec ->
        spec.recipeId?.let { id -> recipes.firstOrNull { it.id == id }?.dishRoles }
            ?: setOf(spec.dishRole)
    }.toSet()
    val complete = DishRole.STAPLE in covered && DishRole.PROTEIN in covered
    val options = remember(recipes, tabIndex) {
        dishOptions(MealPickTab.entries[tabIndex].role, recipes)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(CardWhite)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp, bottom = 20.dp)
        ) {
            Text(
                text = "${day.dayLabel}吃什么",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "主食 + 蛋白必配，蔬菜、汤饮可选；一道菜可顶多个角色（如饺子既是主食也是蛋白和蔬菜）。保存后缺的主料会自动进待买。",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))
            EditorSectionLabel(
                label = "已选（点一下移除）",
                tag = if (complete) "搭配完整" else "还缺主食/蛋白"
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (selection.isEmpty()) {
                Text(text = "还没选菜，到下面按分类挑一道吧。", fontSize = 12.sp, color = TextHint)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selection.forEach { spec ->
                        EditorSelectionChip(
                            text = "${spec.label} ✕",
                            selected = true,
                            onClick = { selection = selection - spec }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DishRole.entries.forEach { role ->
                    PillTag(
                        text = role.label,
                        backgroundColor = if (role in covered) TagGreen else SurfaceWarmDeep,
                        contentColor = if (role in covered) TagGreenText else TextHint
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            SegmentedTabs(
                labels = MealPickTab.entries.map { it.label },
                selectedIndex = tabIndex,
                onSelect = { tabIndex = it }
            )

            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (options.isEmpty()) {
                    Text(
                        text = "菜谱库里还没有「${MealPickTab.entries[tabIndex].label}」菜谱，去「菜谱库」添加一道吧。",
                        fontSize = 12.sp,
                        color = TextHint
                    )
                } else {
                    options.forEach { option ->
                        val selected = selection.any { it.matches(option) }
                        EditorSelectionChip(
                            text = option.label,
                            selected = selected,
                            onClick = {
                                selection = if (selected) {
                                    selection.filterNot { it.matches(option) }
                                } else {
                                    selection + MealDishSpec(option.role.name, option.recipeId, option.label)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SheetActionButton(
                    text = "取消",
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                SheetActionButton(
                    text = if (complete) "保存" else "还缺主食/蛋白",
                    modifier = Modifier.weight(1f),
                    enabled = complete,
                    highlight = complete,
                    onClick = { onSave(MealSpec(selection)) }
                )
            }
        }
    }
}

/** 编辑弹层里的角色 Tabs：全部 + 四个配餐角色。 */
private enum class MealPickTab(val label: String, val role: DishRole?) {
    ALL("全部", null),
    STAPLE("主食", DishRole.STAPLE),
    PROTEIN("蛋白", DishRole.PROTEIN),
    VEGETABLE("蔬菜", DishRole.VEGETABLE),
    SOUP("汤饮", DishRole.SOUP)
}

/** 可挑选的一道菜：菜谱（[recipeId] 非空）或基础主食/汤饮（[recipeId] 为空）。 */
private data class DishOption(
    val role: DishRole,
    val recipeId: Long?,
    val label: String
)

/** 某角色可挑的菜品：菜谱库里覆盖该角色的菜 + 主食/汤饮的基础兜底。 */
private fun dishOptions(role: DishRole?, recipes: List<Recipe>): List<DishOption> {
    val fromRecipes = recipes
        .filter { role == null || role in it.dishRoles }
        .map { DishOption(it.dishRoles.first(), it.id, it.name) }
    // 基础主食若与用户自建菜谱重名，只保留菜谱那份，避免选项重复。
    val recipeNames = recipes.map { it.name }.toSet()
    val bases = buildList {
        if (role == null || role == DishRole.STAPLE) {
            BASE_STAPLES.forEach { name ->
                if (name !in recipeNames) add(DishOption(DishRole.STAPLE, null, name))
            }
        }
        if (role == null || role == DishRole.SOUP) {
            add(DishOption(DishRole.SOUP, null, BASE_SOUP_LABEL))
        }
    }
    return fromRecipes + bases
}

/** 已选菜与候选菜是否是同一道：菜谱按 id 比，基础项按名称比。 */
private fun MealDishSpec.matches(option: DishOption): Boolean =
    recipeId == option.recipeId && label == option.label

/** 弹层底部按钮：高亮为主操作，禁用时置灰。 */
@Composable
private fun SheetActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    !enabled -> SurfaceWarmDeep
                    highlight -> OrangeStart
                    else -> SurfaceWarmDeep
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                !enabled -> TextHint
                highlight -> Color.White
                else -> TextSecondary
            }
        )
    }
}

/**
 * 备菜提醒弹窗：为某天的一餐挑提醒时机，文案按蛋白菜自动预填（带冻肉的提示解冻），可手改。
 * 触发日直接列相对今天的具体日期（今天/明天/后天/…直到菜谱那天），不再用「前一天/当天」
 * 这种要自己心算的相对说法；选了哪天，提醒内容里的相对词就按那天为基准换算
 * （如菜谱在后天、提醒设在明天，文案说「明天要做」——后天是明天的明天，别让读者数错日子）。
 * 时间独立选择：默认带出设置页配置的时间，也能临时改成任意分钟。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MealPrepDialog(
    dateKey: String,
    suggestedTitle: String,
    defaultDayShift: Int,
    defaultFireTime: String,
    onDismiss: () -> Unit,
    onSuggestTitle: (dayShift: Int) -> String,
    onConfirm: (dayShift: Int, fireTime: String) -> Unit,
    errorMessage: String? = null
) {
    var title by remember(dateKey) { mutableStateOf(suggestedTitle) }
    var titleEdited by remember(dateKey) { mutableStateOf(false) }
    val today = remember(dateKey) { LocalDate.now() }
    val cookingDate = remember(dateKey) {
        runCatching { LocalDate.parse(dateKey) }.getOrDefault(today)
    }
    // 可选触发日：今天起到菜谱那天（最多先列 7 天，菜谱更远时把菜谱日本身补在末尾）。
    val triggerDates = remember(dateKey) {
        buildList {
            var day = today
            while (day <= cookingDate && size < 7) {
                add(day)
                day = day.plusDays(1)
            }
            if (cookingDate !in this) add(cookingDate)
        }
    }
    // 触发日相对菜谱日的偏移（≤0）：-1 前一天、0 当天。
    val shiftOf: (LocalDate) -> Int = { date ->
        ChronoUnit.DAYS.between(date, cookingDate).toInt()
    }
    // 默认值来自设置页的「备菜提醒默认」；对应的触发日收拢到可选范围内。
    var dayShift by remember(dateKey, defaultDayShift) {
        val preferred = cookingDate.plusDays(defaultDayShift.toLong())
        val clamped = when {
            preferred.isBefore(today) -> today
            preferred.isAfter(cookingDate) -> cookingDate
            else -> preferred
        }
        mutableIntStateOf(ChronoUnit.DAYS.between(clamped, cookingDate).toInt())
    }
    var fireTime by remember(dateKey, defaultFireTime) {
        mutableStateOf(defaultFireTime)
    }
    var showTimePicker by remember(dateKey) { mutableStateOf(false) }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = fireTime.substringBefore(":").toIntOrNull() ?: 19,
            initialMinute = fireTime.substringAfter(":").toIntOrNull() ?: 0,
            is24Hour = true
        )
        AppDialog(
            title = "提醒时间",
            onDismissRequest = { showTimePicker = false },
            confirmText = "确定",
            onConfirm = {
                fireTime = "%02d:%02d".format(pickerState.hour, pickerState.minute)
                showTimePicker = false
            }
        ) {
            TimePicker(state = pickerState)
        }
    }

    AppDialog(
        title = "提醒准备",
        subtitle = "到点会发一条通知，提前把菜准备好。",
        onDismissRequest = onDismiss,
        confirmText = "好的",
        confirmEnabled = title.isNotBlank(),
        onConfirm = { onConfirm(dayShift, fireTime) }
    ) {
        // 保存失败（多半是提醒时刻已过）就地说明，不打断对话框里的输入。
        if (errorMessage != null) {
            InlineNotice(message = errorMessage)
        }
        OutlinedTextField(
            value = title,
            onValueChange = {
                title = it
                titleEdited = true
            },
            label = { Text("提醒内容") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            triggerDates.forEach { date ->
                // 相对今天的日期词；超过后天时带上具体日期避免歧义。
                val relative = DateUtil.relativeDayLabel(date)
                val label = if (date.isAfter(today.plusDays(2))) {
                    "$relative ${date.monthValue}/${date.dayOfMonth}"
                } else {
                    relative
                }
                EditorSelectionChip(
                    text = label,
                    selected = dayShift == shiftOf(date),
                    onClick = {
                        dayShift = shiftOf(date)
                        // 没手改过文案时，跟着所选触发日重新换算相对词。
                        if (!titleEdited) title = onSuggestTitle(dayShift)
                    }
                )
            }
            // 时间独立选择：默认带出设置页配置的时间，也能临时改成任意分钟。
            EditorSelectionChip(
                text = fireTime,
                selected = false,
                onClick = { showTimePicker = true }
            )
        }
    }
}
