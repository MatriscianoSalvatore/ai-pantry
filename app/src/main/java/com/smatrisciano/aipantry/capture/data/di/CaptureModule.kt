package com.smatrisciano.aipantry.capture.data.di

import com.smatrisciano.aipantry.capture.data.AdaptiveIngredientDetector
import com.smatrisciano.aipantry.capture.data.ClipZeroShotIngredientDetector
import com.smatrisciano.aipantry.capture.data.LlmVisionIngredientDetector
import com.smatrisciano.aipantry.capture.data.NanoIngredientDetector
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.presentation.CaptureViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val captureModule = module {
    // Detection: Gemini Nano where AICore is available, CLIP zero-shot elsewhere. Gemma
    // (LlmVisionIngredientDetector) is for when it is chosen on the hidden page: minutes of
    // inference when it ends up on CPU. See AdaptiveIngredientDetector.
    single { NanoIngredientDetector(get()) }
    single { ClipZeroShotIngredientDetector(androidContext()) }
    single { LlmVisionIngredientDetector(get(), get()) }
    single<IngredientDetector> {
        AdaptiveIngredientDetector(
            nano = get<NanoIngredientDetector>(),
            gemma = get<LlmVisionIngredientDetector>(),
            clip = get<ClipZeroShotIngredientDetector>(),
            modelRepository = get(),
            engineHolder = get(),
            choices = get(),
            stats = get()
        )
    }
    viewModel { CaptureViewModel(get(), get(), get()) }
}
