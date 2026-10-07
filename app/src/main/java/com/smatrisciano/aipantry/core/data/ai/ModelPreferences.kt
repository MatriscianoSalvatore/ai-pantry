package com.smatrisciano.aipantry.core.data.ai

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which model a task is given to. [AUTO] is the default order (Gemini Nano where the phone
 * has it, then the others); a choice whose model isn't there falls back to that order.
 */
enum class ModelChoice { AUTO, NANO, GEMMA, CLIP }

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

    private val _recipes = MutableStateFlow(read(KEY_RECIPES).takeUnless { it == ModelChoice.CLIP } ?: ModelChoice.AUTO)

    /** The model that writes the recipes (CLIP only looks at photos). */
    val recipes: StateFlow<ModelChoice> = _recipes.asStateFlow()

    private val _verbose = MutableStateFlow(prefs.getBoolean(KEY_VERBOSE, false))

    /** Speed, heat and model in use drawn over every screen. */
    val verbose: StateFlow<Boolean> = _verbose.asStateFlow()

    /** Gemma is on the device and ready, as the model repository last saw it. */
    @Volatile
    var gemmaReady: Boolean = false

    /** Gemma was removed by hand: it isn't provisioned again until it is restored. */
    var gemmaRemovedByUser: Boolean
        get() = prefs.getBoolean(KEY_GEMMA_REMOVED, false)
        set(value) = prefs.edit { putBoolean(KEY_GEMMA_REMOVED, value) }

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

    fun setScan(choice: ModelChoice) {
        prefs.edit { putString(KEY_SCAN, choice.name) }
        _scan.value = choice
    }

    fun setRecipes(choice: ModelChoice) {
        val allowed = choice.takeUnless { it == ModelChoice.CLIP } ?: ModelChoice.AUTO
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
        const val KEY_RECIPES = "recipes"
        const val KEY_VERBOSE = "verbose"
        const val KEY_OVERLAY_X = "overlay_x"
        const val KEY_OVERLAY_Y = "overlay_y"
        const val KEY_GEMMA_REMOVED = "gemma_removed_by_user"
        const val KEY_NANO_BYTES = "nano_download_bytes"
    }
}
