package com.smatrisciano.aipantry.recipes.domain

import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.recipes.domain.models.Recipe

/** Avanzamento della generazione: la UI lo traduce nella lingua del device. */
sealed interface GenerationProgress {
    data class LoadingModel(val modelName: String) : GenerationProgress
    data class Generating(val ingredientCount: Int) : GenerationProgress
    data object Retrying : GenerationProgress
}

interface RecipeGenerator {
    /** Nome del motore mostrato in UI (es. "Gemma 4 E2B · LiteRT" / "Demo mode"). */
    val engineName: String

    /**
     * Genera la lista delle ricette (senza istruzioni: tenerle fuori riduce
     * i token da generare di ~4x e rende la lista quasi immediata).
     */
    suspend fun generate(
        ingredients: List<Ingredient>,
        onProgress: (GenerationProgress) -> Unit
    ): List<Recipe>

    /** Completa una ricetta con istruzioni passo-passo e varianti (on-demand). */
    suspend fun generateDetails(recipe: Recipe, ingredients: List<Ingredient>): Recipe
}
