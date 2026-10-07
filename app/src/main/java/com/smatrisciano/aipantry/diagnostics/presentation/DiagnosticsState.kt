package com.smatrisciano.aipantry.diagnostics.presentation

import com.smatrisciano.aipantry.core.data.ai.ModelChoice
import com.smatrisciano.aipantry.core.data.ai.ModelStatus
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

/** What each model weighs on the device, and whether it is there. Null bytes: not there, or not known. */
data class ModelWeights(
    val gemmaBytes: Long?,
    val gemmaCacheBytes: Long,
    val gemmaStatus: ModelStatus?,
    val gemmaRemovedByUser: Boolean,
    val clipBytes: Long?,
    val nanoBytes: Long?,
    val nanoPresent: Boolean,
    val nanoBaseModel: String?
)

/** The model chosen for each task, and which models can be chosen right now. */
data class ModelSelection(
    val scan: ModelChoice,
    val recipes: ModelChoice,
    val verbose: Boolean,
    val nanoPresent: Boolean,
    val gemmaReady: Boolean,
    val clipPresent: Boolean
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
    val weights: ModelWeights? = null,
    val selection: ModelSelection? = null,
    val learned: LearnedWaits? = null,
    /** Recipes may be written ahead of time right now (not with the camera open or the app hidden). */
    val aheadAllowed: Boolean = true,
    val work: RecipeWork = RecipeWork.Idle,
    val cache: List<CachedList> = emptyList()
)
