package com.smatrisciano.aipantry.recipes.presentation

import com.smatrisciano.aipantry.recipes.domain.GenerationProgress
import com.smatrisciano.aipantry.recipes.domain.ListedRecipe
import com.smatrisciano.aipantry.recipes.domain.NextRecipe
import com.smatrisciano.aipantry.recipes.domain.RECIPES_PER_LIST

data class RecipesState(
    /** False until the list arrives from the repository, possibly already written. */
    val isLoaded: Boolean = false,
    /** Different for every list: a regenerated one starts from the top. */
    val listId: Long = -1,
    /** In display order; more arrive while [isGenerating]. */
    val recipes: List<ListedRecipe> = emptyList(),
    val isGenerating: Boolean = true,
    /** List generation failed without a single recipe: error shown with a Retry button. */
    val generationFailed: Boolean = false,
    val progressLog: List<GenerationProgress> = emptyList(),
    /** How far the list is (0..1), from what the model has actually read and written. */
    val progress: Float = 0f,
    val expectedCount: Int = RECIPES_PER_LIST,
    /** While the list grows: the recipe on its way, with its own progress. */
    val nextRecipe: NextRecipe? = null,
    /**
     * A list written ahead of time is still shown being made, the first time: the
     * wait screen stays up for a few seconds, its bar running up to where the list is.
     */
    val isRevealing: Boolean = false,
    val revealProgress: Float = 0f,
    val engineName: String = "",
    val ingredientCount: Int = 0
) {
    /** A first answer wasn't enough: the model is writing more recipes to add to these. */
    val isToppingUp: Boolean get() = GenerationProgress.Retrying in progressLog
}
