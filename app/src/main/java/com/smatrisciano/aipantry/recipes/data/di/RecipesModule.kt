package com.smatrisciano.aipantry.recipes.data.di

import com.smatrisciano.aipantry.recipes.data.DemoRecipeGenerator
import com.smatrisciano.aipantry.recipes.data.GemmaRecipeGenerator
import com.smatrisciano.aipantry.recipes.data.SmartRecipeGenerator
import com.smatrisciano.aipantry.recipes.domain.RecipeGenerator
import com.smatrisciano.aipantry.recipes.presentation.RecipesViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val recipesModule = module {
    single { GemmaRecipeGenerator(androidContext()) }
    single { DemoRecipeGenerator() }
    single<RecipeGenerator> { SmartRecipeGenerator(androidContext(), get(), get()) }
    viewModel { RecipesViewModel(get(), get()) }
}
