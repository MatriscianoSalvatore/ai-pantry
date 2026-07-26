package com.smatrisciano.aipantry.recipes.domain

import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.recipes.domain.models.Recipe

interface RecipeGenerator {
    /** Nome del motore mostrato in UI (es. "Gemma 4 E2B · LiteRT" / "Demo mode"). */
    val engineName: String

    /**
     * Genera la lista delle ricette (senza istruzioni: tenerle fuori riduce
     * i token da generare di ~4x e rende la lista quasi immediata).
     */
    suspend fun generate(
        ingredients: List<Ingredient>,
        onProgress: (String) -> Unit
    ): List<Recipe>

    /** Completa una ricetta con istruzioni passo-passo e varianti (on-demand). */
    suspend fun generateDetails(recipe: Recipe, ingredients: List<Ingredient>): Recipe
}
