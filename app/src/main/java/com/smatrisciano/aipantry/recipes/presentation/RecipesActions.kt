package com.smatrisciano.aipantry.recipes.presentation

sealed interface RecipesActions {

    sealed interface Interaction : RecipesActions {
        data object OnRegenerateClick : Interaction
        data class OnRecipeOpened(val recipeIndex: Int) : Interaction
    }

    sealed interface Navigation : RecipesActions {
        data class GoToDetail(val recipeIndex: Int) : Navigation
        data object GoBack : Navigation
    }
}
