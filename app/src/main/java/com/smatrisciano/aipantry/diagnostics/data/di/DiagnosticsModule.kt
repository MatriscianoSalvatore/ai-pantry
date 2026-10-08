package com.smatrisciano.aipantry.diagnostics.data.di

import com.smatrisciano.aipantry.diagnostics.data.DeviceMonitor
import com.smatrisciano.aipantry.diagnostics.presentation.DiagnosticsViewModel
import com.smatrisciano.aipantry.diagnostics.presentation.VerboseViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val diagnosticsModule = module {
    single { DeviceMonitor(androidContext()) }
    viewModel { VerboseViewModel(get(), get(), get(), get()) }
    viewModel { DiagnosticsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
