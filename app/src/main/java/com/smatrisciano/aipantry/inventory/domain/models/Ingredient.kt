package com.smatrisciano.aipantry.inventory.domain.models

enum class IngredientSource { FRIDGE, PANTRY, MANUAL }

data class Ingredient(
    val name: String,
    val quantity: String,
    val confidence: Float,
    val source: IngredientSource,
    val detectedAtMillis: Long
)
