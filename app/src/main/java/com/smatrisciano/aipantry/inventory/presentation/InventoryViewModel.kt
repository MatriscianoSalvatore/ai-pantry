package com.smatrisciano.aipantry.inventory.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smatrisciano.aipantry.core.data.ai.GeminiNanoWriter
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import com.smatrisciano.aipantry.core.data.ai.ModelStatus
import com.smatrisciano.aipantry.inventory.domain.models.IngredientSource
import com.smatrisciano.aipantry.inventory.domain.repository.InventoryRepository
import com.smatrisciano.aipantry.inventory.presentation.InventoryActions.Interaction
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InventoryViewModel(
    private val inventoryRepository: InventoryRepository,
    modelRepository: ModelRepository,
    nano: GeminiNanoWriter
) : ViewModel() {

    val uiState = combine(
        inventoryRepository.observeInventory(),
        modelRepository.statuses,
        nano.available
    ) { ingredients, statuses, nanoWrites ->
        val activeModel = modelRepository.activeModel()
        val status = statuses[activeModel.id]
        InventoryState(
            isLoaded = true,
            fridgeItems = ingredients.filter { it.source == IngredientSource.FRIDGE },
            pantryItems = ingredients.filter { it.source != IngredientSource.FRIDGE },
            // Where Gemini Nano 4 writes the recipes, Gemma's state doesn't hold them up
            aiStatus = if (nanoWrites) AiStatus.READY else when (status) {
                ModelStatus.Ready -> AiStatus.READY
                is ModelStatus.Downloading -> AiStatus.DOWNLOADING
                is ModelStatus.Failed -> AiStatus.FAILED
                else -> AiStatus.PREPARING
            },
            modelName = if (nanoWrites) GeminiNanoWriter.DISPLAY_NAME else activeModel.displayName
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InventoryState()
    )

    fun onAction(action: Interaction) {
        when (action) {
            is Interaction.OnIngredientRemoved -> viewModelScope.launch {
                inventoryRepository.remove(action.name)
            }
            is Interaction.OnClearAllClick -> viewModelScope.launch {
                inventoryRepository.clear()
            }
        }
    }
}
