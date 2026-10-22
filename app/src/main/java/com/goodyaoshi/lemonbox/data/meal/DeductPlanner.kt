package com.goodyaoshi.lemonbox.data.meal

import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import kotlin.math.abs

/** 扣料行要执行的动作。 */
object DeductAction {
    /** 按计划扣减库存。 */
    const val DEDUCT = "DEDUCT"

    /** 命中物品但默认不扣（调料、可选配料等），用户可以手动打开。 */
    const val SKIP = "SKIP"

    /** 家里没有匹配的物品，不扣，只提示补货。 */
    const val MISSING = "MISSING"
}

/**
 * 一条扣料计划。
 *
 * [beforeAmount] / [afterAmount] 在生成计划时就算好：用户确认前就能看见
 * 「冰箱里有 2 袋，会扣掉半袋，剩 1.5 袋」，而不是扣完才发现扣多了。
 * 撤销靠 [beforeAmount] 整份写回，不做反向推导。
 */
data class DeductLine(
    val label: String,
    val itemId: Long?,
    val itemName: String,
    val amount: Double,
    val unit: String,
    val beforeAmount: Double,
    val afterAmount: Double,
    val action: String,
    val reason: String,
    val selected: Boolean,
    /**
     * 按「原份」计的扣量。份量缩放与手动改量都以它为基准，
     * 来回拨动不会因为反复乘除而漂移。
     */
    val baseAmount: Double = amount
) {
    val isDeductible: Boolean
        get() = action == DeductAction.DEDUCT
}

/** 一餐的扣料计划；[lines] 顺序即展示顺序。 */
data class DeductPlan(
    val dateKey: String,
    val lines: List<DeductLine>,
    /** 当前的份量倍率：1 表示按菜谱原份，2 表示做双份。 */
    val scale: Double = 1.0
) {
    /** 实际会扣减的样数（按物品去重）。 */
    val deductCount: Int
        get() = lines.count { it.selected && it.action == DeductAction.DEDUCT }

    /** 缺料清单：只列必选且家里没有的食材。 */
    val missingLabels: List<String>
        get() = lines.filter { it.action == DeductAction.MISSING }.map { it.label }

    /** 有没有可调份量的行；没有就不显示份量选择，避免空拨。 */
    val canScale: Boolean
        get() = lines.any { it.isDeductible }

    /** 用户逐条勾选/取消后生成新计划。 */
    fun withSelection(index: Int, selected: Boolean): DeductPlan =
        copy(
            lines = lines.mapIndexed { i, line ->
                if (i != index || !line.isDeductible) line else line.copy(selected = selected)
            }
        )

    /**
     * 按倍率重算扣量：做双份就扣两倍，做半份就扣一半。
     *
     * 只改可扣行；[DeductLine.baseAmount] 保留原份用量，来回拨动不会累积误差。
     * 扣后余量按「同一物品多行累加」重算，保证清单上的数字与实际扣减一致。
     */
    fun withScale(factor: Double): DeductPlan {
        val next = factor.coerceIn(MIN_SCALE, MAX_SCALE)
        if (abs(next - scale) < 1e-6) return this
        val scaled = lines.map { line ->
            if (!line.isDeductible) line
            else line.copy(amount = line.baseAmount * next)
        }
        return copy(scale = next, lines = recomputeAfter(scaled))
    }

    /**
     * 手动改某一行的扣量（长按 +/- 之外的精细修正）。
     * 上限是「家里现有的量」——不能扣出一件不存在的东西；下限 0 表示这行不动。
     * 改完后其他行的「扣后余量」按累计重算。
     */
    fun withAmount(index: Int, amount: Double): DeductPlan {
        val target = lines.getOrNull(index) ?: return this
        if (!target.isDeductible) return this
        val value = amount.coerceIn(0.0, target.beforeAmount.coerceAtLeast(0.0))
        val updated = lines.mapIndexed { i, line ->
            if (i != index) line
            else line.copy(amount = value, baseAmount = value / scale.coerceAtLeast(0.001))
        }
        return copy(lines = recomputeAfter(updated))
    }

    /**
     * 同一物品被多道菜命中时，扣量是累加的：第二行要按「扣完第一行之后」算余量。
     * 只重算可扣行，跳过行保持原样。
     */
    private fun recomputeAfter(source: List<DeductLine>): List<DeductLine> {
        val totals = mutableMapOf<Long?, Double>()
        return source.map { line ->
            if (!line.isDeductible) line
            else {
                val total = (totals[line.itemId] ?: 0.0) + line.amount
                totals[line.itemId] = total
                line.copy(afterAmount = (line.beforeAmount - total).coerceAtLeast(0.0))
            }
        }
    }

    companion object {
        /** 份量下限/上限：半份到三份，够覆盖一人食到一桌人。 */
        const val MIN_SCALE = 0.5
        const val MAX_SCALE = 3.0
    }
}

/**
 * 扣料计划生成器（纯函数，不碰数据库）。
 *
 * 规则来自方案 4.6 的矩阵：
 *  - 可选配料默认不扣（用户可以在清单里打开）；
 *  - 油盐酱醋与主食粮油默认不扣，由 [isNonDeductible] 按分类判定；
 *  - 家里没有的食材不静默创建「1 件」，只提示补货；
 *  - 单位不一致不猜换算，退回「扣 1 件」并写明原因。
 *
 * 同一物品被多道菜命中时，扣量累加、扣前值只取一次——否则两道菜各扣一遍，
 * 清单上看不出来，库存却被扣了两次。
 */
object DeductPlanner {

    fun plan(
        dateKey: String,
        recipes: List<Recipe>,
        pool: List<ItemDetail>,
        isNonDeductible: (ItemDetail) -> Boolean,
        match: (RecipeIngredient, ItemDetail) -> Boolean
    ): DeductPlan {
        val lines = mutableListOf<DeductLine>()
        // itemId → 已累计要扣的量；用于同物累加与「扣后值」回算。
        val accumulated = linkedMapOf<Long, Double>()
        val beforeById = linkedMapOf<Long, Double>()

        recipes.forEach { recipe ->
            // 同一道菜里两味食材不应抢到同一袋；跨菜品可以（累加）。
            val usedInThisRecipe = mutableSetOf<Long>()
            recipe.ingredients.forEach { ingredient ->
                val matched = pool.firstOrNull { detail ->
                    detail.item.id !in usedInThisRecipe && match(ingredient, detail)
                }
                when {
                    matched == null -> {
                        // 同一食材被两道菜用到（米饭 + 白粥都要大米）时只提示一次：
                        // 「还缺：大米、大米」会让用户以为要买两份。
                        val alreadyMissing = lines.any {
                            it.action == DeductAction.MISSING && it.label == ingredient.label
                        }
                        if (!ingredient.optional && !alreadyMissing) {
                            lines += DeductLine(
                                label = ingredient.label,
                                itemId = null,
                                itemName = "",
                                amount = 0.0,
                                unit = ingredient.unit,
                                beforeAmount = 0.0,
                                afterAmount = 0.0,
                                action = DeductAction.MISSING,
                                reason = "家里没有，已提醒补货",
                                selected = false
                            )
                        }
                    }

                    ingredient.optional -> lines += skipped(
                        ingredient, matched, "可选配料，默认不扣"
                    )

                    isNonDeductible(matched) -> lines += skipped(
                        ingredient, matched, "粮油调料不扣库存"
                    )

                    else -> {
                        usedInThisRecipe += matched.item.id
                        val (amount, unit, reason) = requirementOf(ingredient, matched.item)
                        val before = beforeById.getOrPut(matched.item.id) { matched.item.quantity }
                        val total = (accumulated[matched.item.id] ?: 0.0) + amount
                        accumulated[matched.item.id] = total
                        lines += DeductLine(
                            label = ingredient.label,
                            itemId = matched.item.id,
                            itemName = matched.item.name,
                            amount = amount,
                            unit = unit,
                            beforeAmount = before,
                            afterAmount = (before - total).coerceAtLeast(0.0),
                            action = DeductAction.DEDUCT,
                            reason = reason,
                            selected = true
                        )
                    }
                }
            }
        }
        return DeductPlan(dateKey = dateKey, lines = lines)
    }

    private fun skipped(
        ingredient: RecipeIngredient,
        detail: ItemDetail,
        reason: String
    ) = DeductLine(
        label = ingredient.label,
        itemId = detail.item.id,
        itemName = detail.item.name,
        amount = 0.0,
        unit = ingredient.unit,
        beforeAmount = detail.item.quantity,
        afterAmount = detail.item.quantity,
        action = DeductAction.SKIP,
        reason = reason,
        selected = false
    )

    /**
     * 这一味该扣多少。
     *
     * 只有「菜谱单位为空」或「与库存单位一致」才按菜谱用量扣；
     * 单位不同（菜谱写 300 克、库存按「袋」）时退回 1 件——宁可按整袋扣，
     * 也不能把 300 当成 300 袋。
     */
    private fun requirementOf(
        ingredient: RecipeIngredient,
        item: Item
    ): Triple<Double, String, String> {
        val amount = ingredient.amount
        val recipeUnit = ingredient.unit
        val stockUnit = item.unit
        if (amount == null || amount <= 0.0) {
            return Triple(1.0, stockUnit, "菜谱没写用量，按 1${stockUnit}扣")
        }
        val sameUnit = recipeUnit.isBlank() || recipeUnit == stockUnit
        return if (sameUnit) {
            Triple(amount, recipeUnit.ifBlank { stockUnit }, "")
        } else {
            Triple(1.0, stockUnit, "单位与库存不同（菜谱 $recipeUnit / 库存 $stockUnit），按 1${stockUnit}扣")
        }
    }
}
