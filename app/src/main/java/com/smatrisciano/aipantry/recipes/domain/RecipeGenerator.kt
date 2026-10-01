package com.smatrisciano.aipantry.recipes.domain

import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.recipes.domain.models.Recipe

/** Generation progress: the UI translates it into the device language. */
sealed interface GenerationProgress {
    data class LoadingModel(val modelName: String) : GenerationProgress
    data class Generating(val ingredientCount: Int) : GenerationProgress
    data object Retrying : GenerationProgress
}

interface RecipeGenerator {
    /** Engine name shown in the UI (e.g. "Gemma 4 E2B · LiteRT"). */
    val engineName: String

    /**
     * Generates the list of recipes (without instructions: leaving them out cuts
     * the tokens to generate by ~4x and makes the list almost immediate).
     */
    suspend fun generate(
        ingredients: List<Ingredient>,
        onProgress: (GenerationProgress) -> Unit
    ): List<Recipe>

    /** Completes a recipe with step-by-step instructions and variants (on demand). */
    suspend fun generateDetails(recipe: Recipe, ingredients: List<Ingredient>): Recipe
}
