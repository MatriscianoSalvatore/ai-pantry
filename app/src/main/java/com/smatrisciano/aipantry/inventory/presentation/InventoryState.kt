package com.smatrisciano.aipantry.inventory.presentation

import com.smatrisciano.aipantry.inventory.domain.models.Ingredient

/** LLM status for the banner: the UI translates it into the device language. */
enum class AiStatus { READY, DOWNLOADING, PREPARING, FAILED }

data class InventoryState(
    val fridgeItems: List<Ingredient> = emptyList(),
    val pantryItems: List<Ingredient> = emptyList(),
    val aiStatus: AiStatus = AiStatus.PREPARING,
    val modelName: String = ""
) {
    val isAiReady: Boolean get() = aiStatus == AiStatus.READY
    val isEmpty: Boolean get() = fridgeItems.isEmpty() && pantryItems.isEmpty()
    val totalCount: Int get() = fridgeItems.size + pantryItems.size
}
