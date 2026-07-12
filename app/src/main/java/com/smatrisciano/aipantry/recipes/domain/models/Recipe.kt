package com.smatrisciano.aipantry.recipes.domain.models

import kotlinx.serialization.Serializable

@Serializable
enum class Difficulty { EASY, MEDIUM, HARD }

@Serializable
data class RecipeIngredient(
    val name: String,
    /** Quantità per questa ricetta, es. "200 g", "2", "1 can". Vuota se non specificata. */
    val quantity: String = ""
) {
    /** Etichetta per la UI: "Pasta - 200 g" o solo "Pasta" se manca la quantità. */
    val display: String get() = if (quantity.isBlank()) name else "$name - $quantity"
}

@Serializable
data class Recipe(
    val title: String,
    val whySuitable: String,
    val prepTimeMinutes: Int,
    val difficulty: Difficulty = Difficulty.EASY,
    val usedIngredients: List<RecipeIngredient>,
    val missingIngredients: List<RecipeIngredient> = emptyList(),
    val steps: List<String>,
    val variants: List<String> = emptyList()
)
