package com.smatrisciano.aipantry.recipes.presentation

import com.smatrisciano.aipantry.recipes.domain.GenerationProgress
import com.smatrisciano.aipantry.recipes.domain.models.Recipe

data class RecipesState(
    val isGenerating: Boolean = true,
    val progressLog: List<GenerationProgress> = emptyList(),
    val engineName: String = "",
    val recipes: List<Recipe> = emptyList(),
    val ingredientCount: Int = 0,
    /** Generazione lista fallita: errore mostrato con un pulsante Retry. */
    val generationFailed: Boolean = false,
    /** Generazione on-demand delle istruzioni della ricetta aperta. */
    val isDetailLoading: Boolean = false,
    val detailFailed: Boolean = false
)
