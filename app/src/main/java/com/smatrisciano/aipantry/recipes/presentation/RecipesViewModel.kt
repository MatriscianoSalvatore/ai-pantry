package com.smatrisciano.aipantry.recipes.presentation

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smatrisciano.aipantry.core.presentation.composables.WAIT_COMPLETION_MILLIS
import com.smatrisciano.aipantry.inventory.domain.repository.InventoryRepository
import com.smatrisciano.aipantry.recipes.domain.ListStatus
import com.smatrisciano.aipantry.recipes.domain.RecipeList
import com.smatrisciano.aipantry.recipes.domain.RecipeRepository
import com.smatrisciano.aipantry.recipes.domain.RecipeSession
import com.smatrisciano.aipantry.recipes.presentation.RecipesActions.Interaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecipesViewModel(
    private val inventoryRepository: InventoryRepository,
    private val recipeRepository: RecipeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecipesState(engineName = recipeRepository.engineName))
    val uiState = _uiState.asStateFlow()

    private var session: RecipeSession? = null

    init {
        viewModelScope.launch {
            val inventory = inventoryRepository.observeInventory().first()
            _uiState.update { it.copy(ingredientCount = inventory.size) }
            val opened = recipeRepository.open(inventory)
            session = opened
            if (opened.writtenAhead) reveal()
            opened.list.collect { list -> _uiState.update { it.with(list) } }
        }
    }

    fun onAction(action: Interaction) {
        when (action) {
            is Interaction.OnRegenerateClick -> session?.regenerate()
            is Interaction.OnRecipeOpened -> session?.showDetails(action.recipeId)
            is Interaction.OnRecipeClosed -> session?.showList()
            is Interaction.OnRetryDetailsClick -> session?.retryDetails(action.recipeId)
        }
    }

    override fun onCleared() {
        session?.close()
    }

    /**
     * The list was written ahead of time: the wait screen still stays up for
     * [REVEAL_MILLIS], its bar running up to where the list really is (all the way,
     * once it's complete), so the generation is seen happening on the device.
     */
    private fun reveal() {
        _uiState.update { it.copy(isRevealing = true, revealProgress = 0f) }
        viewModelScope.launch {
            val start = SystemClock.elapsedRealtime()
            while (true) {
                val time = ((SystemClock.elapsedRealtime() - start).toFloat() / REVEAL_MILLIS).coerceAtMost(1f)
                // Quick at first, slowing down towards the end
                val eased = 1 - (1 - time) * (1 - time)
                _uiState.update { it.copy(revealProgress = eased * it.progress) }
                if (time >= 1f) break
                delay(REVEAL_TICK_MILLIS)
            }
            // A moment to see the bar full before the recipes
            delay(WAIT_COMPLETION_MILLIS)
            _uiState.update { it.copy(isRevealing = false) }
        }
    }

    private fun RecipesState.with(list: RecipeList) = copy(
        isLoaded = true,
        listId = list.id,
        recipes = list.recipes,
        isGenerating = list.status == ListStatus.GENERATING,
        generationFailed = list.status == ListStatus.FAILED,
        progressLog = list.steps,
        progress = list.progress,
        expectedCount = list.expectedCount,
        nextRecipe = list.nextRecipe,
        engineName = recipeRepository.engineName
    )

    private companion object {
        const val REVEAL_MILLIS = 7_000L
        const val REVEAL_TICK_MILLIS = 100L
    }
}
