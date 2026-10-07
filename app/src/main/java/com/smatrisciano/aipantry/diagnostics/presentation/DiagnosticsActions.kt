package com.smatrisciano.aipantry.diagnostics.presentation

import com.smatrisciano.aipantry.core.data.ai.ModelChoice

sealed interface DiagnosticsActions {

    sealed interface Interaction : DiagnosticsActions {
        data object OnClearRecipeCacheClick : Interaction
        data class OnScanChoice(val choice: ModelChoice) : Interaction
        data class OnRecipeChoice(val choice: ModelChoice) : Interaction
        data class OnVerboseChange(val on: Boolean) : Interaction
        data object OnRemoveGemmaClick : Interaction
        data object OnRestoreGemmaClick : Interaction
    }

    sealed interface Navigation : DiagnosticsActions {
        data object GoBack : Navigation
    }
}
