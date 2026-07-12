package com.smatrisciano.aipantry.core.data.ai

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AiSettings(context: Context) {

    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    private val _activeModelId = MutableStateFlow(
        prefs.getString(KEY_ACTIVE_MODEL, null) ?: LlmCatalog.default.id
    )
    val activeModelId = _activeModelId.asStateFlow()

    private val _hfToken = MutableStateFlow(prefs.getString(KEY_HF_TOKEN, "").orEmpty())
    val hfToken = _hfToken.asStateFlow()

    fun setActiveModel(id: String) {
        prefs.edit { putString(KEY_ACTIVE_MODEL, id) }
        _activeModelId.value = id
    }

    fun setHfToken(token: String) {
        prefs.edit { putString(KEY_HF_TOKEN, token.trim()) }
        _hfToken.value = token.trim()
    }

    private companion object {
        const val KEY_ACTIVE_MODEL = "active_model_id"
        const val KEY_HF_TOKEN = "hf_token"
    }
}
