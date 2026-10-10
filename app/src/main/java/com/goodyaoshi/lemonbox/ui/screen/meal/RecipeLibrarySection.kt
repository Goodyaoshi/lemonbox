package com.goodyaoshi.lemonbox.ui.screen.meal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.meal.DishRole
import com.goodyaoshi.lemonbox.data.meal.Recipe
import com.goodyaoshi.lemonbox.data.meal.RecipeIngredient
import com.goodyaoshi.lemonbox.data.meal.decodeRoles
import com.goodyaoshi.lemonbox.data.meal.encodeRoles
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorSelectionChip
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SegmentedTabs
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TagGreen
import com.goodyaoshi.lemonbox.ui.theme.TagGreenText
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.IngredientGroup
import com.goodyaoshi.lemonbox.ui.viewmodel.RecipeViewModel

/**
 * 菜谱库独立页：低频管理页（从吃饭页右上角进入），维护配餐用的菜谱。
 * 新增走右下角 FAB，与记账「记一笔」、家当「录入」统一；列表放在 verticalScroll 的
 * 普通 Column 里，避免嵌套滚动。
 */
@Composable
fun RecipeLibraryScreen(
    onBack: () -> Unit,
    viewModel: RecipeViewModel = hiltViewModel()
) {
    val recipes by viewModel.recipes.collectAsState()
    val ingredientGroups by viewModel.ingredientGroups.collectAsState()
    var editorTarget by remember { mutableStateOf<Recipe?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Recipe?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = TextPrimary
                    )
                }
                Text(
                    text = "菜谱库",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            // 固定筛选：分类 TAB 常驻页头下方，不随列表滚动，滚到哪都能切。
            SegmentedTabs(
                labels = RecipeTab.entries.map { it.label },
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 2.dp, bottom = 12.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                RecipeLibraryContent(
                    recipes = recipes,
                    selectedTab = selectedTab,
                    ingredientGroups = ingredientGroups,
                    onEdit = { recipe ->
                        editorTarget = recipe
                        showEditor = true
                    },
                    onDelete = { pendingDelete = it },
                    onAdd = {
                        editorTarget = null
                        showEditor = true
                    }
                )
                Spacer(modifier = Modifier.height(120.dp))
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 24.dp)
                .size(58.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(colors = listOf(LemonStart, LemonEnd))
                )
                .clickable {
                    editorTarget = null
                    showEditor = true
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "新增菜谱",
                tint = OnLemon,
                modifier = Modifier.size(28.dp)
            )
        }
    }

    if (showEditor) {
        RecipeEditorDialog(
            initial = editorTarget,
            ingredientGroups = ingredientGroups,
            onDismiss = {
                showEditor = false
                editorTarget = null
            },
            onConfirm = { name, ingredients, role ->
                val target = editorTarget
                if (target == null) {
                    viewModel.addRecipe(name, ingredients, role)
                } else {
                    viewModel.updateRecipe(target.id, name, ingredients, role)
                }
                showEditor = false
                editorTarget = null
            }
        )
    }

    pendingDelete?.let { recipe ->
        AppDialog(
            title = "删除菜谱",
            subtitle = "删除后配餐和「做了」扣料都不会再算这道菜。",
            onDismissRequest = { pendingDelete = null },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                viewModel.removeRecipe(recipe.id)
                pendingDelete = null
            }
        ) {
            Text(
                text = "确定要删除「${recipe.name}」吗？",
                fontSize = 14.sp,
                color = TextSecondary
            )
        }
    }
}

/** 菜谱列表主体：菜谱卡列表（筛选 TAB 已上移到页头下方固定）。 */
@Composable
private fun RecipeLibraryContent(
    recipes: List<Recipe>,
    selectedTab: Int,
    ingredientGroups: List<IngredientGroup>,
    onEdit: (Recipe) -> Unit,
    onDelete: (Recipe) -> Unit,
    onAdd: () -> Unit
) {
    val visibleRecipes = remember(recipes, selectedTab) {
        recipes.filterByRole(RecipeTab.entries[selectedTab].role)
    }
    // 食材标签 → 所属食品子分类（肉禽/蛋类/主食粮油…），用于按食材类型分组展示。
    val groupIndex = remember(ingredientGroups) { buildIngredientGroupIndex(ingredientGroups) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            recipes.isEmpty() -> EmptyState(
                title = "还没有菜谱",
                // 去掉「点右下角」这种屏外指路（I5），空态直接给「添加菜谱」。
                message = "把常做的菜记下来，配餐时就能直接选。",
                actionLabel = "添加菜谱",
                onAction = onAdd
            )
            visibleRecipes.isEmpty() -> EmptyState(
                title = "这个分类下还没有菜谱",
                message = "换个标签看看，或在这里再加一道。",
                actionLabel = "添加菜谱",
                onAction = onAdd
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                visibleRecipes.forEach { recipe ->
                    RecipeCard(
                        recipe = recipe,
                        groupIndex = groupIndex,
                        onEdit = { onEdit(recipe) },
                        onDelete = { onDelete(recipe) }
                    )
                }
            }
        }
    }
}

/** 菜谱分类标签：全部 + 四个配餐角色，方便按「主食/蛋白/蔬菜/汤饮」查看与配餐。 */
private enum class RecipeTab(val label: String, val role: DishRole?) {
    ALL("全部", null),
    STAPLE("主食", DishRole.STAPLE),
    PROTEIN("蛋白", DishRole.PROTEIN),
    VEGETABLE("蔬菜", DishRole.VEGETABLE),
    SOUP("汤饮", DishRole.SOUP)
}

/** 按角色筛选菜谱；[role] 为 null 时返回全部。多角色菜在它覆盖的每个分类下都会出现。 */
private fun List<Recipe>.filterByRole(role: DishRole?): List<Recipe> =
    if (role == null) this else filter { role in it.dishRoles }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeCard(
    recipe: Recipe,
    groupIndex: Map<String, String>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    AppSurfaceCard(
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PillTag(
                text = recipe.dishRoles.joinToString("·") { it.label },
                backgroundColor = SurfaceWarmDeep,
                contentColor = TextSecondary
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = recipe.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "编辑菜谱",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "删除菜谱",
                    tint = StatusExpired,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (recipe.ingredients.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            // 按食材类型（蔬菜/肉禽/蛋类/主食粮油/调味品…）分组展示，不把所有食材混在一起。
            groupIngredients(recipe.ingredients, groupIndex).forEach { (group, groupItems) ->
                Text(
                    text = group,
                    // 字号走主题字阶（F6），并满足说明文字 ≥12sp（F7）。
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextHint,
                    modifier = Modifier.padding(top = 4.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    groupItems.forEach { ingredient ->
                        PillTag(
                            text = if (ingredient.optional) "${ingredient.label} · 可选" else ingredient.label,
                            backgroundColor = if (ingredient.optional) TagOrange else TagGreen,
                            contentColor = if (ingredient.optional) TagOrangeText else TagGreenText
                        )
                    }
                }
            }
        }
    }
}

/**
 * 新增 / 编辑菜谱：菜名 + 可增删的食材行。
 * 食材首选从「食品」分类树里挑（按分类匹配，不绑定具体品牌），
 * 也保留手动输入作为分类覆盖不到时的兜底；内置菜谱的关键词在只改名字时会保留。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecipeEditorDialog(
    initial: Recipe?,
    ingredientGroups: List<IngredientGroup>,
    onDismiss: () -> Unit,
    onConfirm: (String, List<RecipeIngredient>, String) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var roles by remember(initial) { mutableStateOf(decodeRoles(initial?.role.orEmpty())) }
    // 食材标签 → 所属食品子分类；不在分类树里的归入「其他」。
    val groupIndex = remember(ingredientGroups) { buildIngredientGroupIndex(ingredientGroups) }
    val ingredients = remember(initial) {
        mutableStateListOf<RecipeIngredient>().apply {
            addAll(initial?.ingredients.orEmpty())
        }
    }
    // 每一味食材的分组：只随「挑分类 / 增删」更新，不随输入的文字实时变化，
    // 避免打字时整行跳到别的分组导致输入框失焦。
    val rowGroups = remember(initial) {
        mutableStateListOf<String>().apply {
            addAll(initial?.ingredients.orEmpty().map { groupIndex[it.label] ?: OTHER_INGREDIENT_GROUP })
        }
    }
    val groupOrder = ingredientGroups.map { it.group } + OTHER_INGREDIENT_GROUP
    // 正在为第几味食材弹出分类选择器；null 表示没有打开。
    var pickingIndex by remember { mutableStateOf<Int?>(null) }

    AppDialog(
        title = if (initial == null) "新增菜谱" else "编辑菜谱",
        subtitle = "食材按分类匹配在库物品，并按类型分组显示；每味可切换「必选 / 可选」，可选食材缺了不影响配餐。",
        onDismissRequest = onDismiss,
        confirmText = "保存",
        confirmEnabled = name.isNotBlank() && ingredients.any { it.label.isNotBlank() },
        onConfirm = { onConfirm(name, ingredients.toList(), encodeRoles(roles)) }
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("菜名") },
            modifier = Modifier.fillMaxWidth()
        )

        Column {
            Text(
                text = "分类（配餐用，可多选）",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.size(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditorSelectionChip(
                    text = "自动",
                    selected = roles.isEmpty(),
                    onClick = { roles = emptySet() }
                )
                DishRole.entries.forEach { option ->
                    val selected = option in roles
                    EditorSelectionChip(
                        text = option.label,
                        selected = selected,
                        onClick = {
                            roles = if (selected) roles - option else roles + option
                        }
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 按食材类型分组：蔬菜 / 肉禽 / 蛋类 / 主食粮油 / 调味品…，分类外的归入「其他」。
            val indicesByGroup = ingredients.indices.groupBy { i ->
                rowGroups.getOrNull(i) ?: OTHER_INGREDIENT_GROUP
            }
            groupOrder.forEach { group ->
                val groupIdx = indicesByGroup[group].orEmpty()
                if (groupIdx.isEmpty()) return@forEach
                Text(
                    text = group,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                groupIdx.forEach row@{ index ->
                    val ingredient = ingredients.getOrNull(index) ?: return@row
                    IngredientEditorRow(
                        index = index,
                        ingredient = ingredient,
                        canPick = ingredientGroups.isNotEmpty(),
                        onLabelChange = { ingredients[index] = ingredient.copy(label = it) },
                        onToggleOptional = {
                            ingredients[index] = ingredient.copy(optional = !ingredient.optional)
                        },
                        onPick = { pickingIndex = index },
                        onRemove = {
                            ingredients.removeAt(index)
                            rowGroups.removeAt(index)
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(999.dp))
                .background(SurfaceWarmDeep)
                .clickable {
                    ingredients.add(RecipeIngredient(label = ""))
                    rowGroups.add(OTHER_INGREDIENT_GROUP)
                    if (ingredientGroups.isNotEmpty()) {
                        pickingIndex = ingredients.lastIndex
                    }
                }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = OrangeStart,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = "添加食材",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = OrangeStart
            )
        }
    }

    pickingIndex?.let { index ->
        IngredientPickerDialog(
            groups = ingredientGroups,
            onDismiss = { pickingIndex = null },
            onPick = { picked ->
                ingredients.getOrNull(index)?.let { current ->
                    ingredients[index] = current.copy(label = picked)
                    if (index < rowGroups.size) {
                        rowGroups[index] = groupIndex[picked] ?: OTHER_INGREDIENT_GROUP
                    }
                }
                pickingIndex = null
            }
        )
    }
}

/** 编辑一行食材：名称 + 「必选/可选」切换 + 从分类挑选 + 移除。 */
@Composable
private fun IngredientEditorRow(
    index: Int,
    ingredient: RecipeIngredient,
    canPick: Boolean,
    onLabelChange: (String) -> Unit,
    onToggleOptional: () -> Unit,
    onPick: () -> Unit,
    onRemove: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = ingredient.label,
            onValueChange = onLabelChange,
            singleLine = true,
            label = { Text("食材 ${index + 1}") },
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .padding(start = 6.dp)
                // 触达面积达标（F7）：可选/必选切换热区补足到 48dp，视觉尺寸不变。
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(999.dp))
                .background(if (ingredient.optional) TagOrange else SurfaceWarmDeep)
                .clickable(onClick = onToggleOptional)
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(
                text = if (ingredient.optional) "可选" else "必选",
                // 字号走主题字阶（F6）：labelMedium 为 12sp，满足 ≥12sp（F7）。
                style = MaterialTheme.typography.labelMedium,
                color = if (ingredient.optional) TagOrangeText else TextSecondary
            )
        }
        if (canPick) {
            IconButton(onClick = onPick) {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = "从分类选择食材",
                    tint = OrangeStart
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "移除食材",
                tint = TextHint
            )
        }
    }
}

/** 「其他」分组名：食材不在食品分类树里时的兜底分组。 */
private const val OTHER_INGREDIENT_GROUP = "其他"

/** 食材标签 → 所属食品子分类（肉禽 / 蛋类 / 主食粮油…）。 */
private fun buildIngredientGroupIndex(groups: List<IngredientGroup>): Map<String, String> {
    val index = linkedMapOf<String, String>()
    groups.forEach { group ->
        group.options.forEach { option -> index.putIfAbsent(option, group.group) }
    }
    return index
}

/** 按食材类型把食材分组，保持分类树的顺序，「其他」放最后；空分组不返回。 */
private fun groupIngredients(
    ingredients: List<RecipeIngredient>,
    groupIndex: Map<String, String>
): List<Pair<String, List<RecipeIngredient>>> {
    val grouped = ingredients.groupBy { groupIndex[it.label] ?: OTHER_INGREDIENT_GROUP }
    val order = groupIndex.values.toList().distinct() + OTHER_INGREDIENT_GROUP
    return order.mapNotNull { group ->
        grouped[group]?.takeIf { it.isNotEmpty() }?.let { group to it }
    }
}

/** 食材分类选择器：按食品子分类分组（肉禽 / 蛋类 / 调味品…），点具体分类即选中。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IngredientPickerDialog(
    groups: List<IngredientGroup>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    AppDialog(
        title = "选择食材分类",
        subtitle = "按分类匹配，不指定品牌或具体物品。",
        onDismissRequest = onDismiss,
        dismissText = "取消"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            groups.forEach { group ->
                Text(
                    text = group.group,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    group.options.forEach { option ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(SurfaceWarmDeep)
                                .clickable { onPick(option) }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = option,
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}
