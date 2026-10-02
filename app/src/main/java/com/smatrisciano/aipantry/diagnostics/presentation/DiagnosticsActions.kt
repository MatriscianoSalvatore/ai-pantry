package com.smatrisciano.aipantry.diagnostics.presentation

sealed interface DiagnosticsActions {

    sealed interface Interaction : DiagnosticsActions {
        data object OnClearRecipeCacheClick : Interaction
    }

    sealed interface Navigation : DiagnosticsActions {
        data object GoBack : Navigation
    }
}
