package com.smatrisciano.aipantry.capture.data.di

import com.smatrisciano.aipantry.capture.data.AdaptiveIngredientDetector
import com.smatrisciano.aipantry.capture.data.ClipZeroShotIngredientDetector
import com.smatrisciano.aipantry.capture.data.NanoIngredientDetector
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.presentation.CaptureViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val captureModule = module {
    // Detection: Gemini Nano dove c'è AICore, zero-shot CLIP altrove.
    // Gemma (LlmVisionIngredientDetector) resta nel codice come terza via
    // — utile per confronti — ma fuori dal percorso di default: minuti di
    // inferenza quando finisce su CPU.
    single<IngredientDetector> {
        AdaptiveIngredientDetector(
            nano = NanoIngredientDetector(),
            fallback = ClipZeroShotIngredientDetector(androidContext())
        )
    }
    viewModel { CaptureViewModel(get(), get()) }
}
