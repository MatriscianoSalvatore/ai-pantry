package com.smatrisciano.aipantry.aisetup.di

import com.smatrisciano.aipantry.aisetup.presentation.AiSetupViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val aiSetupModule = module {
    viewModelOf(::AiSetupViewModel)
}
