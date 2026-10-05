package com.smatrisciano.aipantry.recipes.domain.models

import kotlinx.serialization.Serializable

@Serializable
enum class Difficulty { EASY, MEDIUM, HARD }

@Serializable
data class RecipeIngredient(
    val name: String,
    /** Quantity for this recipe, e.g. "200 g", "2", "1 can". Empty if not specified. */
    val quantity: String = ""
) {
    /** UI label: "Pasta - 200 g", or just "Pasta" when the quantity is missing. */
    val display: String get() = if (quantity.isBlank()) name else "$name - $quantity"
}

@Serializable
data class Recipe(
    val title: String,
    // val whySuitable: String,
    val prepTimeMinutes: Int,
    val difficulty: Difficulty = Difficulty.EASY,
    val usedIngredients: List<RecipeIngredient>,
    val missingIngredients: List<RecipeIngredient> = emptyList(),
    val steps: List<String>,
    val variants: List<String> = emptyList()
)
