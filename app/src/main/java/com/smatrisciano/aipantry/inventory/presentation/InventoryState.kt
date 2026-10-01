package com.smatrisciano.aipantry.inventory.presentation

import com.smatrisciano.aipantry.inventory.domain.models.Ingredient

/** Stato del modello LLM per il banner: la UI lo traduce nella lingua del device. */
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
