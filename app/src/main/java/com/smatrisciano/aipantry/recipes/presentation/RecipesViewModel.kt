package com.smatrisciano.aipantry.recipes.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.inventory.domain.repository.InventoryRepository
import com.smatrisciano.aipantry.recipes.domain.RecipeGenerator
import com.smatrisciano.aipantry.recipes.presentation.RecipesActions.Interaction
import kotlinx.coroutines.CancellationException
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

    private var inventory: List<Ingredient> = emptyList()

    init {
        generate()
    }

    fun onAction(action: Interaction) {
        when (action) {
            is Interaction.OnRegenerateClick -> generate()
            is Interaction.OnRecipeOpened -> loadDetails(action.recipeIndex)
        }
    }

    private fun generate() {
        _uiState.update {
            it.copy(isGenerating = true, progressLog = emptyList(), recipes = emptyList(), generationFailed = false)
        }
        viewModelScope.launch {
            inventory = inventoryRepository.observeInventory().first()
            _uiState.update { it.copy(ingredientCount = inventory.size) }
            runCatching {
                recipeGenerator.generate(inventory) { progress ->
                    _uiState.update { it.copy(progressLog = it.progressLog + progress) }
                }
            }.onSuccess { recipes ->
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        recipes = recipes,
                        engineName = recipeGenerator.engineName
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Recipe generation failed", error)
                _uiState.update { it.copy(isGenerating = false, generationFailed = true) }
            }
        }
    }

    /** Istruzioni generate on-demand alla prima apertura della ricetta. */
    private fun loadDetails(index: Int) {
        val recipe = _uiState.value.recipes.getOrNull(index) ?: return
        if (recipe.steps.isNotEmpty() || _uiState.value.isDetailLoading) return
        _uiState.update { it.copy(isDetailLoading = true, detailFailed = false) }
        viewModelScope.launch {
            runCatching { recipeGenerator.generateDetails(recipe, inventory) }
                .onSuccess { detailed ->
                    _uiState.update { state ->
                        state.copy(
                            isDetailLoading = false,
                            recipes = state.recipes.toMutableList().also { list ->
                                if (index in list.indices) list[index] = detailed
                            }
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Recipe details generation failed", error)
                    _uiState.update { it.copy(isDetailLoading = false, detailFailed = true) }
                }
        }
    }

    private companion object {
        const val TAG = "RecipesViewModel"
    }
}
