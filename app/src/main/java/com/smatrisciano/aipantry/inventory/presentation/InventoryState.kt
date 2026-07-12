package com.smatrisciano.aipantry.inventory.presentation

import com.smatrisciano.aipantry.inventory.domain.models.Ingredient

data class InventoryState(
    val fridgeItems: List<Ingredient> = emptyList(),
    val pantryItems: List<Ingredient> = emptyList(),
    val isAiReady: Boolean = false,
    val aiStatusLabel: String = ""
) {
    val isEmpty: Boolean get() = fridgeItems.isEmpty() && pantryItems.isEmpty()
    val totalCount: Int get() = fridgeItems.size + pantryItems.size
}
