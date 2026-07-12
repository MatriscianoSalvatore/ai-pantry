package com.smatrisciano.aipantry.aisetup.presentation

import com.smatrisciano.aipantry.core.data.ai.LlmModel
import com.smatrisciano.aipantry.core.data.ai.ModelStatus

data class ModelUiState(
    val model: LlmModel,
    val status: ModelStatus,
    val isActive: Boolean
)

data class AiSetupState(
    val hfToken: String = "",
    val models: List<ModelUiState> = emptyList()
)
