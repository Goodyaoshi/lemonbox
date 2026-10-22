package com.goodyaoshi.lemonbox.data.meal

import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扣料计划的规则测试。
 *
 * 这些断言覆盖的都是「错了用户会真的丢库存/算错账」的分支：
 * 同物累加、调料不扣、单位不一致不猜换算、缺料不静默建 1 件。
 */
class DeductPlannerTest {

    private fun detail(
        id: Long,
        name: String,
        quantity: Double,
        unit: String,
        category: String? = "蔬菜"
    ) = ItemDetail(
        item = Item(id = id, name = name, quantity = quantity, unit = unit),
        categoryName = category
    )

    private val alwaysMatch: (RecipeIngredient, ItemDetail) -> Boolean = { ingredient, detail ->
        detail.item.name.contains(ingredient.label) || ingredient.label.contains(detail.item.name)
    }

    private val nothingNonDeductible: (ItemDetail) -> Boolean = { false }

    @Test
    fun plan_deductsByRecipeAmountAndKeepsBeforeAfter() {
        val recipe = Recipe(
            id = 1L,
            name = "番茄炒蛋",
            baseYield = 2,
            ingredients = listOf(
                RecipeIngredient("番茄", amount = 300.0, unit = "克")
            )
        )

        val plan = DeductPlanner.plan(
            dateKey = "2026-10-22",
            recipes = listOf(recipe),
            pool = listOf(detail(1L, "番茄", 500.0, "克")),
            isNonDeductible = nothingNonDeductible,
            match = alwaysMatch
        )

        val line = plan.lines.single()
        assertEquals(DeductAction.DEDUCT, line.action)
        assertEquals(300.0, line.amount, 1e-9)
        assertEquals(500.0, line.beforeAmount, 1e-9)
        assertEquals(200.0, line.afterAmount, 1e-9)
        assertTrue(line.selected)
    }

    @Test
    fun plan_accumulatesWhenTwoRecipesUseSameItem() {
        val a = Recipe(1L, "番茄炒蛋", ingredients = listOf(RecipeIngredient("番茄", amount = 200.0, unit = "克")))
        val b = Recipe(2L, "番茄牛腩", ingredients = listOf(RecipeIngredient("番茄", amount = 300.0, unit = "克")))

        val plan = DeductPlanner.plan(
            dateKey = "2026-10-22",
            recipes = listOf(a, b),
            pool = listOf(detail(1L, "番茄", 600.0, "克")),
            isNonDeductible = nothingNonDeductible,
            match = alwaysMatch
        )

        assertEquals(2, plan.lines.size)
        // 第二条的「扣后」必须按累计量算，否则用户会以为只扣了 300 克。
        assertEquals(400.0, plan.lines[0].afterAmount, 1e-9)
        assertEquals(100.0, plan.lines[1].afterAmount, 1e-9)
        assertEquals(2, plan.deductCount)
    }

    @Test
    fun plan_skipsSeasoningAndOptional() {
        val recipe = Recipe(
            id = 1L,
            name = "番茄炒蛋",
            ingredients = listOf(
                RecipeIngredient("盐", amount = 2.0, unit = "克"),
                RecipeIngredient("葱", optional = true, amount = 1.0, unit = "根"),
                RecipeIngredient("番茄", amount = 300.0, unit = "克")
            )
        )

        val plan = DeductPlanner.plan(
            dateKey = "2026-10-22",
            recipes = listOf(recipe),
            pool = listOf(
                detail(1L, "盐", 100.0, "克", category = "调味品"),
                detail(2L, "葱", 3.0, "根"),
                detail(3L, "番茄", 500.0, "克")
            ),
            isNonDeductible = { it.categoryName == "调味品" },
            match = alwaysMatch
        )

        assertEquals(DeductAction.SKIP, plan.lines[0].action)
        assertEquals(DeductAction.SKIP, plan.lines[1].action)
        assertEquals(DeductAction.DEDUCT, plan.lines[2].action)
        assertEquals(1, plan.deductCount)
    }

    @Test
    fun plan_fallsBackToSingleItemWhenUnitsDiffer() {
        val recipe = Recipe(
            id = 1L,
            name = "青椒炒肉",
            ingredients = listOf(RecipeIngredient("猪肉", amount = 200.0, unit = "克"))
        )

        val plan = DeductPlanner.plan(
            dateKey = "2026-10-22",
            recipes = listOf(recipe),
            pool = listOf(detail(1L, "猪肉", 3.0, "袋")),
            isNonDeductible = nothingNonDeductible,
            match = alwaysMatch
        )

        val line = plan.lines.single()
        // 绝不能把 200 当成 200 袋。
        assertEquals(1.0, line.amount, 1e-9)
        assertEquals("袋", line.unit)
        assertTrue(line.reason.contains("单位与库存不同"))
    }

    @Test
    fun plan_reportsMissingIngredientWithoutCreatingOne() {
        val recipe = Recipe(
            id = 1L,
            name = "番茄炒蛋",
            ingredients = listOf(RecipeIngredient("番茄", amount = 300.0, unit = "克"))
        )

        val plan = DeductPlanner.plan(
            dateKey = "2026-10-22",
            recipes = listOf(recipe),
            pool = emptyList(),
            isNonDeductible = nothingNonDeductible,
            match = alwaysMatch
        )

        val line = plan.lines.single()
        assertEquals(DeductAction.MISSING, line.action)
        assertEquals(null, line.itemId)
        assertFalse(line.selected)
        assertEquals(listOf("番茄"), plan.missingLabels)
    }

    @Test
    fun plan_dedupesMissingIngredientSharedByTwoRecipes() {
        // 米饭和白粥都要大米；家里没有时只该提示一次，不能出现「还缺：大米、大米」。
        val rice = Recipe(1L, "米饭", ingredients = listOf(RecipeIngredient("大米", amount = 150.0, unit = "克")))
        val porridge = Recipe(2L, "白粥", ingredients = listOf(RecipeIngredient("大米", amount = 80.0, unit = "克")))

        val plan = DeductPlanner.plan(
            dateKey = "2026-10-22",
            recipes = listOf(rice, porridge),
            pool = emptyList(),
            isNonDeductible = nothingNonDeductible,
            match = alwaysMatch
        )

        assertEquals(listOf("大米"), plan.missingLabels)
        assertEquals(1, plan.lines.size)
    }

    @Test
    fun withSelection_onlyTogglesDeductibleLines() {
        val recipe = Recipe(
            id = 1L,
            name = "番茄炒蛋",
            ingredients = listOf(
                RecipeIngredient("番茄", amount = 300.0, unit = "克"),
                RecipeIngredient("盐", amount = 2.0, unit = "克")
            )
        )
        val plan = DeductPlanner.plan(
            dateKey = "2026-10-22",
            recipes = listOf(recipe),
            pool = listOf(
                detail(1L, "番茄", 500.0, "克"),
                detail(2L, "盐", 100.0, "克", category = "调味品")
            ),
            isNonDeductible = { it.categoryName == "调味品" },
            match = alwaysMatch
        )

        val toggled = plan.withSelection(0, false).withSelection(1, true)
        assertFalse(toggled.lines[0].selected)
        assertFalse(toggled.lines[1].selected)   // SKIP 行不能被打开
        assertEquals(0, toggled.deductCount)
    }

    @Test
    fun withScale_recomputesAmountAndRemainingTogether() {
        val recipe = Recipe(
            id = 1L,
            name = "番茄炒蛋",
            ingredients = listOf(RecipeIngredient("番茄", amount = 300.0, unit = "克"))
        )
        val plan = DeductPlanner.plan(
            dateKey = "2026-10-22",
            recipes = listOf(recipe),
            pool = listOf(detail(1L, "番茄", 500.0, "克")),
            isNonDeductible = nothingNonDeductible,
            match = alwaysMatch
        )

        val doubled = plan.withScale(2.0)
        assertEquals(2.0, doubled.scale, 1e-9)
        assertEquals(600.0, doubled.lines[0].amount, 1e-9)
        // 只剩 500 克，扣 600 后应为 0，而不是负数。
        assertEquals(0.0, doubled.lines[0].afterAmount, 1e-9)

        // 来回拨动必须回到原值，不能因为反复乘除漂移。
        val back = doubled.withScale(1.0)
        assertEquals(300.0, back.lines[0].amount, 1e-9)
        assertEquals(200.0, back.lines[0].afterAmount, 1e-9)
        assertEquals(300.0, back.lines[0].baseAmount, 1e-9)
    }

    @Test
    fun withAmount_clampsToStockAndKeepsOtherLinesConsistent() {
        val a = Recipe(1L, "番茄炒蛋", ingredients = listOf(RecipeIngredient("番茄", amount = 200.0, unit = "克")))
        val b = Recipe(2L, "番茄牛腩", ingredients = listOf(RecipeIngredient("番茄", amount = 300.0, unit = "克")))
        val plan = DeductPlanner.plan(
            dateKey = "2026-10-22",
            recipes = listOf(a, b),
            pool = listOf(detail(1L, "番茄", 600.0, "克")),
            isNonDeductible = nothingNonDeductible,
            match = alwaysMatch
        )

        // 第一行改成 50 克：第二行的余量要按新的累计值重算（600 - 50 - 300 = 250）。
        val edited = plan.withAmount(0, 50.0)
        assertEquals(50.0, edited.lines[0].amount, 1e-9)
        assertEquals(550.0, edited.lines[0].afterAmount, 1e-9)
        assertEquals(250.0, edited.lines[1].afterAmount, 1e-9)

        // 不能扣超过家里现有的量。
        val over = edited.withAmount(0, 9999.0)
        assertEquals(600.0, over.lines[0].amount, 1e-9)
        assertEquals(0.0, over.lines[0].afterAmount, 1e-9)
    }
}
