package com.smatrisciano.aipantry.core.data.ai

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which model a task is given to. [AUTO] is the default order (Gemini Nano where the phone
 * has it, then the others); a choice whose model isn't there falls back to that order.
 * [CLIP] and [EMBEDDING_GEMMA] only look at photos: they never write the recipes, and
 * [AUTO] never picks EmbeddingGemma, which is there for trying out by hand.
 */
enum class ModelChoice(val writesRecipes: Boolean = true) {
    AUTO,
    NANO,
    GEMMA,
    CLIP(writesRecipes = false),
    EMBEDDING_GEMMA(writesRecipes = false)
}

/**
 * Where a Gemma version runs. [AUTO] tries the GPU and falls back to the CPU for good if it
 * fails; [GPU] and [CPU] are what the user picked by hand (a GPU that fails still falls back to
 * the CPU for the rest of the session, without being written off).
 */
enum class BackendChoice { AUTO, GPU, CPU }

/**
 * What the hidden page lets you decide: the model for each task, the verbose display, and
 * what the app has to remember about the models (Gemma taken off by hand, the size AICore
 * said Nano has). Kept in the app's own preferences.
 */
class ModelPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("model_choices", Context.MODE_PRIVATE)

    private val _scan = MutableStateFlow(read(KEY_SCAN))

    /** The model that recognises the ingredients in a photo. */
    val scan: StateFlow<ModelChoice> = _scan.asStateFlow()

    private val _recipes = MutableStateFlow(read(KEY_RECIPES).takeIf { it.writesRecipes } ?: ModelChoice.AUTO)

    /** The model that writes the recipes (never one that only looks at photos). */
    val recipes: StateFlow<ModelChoice> = _recipes.asStateFlow()

    private val _verbose = MutableStateFlow(prefs.getBoolean(KEY_VERBOSE, false))

    /** Speed, heat and model in use drawn over every screen. */
    val verbose: StateFlow<Boolean> = _verbose.asStateFlow()

    /** The Gemma version in use is on the device and ready, as the model repository last saw it. */
    @Volatile
    var gemmaReady: Boolean = false

    private val _activeModelId = MutableStateFlow(prefs.getString(KEY_ACTIVE_MODEL, null) ?: LlmCatalog.default.id)

    /** The Gemma version that writes and looks at photos when Gemma is the one chosen. */
    val activeModelId: StateFlow<String> = _activeModelId.asStateFlow()

    fun setActiveModelId(id: String) {
        prefs.edit { putString(KEY_ACTIVE_MODEL, id) }
        _activeModelId.value = id
    }

    /** [id] was removed by hand: it isn't provisioned again until it is restored. */
    fun isRemoved(id: String): Boolean = prefs.getStringSet(KEY_REMOVED, emptySet()).orEmpty().contains(id)

    fun setRemoved(id: String, removed: Boolean) {
        val now = prefs.getStringSet(KEY_REMOVED, emptySet()).orEmpty().toMutableSet()
        if (removed) now.add(id) else now.remove(id)
        prefs.edit { putStringSet(KEY_REMOVED, now) }
    }

    /**
     * What AICore said Gemini Nano weighs, the one time it said so (when the download
     * started): the app has no other way to know, the file isn't its own. Zero if never.
     */
    var nanoDownloadBytes: Long
        get() = prefs.getLong(KEY_NANO_BYTES, 0L)
        set(value) = prefs.edit { putLong(KEY_NANO_BYTES, value) }

    /** Where the verbose box was left, as its offset in pixels from the top right corner. */
    var overlayX: Float
        get() = prefs.getFloat(KEY_OVERLAY_X, 0f)
        set(value) = prefs.edit { putFloat(KEY_OVERLAY_X, value) }

    var overlayY: Float
        get() = prefs.getFloat(KEY_OVERLAY_Y, 0f)
        set(value) = prefs.edit { putFloat(KEY_OVERLAY_Y, value) }

    private val _backends = MutableStateFlow(LlmCatalog.all.associate { it.id to readBackend(it.id) })

    /** Where each Gemma version runs, by model id. */
    val backends: StateFlow<Map<String, BackendChoice>> = _backends.asStateFlow()

    fun backendFor(modelId: String): BackendChoice = _backends.value[modelId] ?: BackendChoice.AUTO

    fun setBackend(modelId: String, choice: BackendChoice) {
        prefs.edit { putString("$KEY_BACKEND_PREFIX$modelId", choice.name) }
        _backends.value = _backends.value + (modelId to choice)
    }

    private fun readBackend(modelId: String): BackendChoice =
        runCatching { BackendChoice.valueOf(prefs.getString("$KEY_BACKEND_PREFIX$modelId", null).orEmpty()) }
            .getOrDefault(BackendChoice.AUTO)

    fun setScan(choice: ModelChoice) {
        prefs.edit { putString(KEY_SCAN, choice.name) }
        _scan.value = choice
    }

    fun setRecipes(choice: ModelChoice) {
        val allowed = choice.takeIf { it.writesRecipes } ?: ModelChoice.AUTO
        prefs.edit { putString(KEY_RECIPES, allowed.name) }
        _recipes.value = allowed
    }

    fun setVerbose(on: Boolean) {
        prefs.edit { putBoolean(KEY_VERBOSE, on) }
        _verbose.value = on
    }

    private fun read(key: String): ModelChoice =
        runCatching { ModelChoice.valueOf(prefs.getString(key, null).orEmpty()) }.getOrDefault(ModelChoice.AUTO)

    private companion object {
        const val KEY_SCAN = "scan"
        const val KEY_BACKEND_PREFIX = "backend_"
        const val KEY_RECIPES = "recipes"
        const val KEY_VERBOSE = "verbose"
        const val KEY_OVERLAY_X = "overlay_x"
        const val KEY_OVERLAY_Y = "overlay_y"
        const val KEY_REMOVED = "models_removed_by_user"
        const val KEY_ACTIVE_MODEL = "active_model"
        const val KEY_NANO_BYTES = "nano_download_bytes"
    }
}
