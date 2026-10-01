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
    // Detection: Gemini Nano where AICore is available, CLIP zero-shot elsewhere.
    // Gemma (LlmVisionIngredientDetector) stays in the code as a third route,
    // useful for comparisons, but off the default path: minutes of inference
    // when it ends up on CPU.
    single<IngredientDetector> {
        AdaptiveIngredientDetector(
            nano = NanoIngredientDetector(),
            fallback = ClipZeroShotIngredientDetector(androidContext())
        )
    }
    viewModel { CaptureViewModel(get(), get(), get()) }
}
