package com.smatrisciano.aipantry.capture.data.di

import com.smatrisciano.aipantry.capture.data.DemoIngredientDetector
import com.smatrisciano.aipantry.capture.data.MediaPipeIngredientDetector
import com.smatrisciano.aipantry.capture.data.SmartIngredientDetector
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.presentation.CaptureViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val captureModule = module {
    single { MediaPipeIngredientDetector(androidContext()) }
    single { DemoIngredientDetector() }
    single<IngredientDetector> { SmartIngredientDetector(androidContext(), get(), get()) }
    viewModel { CaptureViewModel(get(), get()) }
}
