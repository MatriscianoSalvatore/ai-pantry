package com.smatrisciano.aipantry.inventory.presentation.composables

private val emojiMap = mapOf(
    "mozzarella" to "🧀",
    "milk" to "🥛",
    "tomatoes" to "🍅",
    "basil" to "🌿",
    "pasta" to "🍝",
    "canned tuna" to "🐟",
    "onions" to "🧅",
    "parmigiano" to "🧀"
)

fun ingredientEmoji(name: String): String = emojiMap[name.lowercase()] ?: "🥘"
