package com.goodyaoshi.lemonbox.data.meal

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 菜谱里的一味食材。
 *
 * @param label 展示用名称，例如「番茄」。
 * @param keywords 命中关键词，会与物品名称、分类名做包含匹配；留空时退回用 [label]。
 * @param optional 是否为可选配料（例如姜蒜），缺失时不计入「还差什么」。
 * @param rawText 原文用量（「2 个」「一小把」「适量」）。解析失败时也保留，信息不丢。
 * @param amount 结构化用量；null 表示「适量/少许」这类不可计量的量，不参与缩放。
 * @param unit 计量单位（克/毫升/个/瓣/勺…）；空串表示按件。
 * @param deductPolicy 扣料策略，取值见 [DeductPolicy]；空串表示按名称推断。
 * @param prepNote 预处理说明（「切块」「去皮」），只展示，不参与匹配。
 */
@Serializable
data class RecipeIngredient(
    val label: String,
    val keywords: List<String> = emptyList(),
    val optional: Boolean = false,
    val rawText: String = "",
    val amount: Double? = null,
    val unit: String = "",
    val deductPolicy: String = "",
    val prepNote: String = ""
) {
    /** 实际参与匹配的关键词；用户自建菜谱只填名称时就用名称本身。 */
    val matchKeywords: List<String>
        get() = keywords.filter { it.isNotBlank() }.ifEmpty { listOf(label) }

    /** 是否可参与份量缩放；「适量」这类没有数值的不缩。 */
    val scalable: Boolean
        get() = amount != null && amount > 0

    /** 展示用量：优先结构化数值，其次原文。 */
    fun amountText(factor: Double = 1.0): String {
        if (!scalable) return rawText.ifBlank { "适量" }
        val value = amount!! * factor
        val shown = formatAmount(value)
        return if (unit.isBlank()) shown else "$shown$unit"
    }
}

/**
 * 扣料策略（方案 4.6 的规则矩阵）。
 *
 * 用常量而不是枚举，是为了让旧数据里空串、以及 Web 端写入的未知值都能安全落库；
 * 未识别的值一律按 [PORTION] 处理。
 */
object DeductPolicy {
    /** 可计量主料：按菜谱用量扣，同维度单位换算。 */
    const val PORTION = "PORTION"

    /** 整袋/整盒：按件扣，允许小数但提示。 */
    const val PACK = "PACK"

    /** 油盐酱醋等辅料：默认不扣，但可手动改。 */
    const val NEVER = "NEVER"

    fun of(raw: String): String = when (raw) {
        PACK, NEVER -> raw
        else -> PORTION
    }
}

/**
 * 菜谱的一步。
 *
 * @param durationSec 该步需要的时间；0 表示不设计时。有值即可直接变倒计时。
 * @param tip 这一步的要点或失败提醒。
 */
@Serializable
data class RecipeStep(
    val text: String,
    val durationSec: Int = 0,
    val tip: String = ""
)

/**
 * 一道菜谱。内置菜谱编号 1 起，用户新增的编号从 100 起（见 AppPreferences.RECIPE_ID_BASE）。
 *
 * @param role 菜品角色 key，多个角色用「,」分隔（如 `STAPLE,PROTEIN,VEGETABLE` 表示饺子自带主食/蛋白/蔬菜）；
 *   为空时按菜名自动判定（见 [DishRoleClassifier]），保证老数据与用户自建菜谱也能正确归类。
 * @param syncId 跨设备合并的稳定标识；内置菜谱为 null（按编号对齐），用户自建菜谱由 AppPreferences 生成。
 * @param baseYield 基准份数，例如 2 表示「2 人份」；0 表示未标注，不参与缩放。
 * @param yieldUnit 份数单位（人份 / 个 / 碗）。
 * @param steps 按顺序执行的步骤；旧数据为空表示「只有食材清单」。
 *
 * 除 [id]/[name] 外全部带默认值：旧版本写入的菜谱 JSON 可直接反序列化，
 * 不需要 Room 迁移，也不会因为新增字段把老菜谱读成空。
 */
@Serializable
data class Recipe(
    val id: Long,
    val name: String,
    val ingredients: List<RecipeIngredient> = emptyList(),
    val role: String = "",
    val syncId: String? = null,
    val description: String = "",
    val baseYield: Int = 0,
    val yieldUnit: String = "人份",
    val prepMin: Int = 0,
    val cookMin: Int = 0,
    val difficulty: String = "",
    val cuisine: String = "",
    val steps: List<RecipeStep> = emptyList(),
    val tips: String = "",
    val sourceUrl: String = ""
) {
    /** 这道菜覆盖的角色：优先用显式 [role]（可多个），缺省时按菜名推断。 */
    val dishRoles: Set<DishRole>
        get() = decodeRoles(role).ifEmpty { DishRoleClassifier.byName(name) }

    /** 从备料到上桌的总时长；两项都没标时为 0。 */
    val totalMin: Int
        get() = prepMin + cookMin

    /** 是否可以按份数缩放（没有基准份数的老菜谱不缩放，避免给出假精度）。 */
    val scalable: Boolean
        get() = baseYield > 0 && ingredients.any { it.scalable }

    /**
     * 按目标份数缩放食材用量，返回新的食材清单；不可计量的「适量」原样保留。
     *
     * 只做同维度换算（g↔kg、ml↔L），不做「杯↔克」这类需要密度的换算——
     * 猜错会让整道菜的咸淡失衡，宁可让用户自己改。
     */
    fun ingredientsFor(servings: Int): List<RecipeIngredient> {
        if (!scalable || servings <= 0) return ingredients
        val factor = servings.toDouble() / baseYield
        if (abs(factor - 1.0) < 1e-6) return ingredients
        return ingredients.map { item ->
            if (!item.scalable) item else item.copy(amount = item.amount!! * factor)
        }
    }

    /** 当前份数对应的缩放倍率；不可缩放时恒为 1。 */
    fun scaleFactor(servings: Int): Double =
        if (!scalable || servings <= 0) 1.0 else servings.toDouble() / baseYield
}

/**
 * 把克数这类小数显示成厨房里说得出口的样子：
 * 整数就显示整数，小于 10 的保留一位，其余四舍五入到整数。
 */
fun formatAmount(value: Double): String {
    if (value <= 0) return "0"
    if (abs(value - value.roundToInt()) < 0.05) return value.roundToInt().toString()
    if (value < 10) {
        val oneDecimal = (value * 10).roundToInt() / 10.0
        return if (abs(oneDecimal - oneDecimal.roundToInt()) < 1e-9) {
            oneDecimal.roundToInt().toString()
        } else {
            oneDecimal.toString()
        }
    }
    return value.roundToInt().toString()
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
        description = "酸甜下饭，十几分钟能上桌的家常底菜。",
        baseYield = 2,
        prepMin = 5,
        cookMin = 8,
        difficulty = "简单",
        cuisine = "家常",
        ingredients = listOf(
            RecipeIngredient(
                "番茄", listOf("番茄", "西红柿"),
                rawText = "2 个（约 300 克）", amount = 300.0, unit = "克",
                prepNote = "切块，怕酸可去皮"
            ),
            RecipeIngredient(
                "鸡蛋", listOf("鸡蛋", "蛋"),
                rawText = "3 个", amount = 3.0, unit = "个",
                deductPolicy = DeductPolicy.PACK, prepNote = "打散"
            ),
            RecipeIngredient(
                "葱", listOf("葱", "小葱", "香葱"), optional = true,
                rawText = "1 根", amount = 1.0, unit = "根", prepNote = "切葱花"
            ),
            RecipeIngredient(
                "盐", optional = true, rawText = "2 克", amount = 2.0, unit = "克",
                deductPolicy = DeductPolicy.NEVER
            ),
            RecipeIngredient(
                "白糖", optional = true, rawText = "5 克", amount = 5.0, unit = "克",
                deductPolicy = DeductPolicy.NEVER, prepNote = "平衡酸味，可省"
            )
        ),
        steps = listOf(
            RecipeStep("鸡蛋加一小撮盐打散，番茄切块。", tip = "蛋液打到看不见明显蛋清即可。"),
            RecipeStep("锅烧热放油，油温升高后倒入蛋液，等边缘凝固再翻动，盛出备用。", 60,
                "油稍多一点蛋才蓬松；炒老了口感发硬。"),
            RecipeStep("原锅下番茄，中火炒到出汁、块边变软。", 180,
                "汁水不够可以沿锅边加两勺水。"),
            RecipeStep("倒回鸡蛋，加盐和糖翻匀，撒葱花出锅。", 40,
                "糖只为提鲜，尝不出甜味才对。")
        ),
        tips = "番茄和蛋分开炒，是这道菜不出水的关键。"
    ),
    Recipe(
        id = 2L,
        name = "青椒炒肉",
        role = roleOf(DishRole.PROTEIN, DishRole.VEGETABLE),
        description = "青椒脆、肉片嫩，一口咸鲜。",
        baseYield = 2,
        prepMin = 10,
        cookMin = 8,
        difficulty = "简单",
        cuisine = "家常",
        ingredients = listOf(
            RecipeIngredient(
                "青椒", listOf("青椒", "尖椒", "彩椒"),
                rawText = "3 个", amount = 3.0, unit = "个", prepNote = "去籽切块"
            ),
            RecipeIngredient(
                "猪肉", listOf("猪肉", "五花肉", "里脊", "肉丝", "肉片", "肉"),
                rawText = "200 克", amount = 200.0, unit = "克", prepNote = "切薄片"
            ),
            RecipeIngredient(
                "生抽", optional = true, rawText = "1 勺", amount = 1.0, unit = "勺",
                deductPolicy = DeductPolicy.NEVER
            ),
            RecipeIngredient(
                "淀粉", optional = true, rawText = "1 小勺", amount = 1.0, unit = "小勺",
                deductPolicy = DeductPolicy.NEVER, prepNote = "抓肉用"
            )
        ),
        steps = listOf(
            RecipeStep("肉片加生抽、淀粉抓匀，静置 10 分钟。", 600,
                "这一步决定肉嫩不嫩，别省。"),
            RecipeStep("热锅少油，肉片下锅摊开，变色后盛出。", 90, "先不要频繁翻动。"),
            RecipeStep("原锅下青椒大火炒到表皮起皱。", 120, "青椒要脆，别炒软。"),
            RecipeStep("倒回肉片，补少许盐翻匀出锅。", 40)
        ),
        tips = "青椒下锅前擦干水分，否则会溅油且炒成水煮味。"
    ),
    Recipe(
        id = 3L,
        name = "蒜蓉青菜",
        role = DishRole.VEGETABLE.name,
        description = "五分钟上桌的绿叶菜，关键是火大、手快。",
        baseYield = 2,
        prepMin = 5,
        cookMin = 4,
        difficulty = "简单",
        cuisine = "家常",
        ingredients = listOf(
            RecipeIngredient(
                "青菜", listOf("青菜", "小白菜", "生菜", "菠菜", "油菜", "白菜", "西兰花"),
                rawText = "400 克", amount = 400.0, unit = "克",
                prepNote = "洗净沥干，长的从中间切开"
            ),
            RecipeIngredient(
                "蒜", listOf("蒜", "大蒜"), optional = true,
                rawText = "4 瓣", amount = 4.0, unit = "瓣",
                deductPolicy = DeductPolicy.NEVER, prepNote = "拍碎切末"
            )
        ),
        steps = listOf(
            RecipeStep("青菜洗净后彻底沥干，太大的叶子撕成两半。", tip = "带水下锅会溅油，也炒不香。"),
            RecipeStep("热锅放油，下蒜末小火炒到微微发黄。", 30),
            RecipeStep("转大火下青菜，快速翻炒到叶子塌下去。", 90, "全程别盖锅盖，否则会发黄。"),
            RecipeStep("加盐翻匀，立刻出锅。", 20, "盐早了会出水。")
        ),
        tips = "炒青菜只放盐就够，生抽会让颜色发暗。"
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
        description = "麻、辣、烫、嫩，一勺就能拌一碗饭。",
        baseYield = 2,
        prepMin = 8,
        cookMin = 12,
        difficulty = "中等",
        cuisine = "川菜",
        ingredients = listOf(
            RecipeIngredient(
                "豆腐", listOf("豆腐"),
                rawText = "400 克", amount = 400.0, unit = "克",
                prepNote = "嫩豆腐切 2 厘米方块"
            ),
            RecipeIngredient(
                "肉末", listOf("肉末", "肉", "猪肉", "牛肉"),
                rawText = "100 克", amount = 100.0, unit = "克"
            ),
            RecipeIngredient(
                "豆瓣酱", optional = true, rawText = "1 勺", amount = 1.0, unit = "勺",
                deductPolicy = DeductPolicy.NEVER, prepNote = "剁细"
            ),
            RecipeIngredient(
                "花椒粉", optional = true, rawText = "少许",
                deductPolicy = DeductPolicy.NEVER, prepNote = "出锅前撒"
            ),
            RecipeIngredient(
                "蒜", listOf("蒜", "大蒜"), optional = true,
                rawText = "2 瓣", amount = 2.0, unit = "瓣",
                deductPolicy = DeductPolicy.NEVER
            )
        ),
        steps = listOf(
            RecipeStep("豆腐块放进淡盐水里小火煮 2 分钟，捞出沥干。", 120,
                "焯过水不易碎，也能去掉豆腥味。"),
            RecipeStep("热锅下油，肉末炒散炒香，推到一边下豆瓣酱和蒜末炒出红油。", 120,
                "豆瓣酱容易糊，火调小一点。"),
            RecipeStep("加一碗水烧开，下豆腐，用铲背推着煮 5 分钟。", 300,
                "别用铲子翻炒，豆腐会碎。"),
            RecipeStep("分两次淋入淀粉水勾芡，汤汁挂住豆腐即可。", 60,
                "一次勾太多会结块，分两次更稳。"),
            RecipeStep("盛出撒花椒粉和葱花。", 0)
        ),
        tips = "花椒粉最后撒，久煮会发苦。"
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
        description = "肥而不腻，靠的是小火慢炖和最后收汁。",
        baseYield = 3,
        prepMin = 15,
        cookMin = 60,
        difficulty = "中等",
        cuisine = "家常",
        ingredients = listOf(
            RecipeIngredient(
                "五花肉", listOf("五花肉", "猪肉", "肉"),
                rawText = "500 克", amount = 500.0, unit = "克",
                prepNote = "切 3 厘米方块"
            ),
            RecipeIngredient(
                "冰糖", optional = true, rawText = "20 克", amount = 20.0, unit = "克",
                deductPolicy = DeductPolicy.NEVER, prepNote = "炒糖色用"
            ),
            RecipeIngredient(
                "生抽", optional = true, rawText = "2 勺", amount = 2.0, unit = "勺",
                deductPolicy = DeductPolicy.NEVER
            ),
            RecipeIngredient(
                "老抽", optional = true, rawText = "1 勺", amount = 1.0, unit = "勺",
                deductPolicy = DeductPolicy.NEVER, prepNote = "上色"
            ),
            RecipeIngredient(
                "姜", listOf("姜"), optional = true,
                rawText = "3 片", amount = 3.0, unit = "片",
                deductPolicy = DeductPolicy.NEVER
            )
        ),
        steps = listOf(
            RecipeStep("五花肉冷水下锅，加姜片煮开，撇沫后捞出冲净。", 300),
            RecipeStep("锅里不放油，肉块下锅中火煸到表面微黄、出油。", 300,
                "煸出多余油脂，成品才不腻。"),
            RecipeStep("把肉推到一边，下冰糖小火炒到融化变枣红色，再和肉翻匀。", 180,
                "糖色一变深就下肉，晚了会苦。"),
            RecipeStep("加生抽、老抽和没过肉的热水，烧开后转小火盖盖炖 45 分钟。", 2700),
            RecipeStep("开盖转大火收汁，汤汁浓稠裹住肉块即可。", 420,
                "收汁时勤翻，糖分容易糊底。")
        ),
        tips = "全程加热水，加冷水肉会突然收紧变柴。"
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
        description = "甜咸挂汁，几乎不会失败的一道荤菜。",
        baseYield = 2,
        prepMin = 10,
        cookMin = 25,
        difficulty = "简单",
        cuisine = "家常",
        ingredients = listOf(
            RecipeIngredient(
                "鸡翅", listOf("鸡翅", "翅中", "翅根"),
                rawText = "8 个（约 400 克）", amount = 8.0, unit = "个",
                prepNote = "两面各划两刀"
            ),
            RecipeIngredient(
                "可乐", rawText = "330 毫升", amount = 330.0, unit = "毫升",
                deductPolicy = DeductPolicy.NEVER, prepNote = "普通含糖可乐，无糖的会发苦"
            ),
            RecipeIngredient(
                "生抽", optional = true, rawText = "2 勺", amount = 2.0, unit = "勺",
                deductPolicy = DeductPolicy.NEVER
            ),
            RecipeIngredient(
                "姜", listOf("姜"), optional = true,
                rawText = "3 片", amount = 3.0, unit = "片",
                deductPolicy = DeductPolicy.NEVER
            )
        ),
        steps = listOf(
            RecipeStep("鸡翅冷水下锅，水开后煮 2 分钟捞出冲净浮沫。", 150,
                "这一步去腥，也让后面更容易入味。"),
            RecipeStep("锅里少油，鸡翅皮朝下小火煎到两面金黄。", 300,
                "煎出鸡皮里的油，成品才不腻。"),
            RecipeStep("倒入可乐和生抽，放姜片，大火烧开转小火盖盖焖 15 分钟。", 900),
            RecipeStep("开盖大火收汁，汤汁变浓稠挂在鸡翅上就关火。", 300,
                "收汁时要翻动，糖分容易糊底。")
        ),
        tips = "收汁到「能挂住勺子」就停，再煮会发苦。"
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
        description = "先焯后炒，颜色翠绿、口感脆嫩。",
        baseYield = 2,
        prepMin = 8,
        cookMin = 5,
        difficulty = "简单",
        cuisine = "家常",
        ingredients = listOf(
            RecipeIngredient(
                "西兰花", listOf("西兰花", "菜花", "花椰菜"),
                rawText = "1 棵（约 400 克）", amount = 400.0, unit = "克",
                prepNote = "切小朵，淡盐水泡 10 分钟"
            ),
            RecipeIngredient(
                "蒜", listOf("蒜", "大蒜"), optional = true,
                rawText = "4 瓣", amount = 4.0, unit = "瓣",
                deductPolicy = DeductPolicy.NEVER
            )
        ),
        steps = listOf(
            RecipeStep("西兰花切小朵，用淡盐水泡 10 分钟后冲净。", 600,
                "花苞里容易藏虫和灰，泡一下更放心。"),
            RecipeStep("水开加一点盐和油，西兰花下锅焯 1 分钟捞出。", 90,
                "加油能让颜色更绿；别焯久，后面还要炒。"),
            RecipeStep("热锅下油爆香蒜末，倒入西兰花大火翻炒。", 60),
            RecipeStep("加盐和少许蚝油翻匀，出锅。", 30)
        ),
        tips = "焯水后立刻炒，放凉了再炒会出水。"
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
        description = "脆爽带酸，考验火候的一道快手菜。",
        baseYield = 2,
        prepMin = 10,
        cookMin = 5,
        difficulty = "中等",
        cuisine = "家常",
        ingredients = listOf(
            RecipeIngredient(
                "土豆", listOf("土豆", "马铃薯"),
                rawText = "2 个（约 400 克）", amount = 400.0, unit = "克",
                prepNote = "切细丝，越细越好"
            ),
            RecipeIngredient(
                "辣椒", listOf("辣椒", "青椒", "干辣椒"), optional = true,
                rawText = "3 个", amount = 3.0, unit = "个",
                deductPolicy = DeductPolicy.NEVER, prepNote = "干辣椒剪段"
            ),
            RecipeIngredient(
                "醋", listOf("醋", "陈醋", "香醋"), optional = true,
                rawText = "2 勺", amount = 2.0, unit = "勺",
                deductPolicy = DeductPolicy.NEVER
            )
        ),
        steps = listOf(
            RecipeStep("土豆切丝后泡清水，反复冲洗到水变清，沥干。", 600,
                "冲掉表面淀粉，炒出来才脆不粘。"),
            RecipeStep("热锅凉油下干辣椒和花椒，小火爆香。", 40, "辣椒变黑就苦了。"),
            RecipeStep("转大火下土豆丝，快速翻炒 2 分钟。", 120, "全程大火，火小了会出水。"),
            RecipeStep("沿锅边淋醋，加盐翻匀立刻出锅。", 30,
                "醋沿锅边淋才香，直接倒进菜里只剩酸味。")
        ),
        tips = "土豆丝下锅前一定要沥干，带水下锅就成炖土豆了。"
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
        description = "土豆、茄子、青椒三样，酱汁一裹就很下饭。",
        baseYield = 2,
        prepMin = 15,
        cookMin = 15,
        difficulty = "中等",
        cuisine = "东北菜",
        ingredients = listOf(
            RecipeIngredient(
                "土豆", listOf("土豆", "马铃薯"),
                rawText = "1 个（约 200 克）", amount = 200.0, unit = "克",
                prepNote = "切滚刀块"
            ),
            RecipeIngredient(
                "茄子", listOf("茄子"),
                rawText = "1 根（约 200 克）", amount = 200.0, unit = "克",
                prepNote = "切滚刀块"
            ),
            RecipeIngredient(
                "青椒", listOf("青椒", "尖椒"), optional = true,
                rawText = "2 个", amount = 2.0, unit = "个", prepNote = "切块"
            ),
            RecipeIngredient(
                "生抽", optional = true, rawText = "2 勺", amount = 2.0, unit = "勺",
                deductPolicy = DeductPolicy.NEVER
            )
        ),
        steps = listOf(
            RecipeStep("土豆、茄子切滚刀块，茄子撒少许盐腌 5 分钟挤掉水分。", 300,
                "茄子挤水后不吸油。"),
            RecipeStep("锅里多放些油，土豆块中火煎到表面金黄，盛出。", 300),
            RecipeStep("同一锅煎茄子到软塌，盛出。", 240),
            RecipeStep("留底油炒青椒，倒回土豆和茄子，加生抽、糖和半碗水烧 2 分钟。", 180),
            RecipeStep("淋淀粉水勾薄芡，翻匀出锅。", 40)
        ),
        tips = "想少油就改用空气炸锅先把土豆和茄子烤软。"
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
        description = "隔夜饭的最佳去处，粒粒分明。",
        baseYield = 2,
        prepMin = 5,
        cookMin = 8,
        difficulty = "简单",
        cuisine = "家常",
        ingredients = listOf(
            RecipeIngredient(
                "米饭", listOf("米饭", "剩饭", "米"),
                rawText = "2 碗（约 300 克）", amount = 300.0, unit = "克",
                prepNote = "隔夜饭最好，先用手抓散"
            ),
            RecipeIngredient(
                "鸡蛋", listOf("鸡蛋", "蛋"),
                rawText = "2 个", amount = 2.0, unit = "个",
                deductPolicy = DeductPolicy.PACK, prepNote = "打散"
            ),
            RecipeIngredient(
                "葱", listOf("葱", "小葱", "香葱"), optional = true,
                rawText = "1 根", amount = 1.0, unit = "根", prepNote = "切葱花"
            )
        ),
        steps = listOf(
            RecipeStep("米饭抓散，鸡蛋打散。", tip = "结块的饭下锅炒不散。"),
            RecipeStep("热锅多油，倒蛋液炒到半凝固就盛出。", 50),
            RecipeStep("原锅下米饭，中火压散翻炒到米粒跳动。", 180,
                "用铲背压比翻更有效。"),
            RecipeStep("倒回鸡蛋，加盐翻匀，撒葱花出锅。", 60)
        ),
        tips = "全程中大火，火小了饭会黏成一团。"
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
        description = "蛋花要薄要散，关键是关火后再淋。",
        baseYield = 2,
        prepMin = 5,
        cookMin = 10,
        difficulty = "简单",
        cuisine = "家常",
        ingredients = listOf(
            RecipeIngredient(
                "番茄", listOf("番茄", "西红柿"),
                rawText = "2 个", amount = 2.0, unit = "个", prepNote = "切小块"
            ),
            RecipeIngredient(
                "鸡蛋", listOf("鸡蛋", "蛋"),
                rawText = "2 个", amount = 2.0, unit = "个",
                deductPolicy = DeductPolicy.PACK, prepNote = "打散"
            ),
            RecipeIngredient(
                "淀粉", optional = true, rawText = "1 小勺", amount = 1.0, unit = "小勺",
                deductPolicy = DeductPolicy.NEVER, prepNote = "调成水淀粉"
            )
        ),
        steps = listOf(
            RecipeStep("番茄切小块，锅里少油炒到出汁。", 180),
            RecipeStep("加两碗水烧开，煮 3 分钟让番茄味出来。", 180),
            RecipeStep("淋入水淀粉搅匀，汤会略微变稠。", 40, "有薄芡蛋花才挂得住。"),
            RecipeStep("关火，沿锅边慢慢淋入蛋液，静置 10 秒再轻推。", 30,
                "开着火淋蛋花会老、会碎。"),
            RecipeStep("加盐和几滴香油，撒葱花。", 0)
        ),
        tips = "蛋液淋下去别马上搅，等它自己凝成片。"
    ),
    Recipe(
        id = 36L,
        name = "冬瓜排骨汤",
        role = roleOf(DishRole.SOUP, DishRole.PROTEIN),
        description = "清甜不腻，炖上就不用管的汤。",
        baseYield = 2,
        prepMin = 10,
        cookMin = 55,
        difficulty = "简单",
        cuisine = "家常",
        ingredients = listOf(
            RecipeIngredient(
                "冬瓜", listOf("冬瓜"),
                rawText = "400 克", amount = 400.0, unit = "克",
                prepNote = "去皮去瓤切厚片"
            ),
            RecipeIngredient(
                "排骨", listOf("排骨", "肋排"),
                rawText = "400 克", amount = 400.0, unit = "克",
                prepNote = "斩小块"
            ),
            RecipeIngredient(
                "姜", listOf("姜"), optional = true,
                rawText = "3 片", amount = 3.0, unit = "片",
                deductPolicy = DeductPolicy.NEVER
            )
        ),
        steps = listOf(
            RecipeStep("排骨冷水下锅，加姜片煮开，撇净浮沫后捞出冲洗。", 300,
                "冷水下锅才能把血水逼出来。"),
            RecipeStep("排骨放回锅里，加足量热水和姜片，大火烧开转小火炖 40 分钟。", 2400,
                "中途别加冷水，肉会紧。"),
            RecipeStep("下冬瓜片，继续煮 15 分钟到冬瓜透明。", 900),
            RecipeStep("出锅前加盐调味，撒葱花。", 0, "盐最后放，肉更容易炖烂。")
        ),
        tips = "冬瓜别太早下，煮久了会化在汤里。"
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