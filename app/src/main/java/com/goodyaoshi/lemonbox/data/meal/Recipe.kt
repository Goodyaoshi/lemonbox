package com.goodyaoshi.lemonbox.data.meal

import kotlinx.serialization.Serializable

/**
 * 菜谱里的一味食材。
 *
 * @param label 展示用名称，例如「番茄」。
 * @param keywords 命中关键词，会与物品名称、分类名做包含匹配；留空时退回用 [label]。
 * @param optional 是否为可选配料（例如姜蒜），缺失时不计入「还差什么」。
 */
@Serializable
data class RecipeIngredient(
    val label: String,
    val keywords: List<String> = emptyList(),
    val optional: Boolean = false
) {
    /** 实际参与匹配的关键词；用户自建菜谱只填名称时就用名称本身。 */
    val matchKeywords: List<String>
        get() = keywords.filter { it.isNotBlank() }.ifEmpty { listOf(label) }
}

/**
 * 一道菜谱。内置菜谱编号 1 起，用户新增的编号从 100 起（见 AppPreferences.RECIPE_ID_BASE）。
 *
 * @param role 菜品角色 key，多个角色用「,」分隔（如 `STAPLE,PROTEIN,VEGETABLE` 表示饺子自带主食/蛋白/蔬菜）；
 *   为空时按菜名自动判定（见 [DishRoleClassifier]），保证老数据与用户自建菜谱也能正确归类。
 */
@Serializable
data class Recipe(
    val id: Long,
    val name: String,
    val ingredients: List<RecipeIngredient> = emptyList(),
    val role: String = ""
) {
    /** 这道菜覆盖的角色：优先用显式 [role]（可多个），缺省时按菜名推断。 */
    val dishRoles: Set<DishRole>
        get() = decodeRoles(role).ifEmpty { DishRoleClassifier.byName(name) }
}

/** 角色 key 的分隔符：配合 [Recipe.role] 让一道菜同时属于多个角色。 */
const val RECIPE_ROLE_SEPARATOR = ","

/** 把 `STAPLE,PROTEIN` 这样的角色串解析成角色集合；忽略未知 key，按枚举顺序排列。 */
fun decodeRoles(role: String): Set<DishRole> {
    if (role.isBlank()) return emptySet()
    val keys = role.split(RECIPE_ROLE_SEPARATOR).map { it.trim() }.toSet()
    return DishRole.entries.filterTo(linkedSetOf()) { it.name in keys }
}

/** 把角色集合编码成持久化的角色串（按枚举顺序，保证与 [decodeRoles] 对称）。 */
fun encodeRoles(roles: Set<DishRole>): String =
    DishRole.entries.filter { it in roles }.joinToString(RECIPE_ROLE_SEPARATOR) { it.name }

/** 内置菜谱里标注「一道顶多个角色」时的便捷写法。 */
private fun roleOf(vararg roles: DishRole): String = encodeRoles(roles.toSet())

/**
 * 内置菜谱的最大编号。用「最大编号」而不是「数量」作为增量种子的水位线，
 * 这样下架部分内置菜谱后，之后再新增的菜谱仍能被正确补入。
 */
const val BUILT_IN_RECIPE_MAX_ID = 44L

/**
 * 内置的一版家常菜谱（参考 https://github.com/Gar-b-age/CookLikeHOC 的常见家常菜），
 * 首次启动写入偏好设置，之后完全由用户自由增删改；升级时会自动补入新增的内置菜谱。
 * 主料都在两味以内，避免只有调料也能凑出一道菜。
 * 一道菜能同时顶多个角色的（饺子/蛋炒饭/带肉的汤…）用 [roleOf] 标注多个角色，配餐时不会重复配。
 */
val DEFAULT_RECIPES: List<Recipe> = listOf(
    // —— 蛋白质（肉 / 蛋 / 豆制品）——
    Recipe(
        id = 1L,
        name = "番茄炒蛋",
        role = roleOf(DishRole.PROTEIN, DishRole.VEGETABLE),
        ingredients = listOf(
            RecipeIngredient("番茄", listOf("番茄", "西红柿")),
            RecipeIngredient("鸡蛋", listOf("鸡蛋", "蛋"))
        )
    ),
    Recipe(
        id = 2L,
        name = "青椒炒肉",
        role = roleOf(DishRole.PROTEIN, DishRole.VEGETABLE),
        ingredients = listOf(
            RecipeIngredient("青椒", listOf("青椒", "尖椒", "彩椒")),
            RecipeIngredient("猪肉", listOf("猪肉", "五花肉", "里脊", "肉丝", "肉片", "肉"))
        )
    ),
    Recipe(
        id = 3L,
        name = "蒜蓉青菜",
        role = DishRole.VEGETABLE.name,
        ingredients = listOf(
            RecipeIngredient("青菜", listOf("青菜", "小白菜", "生菜", "菠菜", "油菜", "白菜", "西兰花")),
            RecipeIngredient("蒜", listOf("蒜", "大蒜"), optional = true)
        )
    ),
    Recipe(
        id = 4L,
        name = "鸡蛋面",
        role = roleOf(DishRole.STAPLE, DishRole.PROTEIN),
        ingredients = listOf(
            RecipeIngredient("面条", listOf("面条", "挂面", "拉面", "乌冬", "意面", "刀削面")),
            RecipeIngredient("鸡蛋", listOf("鸡蛋", "蛋"))
        )
    ),
    Recipe(
        id = 5L,
        name = "麻婆豆腐",
        role = DishRole.PROTEIN.name,
        ingredients = listOf(
            RecipeIngredient("豆腐", listOf("豆腐")),
            RecipeIngredient("肉末", listOf("肉末", "肉", "猪肉", "牛肉"))
        )
    ),
    Recipe(
        id = 6L,
        name = "紫菜蛋花汤",
        role = roleOf(DishRole.SOUP, DishRole.PROTEIN),
        ingredients = listOf(
            RecipeIngredient("紫菜", listOf("紫菜", "海带", "裙带菜")),
            RecipeIngredient("鸡蛋", listOf("鸡蛋", "蛋"))
        )
    ),
    Recipe(
        id = 7L,
        name = "清炒时蔬",
        role = DishRole.VEGETABLE.name,
        ingredients = listOf(
            RecipeIngredient("时蔬", listOf("菜", "瓜", "胡萝卜", "豆角", "芹菜", "藕", "笋")),
            RecipeIngredient("蒜", listOf("蒜", "大蒜"), optional = true)
        )
    ),
    Recipe(
        id = 8L,
        name = "红烧肉",
        role = DishRole.PROTEIN.name,
        ingredients = listOf(
            RecipeIngredient("五花肉", listOf("五花肉", "猪肉", "肉")),
            RecipeIngredient("姜", listOf("姜"), optional = true)
        )
    ),
    Recipe(
        id = 9L,
        name = "糖醋排骨",
        role = DishRole.PROTEIN.name,
        ingredients = listOf(
            RecipeIngredient("排骨", listOf("排骨", "肋排")),
            RecipeIngredient("醋", listOf("醋", "陈醋", "香醋"), optional = true)
        )
    ),
    Recipe(
        id = 10L,
        name = "宫保鸡丁",
        role = DishRole.PROTEIN.name,
        ingredients = listOf(
            RecipeIngredient("鸡肉", listOf("鸡肉", "鸡胸", "鸡腿", "鸡")),
            RecipeIngredient("花生", listOf("花生", "花生米"), optional = true)
        )
    ),
    Recipe(
        id = 11L,
        name = "青椒肉丝",
        role = roleOf(DishRole.PROTEIN, DishRole.VEGETABLE),
        ingredients = listOf(
            RecipeIngredient("青椒", listOf("青椒", "尖椒")),
            RecipeIngredient("猪肉", listOf("猪肉", "里脊", "肉丝", "肉"))
        )
    ),
    Recipe(
        id = 12L,
        name = "农家小炒肉",
        role = roleOf(DishRole.PROTEIN, DishRole.VEGETABLE),
        ingredients = listOf(
            RecipeIngredient("猪肉", listOf("猪肉", "五花肉", "肉")),
            RecipeIngredient("青椒", listOf("青椒", "尖椒"))
        )
    ),
    Recipe(
        id = 15L,
        name = "酸菜鱼",
        role = DishRole.PROTEIN.name,
        ingredients = listOf(
            RecipeIngredient("鱼", listOf("鱼", "草鱼", "黑鱼")),
            RecipeIngredient("酸菜", listOf("酸菜", "泡菜"))
        )
    ),
    Recipe(
        id = 16L,
        name = "可乐鸡翅",
        role = DishRole.PROTEIN.name,
        ingredients = listOf(
            RecipeIngredient("鸡翅", listOf("鸡翅", "翅中", "翅根"))
        )
    ),
    Recipe(
        id = 18L,
        name = "番茄牛腩",
        role = roleOf(DishRole.PROTEIN, DishRole.VEGETABLE),
        ingredients = listOf(
            RecipeIngredient("牛肉", listOf("牛肉", "牛腩")),
            RecipeIngredient("番茄", listOf("番茄", "西红柿"))
        )
    ),
    Recipe(
        id = 19L,
        name = "虾仁滑蛋",
        role = DishRole.PROTEIN.name,
        ingredients = listOf(
            RecipeIngredient("虾仁", listOf("虾", "虾仁", "基围虾")),
            RecipeIngredient("鸡蛋", listOf("鸡蛋", "蛋"))
        )
    ),
    Recipe(
        id = 20L,
        name = "家常豆腐",
        role = DishRole.PROTEIN.name,
        ingredients = listOf(
            RecipeIngredient("豆腐", listOf("豆腐")),
            RecipeIngredient("青椒", listOf("青椒", "尖椒"), optional = true)
        )
    ),
    Recipe(
        id = 41L,
        name = "胡萝卜炒肉",
        role = roleOf(DishRole.PROTEIN, DishRole.VEGETABLE),
        ingredients = listOf(
            RecipeIngredient("胡萝卜", listOf("胡萝卜", "红萝卜")),
            RecipeIngredient("猪肉", listOf("猪肉", "里脊", "肉丝", "肉片", "肉"))
        )
    ),

    // —— 蔬菜 ——
    Recipe(
        id = 21L,
        name = "蒜蓉西兰花",
        role = DishRole.VEGETABLE.name,
        ingredients = listOf(
            RecipeIngredient("西兰花", listOf("西兰花", "菜花", "花椰菜")),
            RecipeIngredient("蒜", listOf("蒜", "大蒜"), optional = true)
        )
    ),
    Recipe(
        id = 22L,
        name = "手撕包菜",
        role = DishRole.VEGETABLE.name,
        ingredients = listOf(
            RecipeIngredient("包菜", listOf("包菜", "圆白菜", "卷心菜", "甘蓝"))
        )
    ),
    Recipe(
        id = 23L,
        name = "酸辣土豆丝",
        role = DishRole.VEGETABLE.name,
        ingredients = listOf(
            RecipeIngredient("土豆", listOf("土豆", "马铃薯")),
            RecipeIngredient("辣椒", listOf("辣椒", "青椒", "干辣椒"), optional = true)
        )
    ),
    Recipe(
        id = 24L,
        name = "干煸四季豆",
        role = DishRole.VEGETABLE.name,
        ingredients = listOf(
            RecipeIngredient("四季豆", listOf("四季豆", "豆角", "豇豆"))
        )
    ),
    Recipe(
        id = 25L,
        name = "地三鲜",
        role = DishRole.VEGETABLE.name,
        ingredients = listOf(
            RecipeIngredient("土豆", listOf("土豆", "马铃薯")),
            RecipeIngredient("茄子", listOf("茄子")),
            RecipeIngredient("青椒", listOf("青椒", "尖椒"), optional = true)
        )
    ),
    Recipe(
        id = 26L,
        name = "上汤娃娃菜",
        role = DishRole.VEGETABLE.name,
        ingredients = listOf(
            RecipeIngredient("娃娃菜", listOf("娃娃菜", "白菜", "娃娃"))
        )
    ),
    Recipe(
        id = 27L,
        name = "醋溜白菜",
        role = DishRole.VEGETABLE.name,
        ingredients = listOf(
            RecipeIngredient("白菜", listOf("白菜", "大白菜"))
        )
    ),
    Recipe(
        id = 28L,
        name = "番茄炒花菜",
        role = DishRole.VEGETABLE.name,
        ingredients = listOf(
            RecipeIngredient("花菜", listOf("花菜", "菜花", "花椰菜")),
            RecipeIngredient("番茄", listOf("番茄", "西红柿"))
        )
    ),

    // —— 主食（碳水）——
    Recipe(
        id = 29L,
        name = "蛋炒饭",
        role = roleOf(DishRole.STAPLE, DishRole.PROTEIN),
        ingredients = listOf(
            RecipeIngredient("米饭", listOf("米饭", "剩饭", "米")),
            RecipeIngredient("鸡蛋", listOf("鸡蛋", "蛋"))
        )
    ),
    Recipe(
        id = 30L,
        name = "阳春面",
        role = DishRole.STAPLE.name,
        ingredients = listOf(
            RecipeIngredient("面条", listOf("面条", "挂面", "拉面"))
        )
    ),
    Recipe(
        id = 32L,
        name = "猪肉白菜饺子",
        role = roleOf(DishRole.STAPLE, DishRole.PROTEIN, DishRole.VEGETABLE),
        ingredients = listOf(
            RecipeIngredient("饺子皮", listOf("饺子", "水饺", "饺子皮")),
            RecipeIngredient("猪肉", listOf("猪肉", "肉"))
        )
    ),
    // 米饭/白粥：既作为默认主食，也进菜谱库，按「米」做食材检查（没米会提示缺米）；
    // 但点「做了」时主食角色不扣食材（一袋米够吃很多顿）。
    Recipe(
        id = 43L,
        name = "米饭",
        role = DishRole.STAPLE.name,
        ingredients = listOf(
            RecipeIngredient("大米", listOf("大米", "米", "珍珠米"))
        )
    ),
    Recipe(
        id = 44L,
        name = "白粥",
        role = DishRole.STAPLE.name,
        ingredients = listOf(
            RecipeIngredient("大米", listOf("大米", "米", "珍珠米"))
        )
    ),

    // —— 汤饮（干湿搭配）——
    Recipe(
        id = 35L,
        name = "西红柿鸡蛋汤",
        role = roleOf(DishRole.SOUP, DishRole.PROTEIN),
        ingredients = listOf(
            RecipeIngredient("番茄", listOf("番茄", "西红柿")),
            RecipeIngredient("鸡蛋", listOf("鸡蛋", "蛋"))
        )
    ),
    Recipe(
        id = 36L,
        name = "冬瓜排骨汤",
        role = roleOf(DishRole.SOUP, DishRole.PROTEIN),
        ingredients = listOf(
            RecipeIngredient("冬瓜", listOf("冬瓜")),
            RecipeIngredient("排骨", listOf("排骨", "肋排"))
        )
    ),
    Recipe(
        id = 37L,
        name = "玉米排骨汤",
        role = roleOf(DishRole.SOUP, DishRole.PROTEIN),
        ingredients = listOf(
            RecipeIngredient("玉米", listOf("玉米")),
            RecipeIngredient("排骨", listOf("排骨", "肋排"))
        )
    ),
    Recipe(
        id = 38L,
        name = "海带排骨汤",
        role = roleOf(DishRole.SOUP, DishRole.PROTEIN),
        ingredients = listOf(
            RecipeIngredient("海带", listOf("海带", "裙带菜")),
            RecipeIngredient("排骨", listOf("排骨", "肋排"))
        )
    ),
    Recipe(
        id = 39L,
        name = "银耳莲子羹",
        role = DishRole.SOUP.name,
        ingredients = listOf(
            RecipeIngredient("银耳", listOf("银耳", "雪耳")),
            RecipeIngredient("莲子", listOf("莲子"))
        )
    ),
    Recipe(
        id = 40L,
        name = "小米粥",
        role = roleOf(DishRole.SOUP, DishRole.STAPLE),
        ingredients = listOf(
            RecipeIngredient("小米", listOf("小米"))
        )
    )
)