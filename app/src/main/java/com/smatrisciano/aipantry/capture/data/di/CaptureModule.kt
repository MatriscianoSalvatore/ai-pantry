package com.smatrisciano.aipantry.capture.data.di

import com.smatrisciano.aipantry.capture.data.LlmVisionIngredientDetector
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.presentation.CaptureViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val captureModule = module {
    single<IngredientDetector> { LlmVisionIngredientDetector(get(), get()) }
    viewModel { CaptureViewModel(get(), get()) }
}
