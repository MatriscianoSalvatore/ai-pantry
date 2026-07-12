package com.smatrisciano.aipantry.aisetup.presentation

sealed interface AiSetupActions {

    sealed interface Interaction : AiSetupActions {
        data class OnTokenChanged(val token: String) : Interaction
        data class OnDownloadClick(val modelId: String) : Interaction
        data class OnCancelClick(val modelId: String) : Interaction
        data class OnDeleteClick(val modelId: String) : Interaction
        data class OnModelSelected(val modelId: String) : Interaction
    }

    sealed interface Navigation : AiSetupActions {
        data object GoBack : Navigation
    }
}
