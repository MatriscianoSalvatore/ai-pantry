package com.smatrisciano.aipantry.aisetup.presentation

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smatrisciano.aipantry.aisetup.presentation.AiSetupActions.Interaction
import com.smatrisciano.aipantry.core.data.ai.LlmCatalog
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import com.smatrisciano.aipantry.core.data.ai.ModelStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AiSetupViewModel(
    private val modelRepository: ModelRepository
) : ViewModel() {

    /** Launcher del dialog di conferma Play, registrato dalla screen. */
    var confirmationLauncher: ActivityResultLauncher<IntentSenderRequest>? = null

    val uiState = modelRepository.statuses
        .map { statuses ->
            AiSetupState(
                models = LlmCatalog.all.map { model ->
                    ModelUiState(
                        model = model,
                        status = statuses[model.id] ?: ModelStatus.NotInstalled
                    )
                }
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AiSetupState()
        )

    fun onAction(action: Interaction) {
        when (action) {
            is Interaction.OnDownloadClick ->
                modelRepository.startDownload(LlmCatalog.byId(action.modelId))
            is Interaction.OnCancelClick ->
                modelRepository.cancelDownload(LlmCatalog.byId(action.modelId))
            is Interaction.OnDeleteClick ->
                modelRepository.deleteModel(LlmCatalog.byId(action.modelId))
            is Interaction.OnConfirmDownloadClick ->
                confirmationLauncher?.let { modelRepository.showConfirmationDialog(it) }
        }
    }
}
