package com.smatrisciano.aipantry.recipes.domain

import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.recipes.domain.models.Recipe

interface RecipeGenerator {
    /** Nome del motore mostrato in UI (es. "Gemma 3n · LiteRT" / "Demo mode"). */
    val engineName: String

    suspend fun generate(
        ingredients: List<Ingredient>,
        onProgress: (String) -> Unit
    ): List<Recipe>
}
