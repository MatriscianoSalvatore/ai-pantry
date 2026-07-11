package com.smatrisciano.aipantry.recipes.domain.models

import kotlinx.serialization.Serializable

@Serializable
enum class Difficulty { EASY, MEDIUM, HARD }

@Serializable
data class Recipe(
    val title: String,
    val whySuitable: String,
    val prepTimeMinutes: Int,
    val difficulty: Difficulty = Difficulty.EASY,
    val usedIngredients: List<String>,
    val missingIngredients: List<String> = emptyList(),
    val steps: List<String>,
    val variants: List<String> = emptyList()
)
