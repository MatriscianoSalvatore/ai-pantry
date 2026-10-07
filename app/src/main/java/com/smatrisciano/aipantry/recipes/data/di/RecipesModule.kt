package com.smatrisciano.aipantry.recipes.data.di

import com.smatrisciano.aipantry.recipes.data.LlmRecipeGenerator
import com.smatrisciano.aipantry.recipes.data.RecipeRepositoryImpl
import com.smatrisciano.aipantry.recipes.domain.RecipeGenerator
import com.smatrisciano.aipantry.recipes.domain.RecipeRepository
import com.smatrisciano.aipantry.recipes.presentation.RecipesViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val recipesModule = module {
    single<RecipeGenerator> { LlmRecipeGenerator(get(), get(), get(), get(), get()) }
    // Created at startup: it writes the first list for the inventory ahead of time
    single<RecipeRepository>(createdAtStart = true) { RecipeRepositoryImpl(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { RecipesViewModel(get(), get()) }
}
