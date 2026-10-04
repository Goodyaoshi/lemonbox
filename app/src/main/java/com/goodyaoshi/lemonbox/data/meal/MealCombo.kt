package com.goodyaoshi.lemonbox.data.meal

import kotlinx.serialization.Serializable

/**
 * 一餐里一道菜承担的角色。按「营养均衡」的标准，一顿正餐/简餐至少要有
 * 主食（碳水）+ 蛋白质（肉/蛋/豆制品）；蔬菜（维生素/纤维）与汤饮（干湿搭配）尽量配齐但可选。
 * 一道菜可以同时承担多个角色（如饺子自带主食/蛋白/蔬菜）。
 */
enum class DishRole(val label: String) {
    STAPLE("主食"),
    PROTEIN("蛋白"),
    VEGETABLE("蔬菜"),
    SOUP("汤饮")
}

/** 一餐的持久化描述：一道菜要么指向菜谱库（[recipeId]），要么是基础主食/基础汤饮（[label]）。 */
@Serializable
data class MealDishSpec(
    val role: String,
    val recipeId: Long? = null,
    val label: String = ""
) {
    val dishRole: DishRole
        get() = DishRole.entries.firstOrNull { it.name == role } ?: DishRole.VEGETABLE
}

/** 某一天的一餐（可手动编辑后覆盖保存）。 */
@Serializable
data class MealSpec(
    val dishes: List<MealDishSpec> = emptyList()
) {
    /** 这道菜若来自菜谱库，用 [recipes] 换回名字；否则用基础名称。 */
    fun dishName(spec: MealDishSpec, recipes: Map<Long, Recipe>): String =
        spec.recipeId?.let { recipes[it]?.name } ?: spec.label.ifBlank { spec.dishRole.label }
}

/** 展示用的一餐：每道菜带上「能用/还差」的食材评估。 */
data class MealDish(
    val role: DishRole,
    val label: String,
    val recipeId: Long?,
    val suggestion: RecipeSuggestion?,
    /** 这道菜实际覆盖的角色（多角色菜可能多于 [role] 一个）；为空时回退到 [role]。 */
    val roles: Set<DishRole> = emptySet()
) {
    /** 展示用的角色集合：多角色菜会把覆盖到的角色都标出来。 */
    val displayRoles: Set<DishRole> get() = roles.ifEmpty { setOf(role) }
}

/** 展示用的一餐组合。 */
data class MealCombo(val dishes: List<MealDish>) {
    /** 「番茄炒蛋 + 蒜蓉青菜 + 紫菜蛋花汤」这样的组合标题。 */
    val title: String
        get() = dishes.joinToString(" + ") { it.label }

    /** 这一餐还缺的主料（去重），供「做了」前提示与待买联动。 */
    val missing: List<String>
        get() = dishes.flatMap { it.suggestion?.missing.orEmpty() }.distinct()
}

/**
 * 按菜名把菜谱归入角色，作为显式 [Recipe.role] 缺省时的兜底。
 * 只认菜名，避免「豆角/豆芽」这类蔬菜被食材误判成蛋白质。
 * 菜名同时命中多类关键词时会返回多个角色（如「蛋炒饭」既是主食也是蛋白），
 * 一类都不命中时按蔬菜兜底。用户自建菜谱若不选角色，也走同一套规则。
 */
object DishRoleClassifier {

    private val SOUP_KEYWORDS = listOf("汤", "羹", "粥", "饮", "奶昔", "浆", "汁")
    private val STAPLE_KEYWORDS =
        listOf("饭", "面", "饺", "馒", "包", "馍", "饼", "粉", "米线", "吐司", "糕", "卷")
    private val PROTEIN_KEYWORDS =
        listOf("肉", "鸡", "牛", "猪", "羊", "鱼", "虾", "蟹", "蛋", "豆腐", "排骨", "培根", "肠", "火腿", "鸭")

    fun byName(name: String): Set<DishRole> {
        val roles = linkedSetOf<DishRole>()
        if (SOUP_KEYWORDS.any { name.contains(it) }) roles += DishRole.SOUP
        if (STAPLE_KEYWORDS.any { name.contains(it) }) roles += DishRole.STAPLE
        if (PROTEIN_KEYWORDS.any { name.contains(it) }) roles += DishRole.PROTEIN
        return roles.ifEmpty { setOf(DishRole.VEGETABLE) }
    }
}

/**
 * 默认固定的两样主食：每天各上一份。它们同时是菜谱库里的菜谱，
 * 参与食材检查（没米会提示缺米）；但点「做了」时主食角色不扣食材。
 */
val BASE_STAPLES = listOf("米饭", "白粥")

/** 默认固定的蔬菜。 */
const val DEFAULT_VEGETABLE_NAME = "清炒时蔬"

/** 没有汤饮菜谱时用到的基础汤饮名（仅手动编辑时可加，默认不配汤饮）。 */
const val BASE_SOUP_LABEL = "清汤"