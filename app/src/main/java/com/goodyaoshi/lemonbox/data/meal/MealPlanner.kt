package com.goodyaoshi.lemonbox.data.meal

/**
 * 菜单搭配器：为每一天配出默认的「一餐」。
 *
 * 默认搭配（对应用户要求）：
 * 1. 主食固定为「米饭 + 白粥」，两样每天都上；菜谱库里有同名菜谱时优先用菜谱，便于扣减食材；
 * 2. 蛋白必配：从菜谱库里轮换一道蛋白菜（汤品不算），尽量不与本周重复，轮完了宁可重复；
 * 3. 蔬菜固定为「清炒时蔬」；
 * 4. 默认不配汤饮；想喝汤或换菜由用户在编辑弹层里自行调整。
 */
object MealPlanner {

    fun planWeek(ranked: List<Recipe>, dayCount: Int, startIndex: Int = 0): List<MealSpec> {
        if (dayCount <= 0) return emptyList()
        val used = mutableSetOf<Long>()
        val byName = ranked.associateBy { it.name }

        return (0 until dayCount).map { offset ->
            val dayIndex = startIndex + offset
            val dishes = mutableListOf<MealDishSpec>()

            // 主食固定米饭 + 白粥：菜谱库里有（默认都有）就用菜谱，便于按「米」做食材检查；
            // 菜谱被删掉时退回基础名。是否扣食材由「做了」时的角色判定决定（主食不扣）。
            BASE_STAPLES.forEach { name ->
                val recipe = byName[name]
                if (recipe != null) {
                    used += recipe.id
                    dishes += MealDishSpec(DishRole.STAPLE.name, recipe.id, name)
                } else {
                    dishes += MealDishSpec(DishRole.STAPLE.name, null, name)
                }
            }

            // 蛋白：从菜谱库轮换，但绝不配汤品（汤里的肉不算一道硬菜）——
            // 自带主食的菜（蛋炒饭/饺子等）不参与避免主食重复；非汤蛋白都轮过了宁可重复，
            // 实在没有非汤蛋白时宁缺也不拿汤凑数。
            val protein = pick(
                ranked.filter {
                    DishRole.PROTEIN in it.dishRoles &&
                        DishRole.STAPLE !in it.dishRoles &&
                        DishRole.SOUP !in it.dishRoles
                },
                used,
                dayIndex
            )
            if (protein != null) {
                used += protein.id
                dishes += MealDishSpec(DishRole.PROTEIN.name, protein.id, protein.name)
            }

            // 蔬菜固定清炒时蔬；菜谱库里没有这道菜时用同名基础项兜底。
            val vegetable = byName[DEFAULT_VEGETABLE_NAME]
                ?: pick(ranked.filter { DishRole.VEGETABLE in it.dishRoles }, used, dayIndex)
            if (vegetable != null) {
                used += vegetable.id
                dishes += MealDishSpec(DishRole.VEGETABLE.name, vegetable.id, vegetable.name)
            } else {
                dishes += MealDishSpec(DishRole.VEGETABLE.name, null, DEFAULT_VEGETABLE_NAME)
            }

            // 汤饮默认不配。
            MealSpec(dishes)
        }
    }

    /** 优先取本周没用过且排行靠前的；都轮过了按天号轮换复用，避免连日重复同一道。 */
    private fun pick(candidates: List<Recipe>, used: Set<Long>, dayIndex: Int): Recipe? {
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.id !in used }
            ?: candidates[dayIndex % candidates.size]
    }
}
