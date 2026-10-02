package com.smatrisciano.aipantry.recipes.presentation

sealed interface RecipesActions {

    sealed interface Interaction : RecipesActions {
        data object OnRegenerateClick : Interaction
        data class OnRecipeOpened(val recipeId: Int) : Interaction
        data class OnRecipeClosed(val recipeId: Int) : Interaction
        data class OnRetryDetailsClick(val recipeId: Int) : Interaction
    }

    sealed interface Navigation : RecipesActions {
        data class GoToDetail(val recipeId: Int) : Navigation
        data object GoBack : Navigation
    }
}
