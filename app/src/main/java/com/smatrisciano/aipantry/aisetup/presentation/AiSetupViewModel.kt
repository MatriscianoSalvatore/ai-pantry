package com.smatrisciano.aipantry.aisetup.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smatrisciano.aipantry.aisetup.presentation.AiSetupActions.Interaction
import com.smatrisciano.aipantry.core.data.ai.AiSettings
import com.smatrisciano.aipantry.core.data.ai.LlmCatalog
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import com.smatrisciano.aipantry.core.data.ai.ModelStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class AiSetupViewModel(
    private val settings: AiSettings,
    private val modelRepository: ModelRepository
) : ViewModel() {

    val uiState = combine(
        settings.hfToken,
        settings.activeModelId,
        modelRepository.statuses
    ) { token, activeId, statuses ->
        AiSetupState(
            hfToken = token,
            models = LlmCatalog.all.map { model ->
                ModelUiState(
                    model = model,
                    status = statuses[model.id] ?: ModelStatus.NotDownloaded(),
                    isActive = model.id == activeId
                )
            }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AiSetupState()
    )

    fun onAction(action: Interaction) {
        when (action) {
            is Interaction.OnTokenChanged -> settings.setHfToken(action.token)
            is Interaction.OnDownloadClick ->
                modelRepository.startDownload(LlmCatalog.byId(action.modelId))
            is Interaction.OnCancelClick ->
                modelRepository.cancelDownload(LlmCatalog.byId(action.modelId))
            is Interaction.OnDeleteClick ->
                modelRepository.deleteModel(LlmCatalog.byId(action.modelId))
            is Interaction.OnModelSelected ->
                modelRepository.setActiveModel(LlmCatalog.byId(action.modelId))
        }
    }
}
