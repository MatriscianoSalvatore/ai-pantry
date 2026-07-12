package com.smatrisciano.aipantry.aisetup.presentation

import com.smatrisciano.aipantry.core.data.ai.LlmModel
import com.smatrisciano.aipantry.core.data.ai.ModelStatus

data class ModelUiState(
    val model: LlmModel,
    val status: ModelStatus
)

data class AiSetupState(
    val models: List<ModelUiState> = emptyList()
)
