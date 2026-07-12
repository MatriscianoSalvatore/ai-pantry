package com.smatrisciano.aipantry.aisetup.presentation

sealed interface AiSetupActions {

    sealed interface Interaction : AiSetupActions {
        data class OnDownloadClick(val modelId: String) : Interaction
        data class OnCancelClick(val modelId: String) : Interaction
        data class OnDeleteClick(val modelId: String) : Interaction
        data object OnConfirmDownloadClick : Interaction
    }

    sealed interface Navigation : AiSetupActions {
        data object GoBack : Navigation
    }
}
