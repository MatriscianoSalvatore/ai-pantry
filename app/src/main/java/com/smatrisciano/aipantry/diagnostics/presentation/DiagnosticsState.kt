package com.smatrisciano.aipantry.diagnostics.presentation

import com.smatrisciano.aipantry.core.data.ai.BackendChoice
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

/** One Gemma version: what it weighs, where it stands, and whether it is the one in use. */
data class GemmaWeights(
    val id: String,
    val name: String,
    val bytes: Long?,
    val cacheBytes: Long,
    val status: ModelStatus?,
    val removedByUser: Boolean,
    val active: Boolean,
    /** Copied in with adb: nothing in the app can bring it back. */
    val manual: Boolean,
    /** Where the file goes. */
    val installPath: String
)

/** What each model weighs on the device, and whether it is there. Null bytes: not there, or not known. */
data class ModelWeights(
    val gemma: List<GemmaWeights>,
    val clipBytes: Long?,
    val embeddingBytes: Long?,
    /** Where EmbeddingGemma 2 goes: it is copied in with adb. */
    val embeddingInstallPath: String,
    val nanoBytes: Long?,
    val nanoPresent: Boolean,
    val nanoBaseModel: String?
)

/** A Gemma version that can be picked, and whether it is on the device. */
data class GemmaVersion(val id: String, val name: String, val ready: Boolean)

/** The model chosen for each task, and which models can be chosen right now. */
data class ModelSelection(
    val scan: ModelChoice,
    val recipes: ModelChoice,
    val verbose: Boolean,
    val nanoPresent: Boolean,
    val gemmaReady: Boolean,
    val clipPresent: Boolean,
    val embeddingPresent: Boolean,
    val gemmaVersions: List<GemmaVersion>,
    val activeGemmaId: String,
    val activeGemmaName: String,
    /** Where the Gemma version in use runs. */
    val backend: BackendChoice
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
