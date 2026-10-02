package com.smatrisciano.aipantry.inventory.presentation

sealed interface InventoryActions {

    sealed interface Interaction : InventoryActions {
        data class OnIngredientRemoved(val name: String) : Interaction
        data object OnClearAllClick : Interaction
    }

    sealed interface Navigation : InventoryActions {
        data object GoToCapture : Navigation
        data object GoToRecipes : Navigation

        /** The hidden diagnostics page: six quick taps on the brand mark. */
        data object GoToDiagnostics : Navigation
    }
}
