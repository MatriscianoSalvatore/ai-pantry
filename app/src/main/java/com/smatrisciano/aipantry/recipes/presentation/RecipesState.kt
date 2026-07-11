package com.smatrisciano.aipantry.recipes.presentation

import com.smatrisciano.aipantry.recipes.domain.models.Recipe

data class RecipesState(
    val isGenerating: Boolean = true,
    val progressLog: List<String> = emptyList(),
    val engineName: String = "",
    val recipes: List<Recipe> = emptyList(),
    val ingredientCount: Int = 0
)
