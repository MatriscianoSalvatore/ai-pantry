package com.smatrisciano.aipantry.inventory.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smatrisciano.aipantry.inventory.domain.models.IngredientSource
import com.smatrisciano.aipantry.inventory.domain.repository.InventoryRepository
import com.smatrisciano.aipantry.inventory.presentation.InventoryActions.Interaction
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InventoryViewModel(
    private val inventoryRepository: InventoryRepository
) : ViewModel() {

    val uiState = inventoryRepository.observeInventory()
        .map { ingredients ->
            InventoryState(
                fridgeItems = ingredients.filter { it.source == IngredientSource.FRIDGE },
                pantryItems = ingredients.filter { it.source != IngredientSource.FRIDGE }
            )
        }
        .stateIn(
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
