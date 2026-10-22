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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.meal.DeductPolicy
import com.goodyaoshi.lemonbox.data.meal.DishRole
import com.goodyaoshi.lemonbox.data.meal.Recipe
import com.goodyaoshi.lemonbox.data.meal.RecipeIngredient
import com.goodyaoshi.lemonbox.data.meal.RecipeStep
import com.goodyaoshi.lemonbox.data.meal.decodeRoles
import com.goodyaoshi.lemonbox.data.meal.encodeRoles
import com.goodyaoshi.lemonbox.data.meal.formatAmount
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorSelectionChip
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.LemonFab
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SegmentedTabs
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
                .navigationBarsPadding()
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
                    // 字号走主题字阶（F6）：titleLarge 为 18sp。
                    style = MaterialTheme.typography.titleLarge,
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

        LemonFab(
            icon = Icons.Default.Add,
            contentDescription = "新增菜谱",
            onClick = {
                editorTarget = null
                showEditor = true
            },
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }

    if (showEditor) {
        RecipeEditorDialog(
            initial = editorTarget,
            ingredientGroups = ingredientGroups,
            onDismiss = {
                showEditor = false
                editorTarget = null
            },
            onConfirm = { recipe ->
                // 新增与编辑都收完整 Recipe：编号、syncId、内置身份由仓库层决定。
                if (editorTarget == null) {
                    viewModel.addRecipe(recipe)
                } else {
                    viewModel.updateRecipe(recipe)
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
                // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                style = MaterialTheme.typography.bodyMedium,
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
        // 圆角走形状令牌（F6）：extraLarge 为 24dp。
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        shadowElevation = 12.dp
    ) {
        // 展开状态按菜谱 id 记住：重绘（切标签、同步刷新）时不会把用户正看着的做法收起来。
        var expanded by remember(recipe.id) { mutableStateOf(false) }
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
                // 字号走主题字阶（F6）：titleMedium 为 16sp。
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                // 长菜名限行截断（P2）：此前无 maxLines，长名会把编辑/删除按钮挤出屏。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
        // 份量 / 总时长 / 难度：一眼判断「今晚做不做得起」。
        val summary = buildList {
            if (recipe.baseYield > 0) add("${recipe.baseYield}${recipe.yieldUnit}")
            if (recipe.totalMin > 0) add("${recipe.totalMin} 分钟")
            if (recipe.difficulty.isNotBlank()) add(recipe.difficulty)
        }
        if (summary.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = TextHint
            )
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
                        // 带上用量：只有名字没有分量的「食材」教不会人做菜。
                        val shown = "${ingredient.label} ${ingredient.amountText()}"
                        PillTag(
                            text = if (ingredient.optional) "$shown · 可选" else shown,
                            backgroundColor = if (ingredient.optional) TagOrange else TagGreen,
                            contentColor = if (ingredient.optional) TagOrangeText else TagGreenText
                        )
                    }
                }
            }
        }
        if (recipe.steps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (expanded) "收起做法" else "查看做法（${recipe.steps.size} 步）",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary,
                // 热区补到 48dp（F5）；整行可点，不用精确点文字。
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .minimumInteractiveComponentSize()
                    .padding(vertical = 4.dp)
            )
            if (expanded) {
                recipe.steps.forEachIndexed { index, step ->
                    Row(modifier = Modifier.padding(top = 6.dp)) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextHint,
                            modifier = Modifier.width(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = step.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                            if (step.tip.isNotBlank()) {
                                Text(
                                    text = "提示：${step.tip}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextHint,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
                if (recipe.tips.isNotBlank()) {
                    Text(
                        text = "小贴士：${recipe.tips}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 新增 / 编辑菜谱：基本信息 + 带用量的食材 + 做法步骤。
 *
 * 只填菜名和食材名也能保存（老用户习惯不变），但填了用量与步骤之后，
 * 这道菜才真正能教人做、能按份数缩放、能给扣料提供依据。
 * 食材首选从「食品」分类树里挑（按分类匹配，不绑定具体品牌），
 * 也保留手动输入作为分类覆盖不到时的兜底；内置菜谱的关键词在只改名字时会保留。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecipeEditorDialog(
    initial: Recipe?,
    ingredientGroups: List<IngredientGroup>,
    onDismiss: () -> Unit,
    onConfirm: (Recipe) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var roles by remember(initial) { mutableStateOf(decodeRoles(initial?.role.orEmpty())) }
    var description by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }
    // 份量与时长用文本暂存：输入过程中「2.」这种半截内容不该被强行转成数字。
    var yieldText by remember(initial) {
        mutableStateOf(initial?.baseYield?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var yieldUnit by remember(initial) { mutableStateOf(initial?.yieldUnit ?: "人份") }
    var prepText by remember(initial) {
        mutableStateOf(initial?.prepMin?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var cookText by remember(initial) {
        mutableStateOf(initial?.cookMin?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var difficulty by remember(initial) { mutableStateOf(initial?.difficulty.orEmpty()) }
    var tips by remember(initial) { mutableStateOf(initial?.tips.orEmpty()) }
    val steps = remember(initial) {
        mutableStateListOf<RecipeStep>().apply { addAll(initial?.steps.orEmpty()) }
    }
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
        subtitle = "用量填数字才能按份数缩放；油盐等辅料默认不扣库存。",
        onDismissRequest = onDismiss,
        confirmText = "保存",
        confirmEnabled = name.isNotBlank() && ingredients.any { it.label.isNotBlank() },
        onConfirm = {
            val base = initial ?: Recipe(id = 0L, name = "")
            onConfirm(
                base.copy(
                    name = name.trim(),
                    role = encodeRoles(roles),
                    description = description.trim(),
                    baseYield = yieldText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    yieldUnit = yieldUnit.trim().ifBlank { "人份" },
                    prepMin = prepText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    cookMin = cookText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    difficulty = difficulty,
                    steps = steps.toList(),
                    tips = tips.trim()
                )
            )
        }
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("菜名") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            singleLine = true,
            label = { Text("一句话介绍（可选）") },
            modifier = Modifier.fillMaxWidth()
        )

        // —— 份量与时长：缩放的基准，也是「今晚做不做得起」的判断依据 ——
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = yieldText,
                onValueChange = { text -> yieldText = text.filter { it.isDigit() }.take(3) },
                singleLine = true,
                label = { Text("份量") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = yieldUnit,
                onValueChange = { yieldUnit = it.take(6) },
                singleLine = true,
                label = { Text("单位") },
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = prepText,
                onValueChange = { text -> prepText = text.filter { it.isDigit() }.take(4) },
                singleLine = true,
                label = { Text("备料分钟") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = cookText,
                onValueChange = { text -> cookText = text.filter { it.isDigit() }.take(4) },
                singleLine = true,
                label = { Text("烹饪分钟") },
                modifier = Modifier.weight(1f)
            )
        }
        Column {
            Text(
                text = "难度（可选）",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.size(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("简单", "中等", "较难").forEach { option ->
                    EditorSelectionChip(
                        text = option,
                        selected = difficulty == option,
                        onClick = { difficulty = if (difficulty == option) "" else option }
                    )
                }
            }
        }

        Column {
            Text(
                text = "分类（配餐用，可多选）",
                // 字号走主题字阶（F6）：labelLarge 为 14sp。
                style = MaterialTheme.typography.labelLarge,
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
            // 不再单独滚动：对话框整体已经可滚，嵌套滚动会让手指在食材区里滚不动。
            modifier = Modifier.fillMaxWidth(),
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
                    // 字号走主题字阶（F6）：bodySmall 为 12sp。
                    style = MaterialTheme.typography.bodySmall,
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
                        onChange = { updated -> ingredients[index] = updated },
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
                // 胶囊用 CircleShape（F6）：形状令牌化，替换内联 999dp 圆角。
                .clip(CircleShape)
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
                // 字号走主题字阶（F6）：labelLarge 为 14sp。
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = OrangeStart
            )
        }

        // —— 做法：只填食材是「配菜标签」，填了步骤才教得会人 ——
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "做法步骤（可选）",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
            steps.forEachIndexed { index, step ->
                StepEditorRow(
                    index = index,
                    step = step,
                    onChange = { updated -> if (index < steps.size) steps[index] = updated },
                    onRemove = { if (index < steps.size) steps.removeAt(index) }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(SurfaceWarmDeep)
                    .clickable { steps.add(RecipeStep(text = "")) }
                    .minimumInteractiveComponentSize()
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
                    text = "添加一步",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = OrangeStart
                )
            }
        }

        OutlinedTextField(
            value = tips,
            onValueChange = { tips = it },
            label = { Text("小贴士（可选）") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
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

/** 编辑一行食材：名称 + 用量/单位 + 扣料策略 + 「必选/可选」+ 从分类挑选 + 移除。 */
@Composable
private fun IngredientEditorRow(
    index: Int,
    ingredient: RecipeIngredient,
    canPick: Boolean,
    onChange: (RecipeIngredient) -> Unit,
    onPick: () -> Unit,
    onRemove: () -> Unit
) {
    // 用量单独存文本：直接回读 amount 会把用户输入的「2.」立刻格式化成「2」，
    // 小数点根本打不进去。以食材名为 key，换一道菜时自然重置。
    var amountText by remember(index, ingredient.label) {
        mutableStateOf(ingredient.amount?.let { formatAmount(it) } ?: "")
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ingredient.label,
                onValueChange = { onChange(ingredient.copy(label = it)) },
                singleLine = true,
                label = { Text("食材 ${index + 1}") },
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    // 触达面积达标（F7）：可选/必选切换热区补足到 48dp，视觉尺寸不变。
                    .minimumInteractiveComponentSize()
                    // 胶囊用 CircleShape（F6）：形状令牌化。
                    .clip(CircleShape)
                    .background(if (ingredient.optional) TagOrange else SurfaceWarmDeep)
                    .clickable { onChange(ingredient.copy(optional = !ingredient.optional)) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = amountText,
                onValueChange = { text ->
                    // 只接受数字与一个小数点：别的字符留着也解析不出来，不如直接挡掉。
                    val cleaned = text.filter { it.isDigit() || it == '.' }.take(8)
                    amountText = cleaned
                    onChange(ingredient.copy(amount = cleaned.toDoubleOrNull()))
                },
                singleLine = true,
                label = { Text("用量") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = ingredient.unit,
                onValueChange = { onChange(ingredient.copy(unit = it.take(4))) },
                singleLine = true,
                label = { Text("单位") },
                modifier = Modifier.weight(1f)
            )
            val policy = DeductPolicy.of(ingredient.deductPolicy)
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    // 不扣的辅料用中性色，避免满屏绿色让人以为都要扣库存。
                    .background(if (policy == DeductPolicy.NEVER) SurfaceWarmDeep else TagGreen)
                    .clickable {
                        val next = when (policy) {
                            DeductPolicy.PORTION -> DeductPolicy.PACK
                            DeductPolicy.PACK -> DeductPolicy.NEVER
                            else -> DeductPolicy.PORTION
                        }
                        onChange(ingredient.copy(deductPolicy = next))
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = when (policy) {
                        DeductPolicy.PACK -> "按件扣"
                        DeductPolicy.NEVER -> "不扣"
                        else -> "按量扣"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (policy == DeductPolicy.NEVER) TextHint else TagGreenText
                )
            }
        }
    }
}

/** 编辑一步做法：正文 + 可选计时 + 移除。 */
@Composable
private fun StepEditorRow(
    index: Int,
    step: RecipeStep,
    onChange: (RecipeStep) -> Unit,
    onRemove: () -> Unit
) {
    var secondsText by remember(index) {
        mutableStateOf(step.durationSec.takeIf { it > 0 }?.toString() ?: "")
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = step.text,
                onValueChange = { onChange(step.copy(text = it)) },
                label = { Text("第 ${index + 1} 步") },
                minLines = 2,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "移除这一步",
                    tint = TextHint
                )
            }
        }
        OutlinedTextField(
            value = secondsText,
            onValueChange = { text ->
                val cleaned = text.filter { it.isDigit() }.take(5)
                secondsText = cleaned
                onChange(step.copy(durationSec = cleaned.toIntOrNull() ?: 0))
            },
            singleLine = true,
            label = { Text("计时秒数（可选）") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = step.tip,
            onValueChange = { onChange(step.copy(tip = it)) },
            singleLine = true,
            label = { Text("这一步的要点（可选）") },
            modifier = Modifier.fillMaxWidth()
        )
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
                    // 字号走主题字阶（F6）：labelLarge 为 14sp。
                    style = MaterialTheme.typography.labelLarge,
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
                                // 胶囊用 CircleShape（F6）：形状令牌化。
                                .clip(CircleShape)
                                .background(SurfaceWarmDeep)
                                .clickable { onPick(option) }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = option,
                                // 字号走主题字阶（F6）：labelLarge 为 14sp。
                                style = MaterialTheme.typography.labelLarge,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}
