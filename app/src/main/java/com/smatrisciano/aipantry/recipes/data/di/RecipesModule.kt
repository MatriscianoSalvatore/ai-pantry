package com.smatrisciano.aipantry.recipes.data.di

import com.smatrisciano.aipantry.recipes.data.DemoRecipeGenerator
import com.smatrisciano.aipantry.recipes.data.LlmRecipeGenerator
import com.smatrisciano.aipantry.recipes.data.SmartRecipeGenerator
import com.smatrisciano.aipantry.recipes.domain.RecipeGenerator
import com.smatrisciano.aipantry.recipes.presentation.RecipesViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val recipesModule = module {
    single { LlmRecipeGenerator(get(), get()) }
    single { DemoRecipeGenerator() }
    single<RecipeGenerator> { SmartRecipeGenerator(get(), get(), get()) }
    viewModel { RecipesViewModel(get(), get()) }
}
