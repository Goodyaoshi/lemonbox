package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.meal.Recipe
import com.goodyaoshi.lemonbox.data.meal.RecipeIngredient
import com.goodyaoshi.lemonbox.data.repository.CategoryRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 菜谱里可选的一组食材分类：一组 = 食品下的一个子分类（肉禽 / 蛋类 / 调味品…），
 * 选项 = 该子分类下的具体分类（猪肉 / 鸡蛋 / 酱油…）。
 */
data class IngredientGroup(
    val group: String,
    val options: List<String>
)

@HiltViewModel
class RecipeViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    categoryRepository: CategoryRepository
) : ViewModel() {

    /** 菜谱库（内置 + 自建），「今天吃什么」也用同一份。 */
    val recipes: StateFlow<List<Recipe>> = appPreferences.recipes

    /**
     * 食材分类分组：取「食品」子树的两级结构。菜谱食材按分类匹配，
     * 不绑定具体品牌或物品，所以编辑器里让用户从分类里挑而不是自由发挥。
     */
    val ingredientGroups: StateFlow<List<IngredientGroup>> =
        categoryRepository.getAllCategories()
            .map { categories ->
                val active = categories.filter { it.deletedAt == null }
                val foodRoot = active.firstOrNull { it.name == FOOD_ROOT } ?: return@map emptyList()
                active
                    .filter { it.parentId == foodRoot.id }
                    .map { sub ->
                        IngredientGroup(
                            group = sub.name,
                            options = active
                                .filter { it.parentId == sub.id }
                                .map { it.name }
                        )
                    }
                    .filter { it.options.isNotEmpty() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 新增一道菜谱；名称为空时返回 false。 */
    fun addRecipe(name: String, ingredients: List<RecipeIngredient>, role: String): Boolean =
        appPreferences.addRecipe(name, ingredients, role)

    /** 修改一道菜谱；名称为空或菜谱不存在时返回 false。 */
    fun updateRecipe(
        id: Long,
        name: String,
        ingredients: List<RecipeIngredient>,
        role: String
    ): Boolean = appPreferences.updateRecipe(id, name, ingredients, role)

    fun removeRecipe(id: Long) {
        appPreferences.removeRecipe(id)
    }

    private companion object {
        /** 菜谱食材的根分类名，与「今天吃什么」的取材根分类一致。 */
        const val FOOD_ROOT = "食品"
    }
}
