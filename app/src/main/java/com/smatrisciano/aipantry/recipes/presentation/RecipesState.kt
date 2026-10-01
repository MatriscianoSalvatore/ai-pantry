package com.smatrisciano.aipantry.recipes.presentation

import com.smatrisciano.aipantry.recipes.domain.GenerationProgress
import com.smatrisciano.aipantry.recipes.domain.models.Recipe

data class RecipesState(
    val isGenerating: Boolean = true,
    val progressLog: List<GenerationProgress> = emptyList(),
    val engineName: String = "",
    val recipes: List<Recipe> = emptyList(),
    val ingredientCount: Int = 0,
    /** List generation failed: error shown with a Retry button. */
    val generationFailed: Boolean = false,
    /** On-demand generation of the opened recipe's instructions. */
    val isDetailLoading: Boolean = false,
    val detailFailed: Boolean = false
)
