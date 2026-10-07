package com.smatrisciano.aipantry.diagnostics.presentation

import com.smatrisciano.aipantry.core.data.ai.ModelStatus
import com.smatrisciano.aipantry.core.data.ai.NanoState
import com.smatrisciano.aipantry.diagnostics.data.DeviceInfo
import com.smatrisciano.aipantry.diagnostics.data.DeviceSnapshot
import com.smatrisciano.aipantry.recipes.domain.CachedList
import com.smatrisciano.aipantry.recipes.domain.RecipeWork

data class ModelInfo(
    val name: String,
    val status: ModelStatus?,
    val fileBytes: Long?,
    val loaded: Boolean,
    val gpu: Boolean,
    val cpuThreads: Int,
    val gpuDisabled: Boolean,
    val cacheBytes: Long,
    val cacheFileCount: Int,
    val detectorName: String
)

/** What the progress bars measure the model against, as learned on this device. */
data class LearnedWaits(
    val promptReadingMillis: Long,
    val listRecipeChars: Long,
    val detailsChars: Long
)

data class DiagnosticsState(
    /** Null until the first reading. */
    val device: DeviceSnapshot? = null,
    val deviceInfo: DeviceInfo? = null,
    val appVersion: String = "",
    val model: ModelInfo? = null,
    /** Null while AICore hasn't answered yet. */
    val nano: NanoState? = null,
    val learned: LearnedWaits? = null,
    /** Recipes may be written ahead of time right now (not with the camera open or the app hidden). */
    val aheadAllowed: Boolean = true,
    val work: RecipeWork = RecipeWork.Idle,
    val cache: List<CachedList> = emptyList()
)
