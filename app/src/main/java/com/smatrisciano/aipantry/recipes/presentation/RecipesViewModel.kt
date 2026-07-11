package com.smatrisciano.aipantry.recipes.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smatrisciano.aipantry.inventory.domain.repository.InventoryRepository
import com.smatrisciano.aipantry.recipes.domain.RecipeGenerator
import com.smatrisciano.aipantry.recipes.presentation.RecipesActions.Interaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecipesViewModel(
    private val inventoryRepository: InventoryRepository,
    private val recipeGenerator: RecipeGenerator
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecipesState(engineName = recipeGenerator.engineName))
    val uiState = _uiState.asStateFlow()

    init {
        generate()
    }

    fun onAction(action: Interaction) {
        when (action) {
            is Interaction.OnRegenerateClick -> generate()
        }
    }

    private fun generate() {
        _uiState.update {
            it.copy(isGenerating = true, progressLog = emptyList(), recipes = emptyList())
        }
        viewModelScope.launch {
            val inventory = inventoryRepository.observeInventory().first()
            _uiState.update { it.copy(ingredientCount = inventory.size) }
            val recipes = recipeGenerator.generate(inventory) { progress ->
                _uiState.update { it.copy(progressLog = it.progressLog + progress) }
            }
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    recipes = recipes,
                    engineName = recipeGenerator.engineName
                )
            }
        }
    }
}
