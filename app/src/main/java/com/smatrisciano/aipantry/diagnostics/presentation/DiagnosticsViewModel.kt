package com.smatrisciano.aipantry.diagnostics.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smatrisciano.aipantry.BuildConfig
import com.smatrisciano.aipantry.capture.data.ClipZeroShotIngredientDetector
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.core.data.WaitTimeEstimator
import com.smatrisciano.aipantry.core.data.WaitTimeEstimator.Measure
import com.smatrisciano.aipantry.core.data.ai.BackgroundAiWork
import com.smatrisciano.aipantry.core.data.ai.GeminiNanoWriter
import com.smatrisciano.aipantry.core.data.ai.LlmEngineHolder
import com.smatrisciano.aipantry.core.data.ai.ModelPreferences
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import com.smatrisciano.aipantry.diagnostics.data.DeviceMonitor
import com.smatrisciano.aipantry.diagnostics.data.DeviceSnapshot
import com.smatrisciano.aipantry.diagnostics.presentation.DiagnosticsActions.Interaction
import com.smatrisciano.aipantry.recipes.domain.RecipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DiagnosticsViewModel(
    private val deviceMonitor: DeviceMonitor,
    private val recipeRepository: RecipeRepository,
    private val modelRepository: ModelRepository,
    private val engineHolder: LlmEngineHolder,
    private val detector: IngredientDetector,
    private val waitTimes: WaitTimeEstimator,
    private val choices: ModelPreferences,
    private val nano: GeminiNanoWriter,
    private val clip: ClipZeroShotIngredientDetector,
    backgroundAiWork: BackgroundAiWork
) : ViewModel() {

    private class Readings(
        val device: DeviceSnapshot,
        val model: ModelInfo,
        val weights: ModelWeights,
        val selection: ModelSelection,
        val learned: LearnedWaits
    )

    // Read again every couple of seconds while the page is on screen: heat and
    // throttling change by the second while the model works
    private val readings = flow {
        while (true) {
            emit(Readings(deviceMonitor.snapshot(), modelInfo(), weights(), selection(), learnedWaits()))
            delay(REFRESH_MILLIS)
        }
    }.flowOn(Dispatchers.Default)

    // The choices are read live, not with the readings: a tap must show at once
    private val liveChoices = combine(choices.scan, choices.recipes, choices.verbose) { scan, recipes, verbose ->
        Triple(scan, recipes, verbose)
    }

    val uiState: StateFlow<DiagnosticsState> = combine(
        readings,
        recipeRepository.cache,
        recipeRepository.work,
        backgroundAiWork.isAllowed,
        liveChoices
    ) { readings, cache, work, aheadAllowed, (scan, recipes, verbose) ->
        DiagnosticsState(
            device = readings.device,
            deviceInfo = deviceMonitor.deviceInfo,
            appVersion = "${BuildConfig.VERSION_NAME} · ${BuildConfig.FLAVOR}",
            model = readings.model,
            weights = readings.weights,
            selection = readings.selection.copy(scan = scan, recipes = recipes, verbose = verbose),
            learned = readings.learned,
            aheadAllowed = aheadAllowed,
            work = work,
            cache = cache
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsState())

    fun onAction(action: Interaction) {
        when (action) {
            Interaction.OnClearRecipeCacheClick -> recipeRepository.clearCache()
            is Interaction.OnScanChoice -> choices.setScan(action.choice)
            is Interaction.OnRecipeChoice -> {
                choices.setRecipes(action.choice)
                // The badge and the recipes' engine follow the choice from the next look at Nano
                viewModelScope.launch { nano.isUsable() }
            }
            is Interaction.OnVerboseChange -> choices.setVerbose(action.on)
            Interaction.OnRemoveGemmaClick -> viewModelScope.launch {
                modelRepository.remove(modelRepository.activeModel())
                nano.isUsable()
            }
            Interaction.OnRestoreGemmaClick -> viewModelScope.launch { modelRepository.restore(modelRepository.activeModel()) }
        }
    }

    private fun modelInfo(): ModelInfo {
        val model = modelRepository.activeModel()
        val file = modelRepository.modelFile(model)
        val caches = modelRepository.cacheFiles(model)
        return ModelInfo(
            name = model.displayName,
            status = modelRepository.statuses.value[model.id],
            fileBytes = file.length().takeIf { file.exists() },
            loaded = engineHolder.isLoaded(),
            gpu = engineHolder.currentBackendIsGpu(),
            cpuThreads = engineHolder.cpuThreads,
            gpuDisabled = engineHolder.isGpuDisabled(model),
            cacheBytes = caches.sumOf { it.length() },
            cacheFileCount = caches.size,
            detectorName = detector.engineName
        )
    }

    private fun weights(): ModelWeights {
        val model = modelRepository.activeModel()
        val file = modelRepository.modelFile(model)
        return ModelWeights(
            gemmaBytes = file.length().takeIf { file.exists() },
            gemmaCacheBytes = modelRepository.cacheFiles(model).sumOf { it.length() },
            gemmaStatus = modelRepository.statuses.value[model.id],
            gemmaRemovedByUser = choices.gemmaRemovedByUser,
            clipBytes = clip.modelSizeBytes(),
            nanoBytes = choices.nanoDownloadBytes.takeIf { it > 0 },
            nanoPresent = nano.present.value,
            nanoBaseModel = nano.baseModelName
        )
    }

    private fun selection() = ModelSelection(
        scan = choices.scan.value,
        recipes = choices.recipes.value,
        verbose = choices.verbose.value,
        nanoPresent = nano.present.value,
        gemmaReady = modelRepository.readyActiveModel() != null,
        clipPresent = clip.modelSizeBytes() != null
    )

    private fun learnedWaits() = LearnedWaits(
        promptReadingMillis = waitTimes.expected(Measure.PROMPT_READING_MILLIS),
        listRecipeChars = waitTimes.expected(Measure.LIST_RECIPE_CHARS),
        detailsChars = waitTimes.expected(Measure.DETAILS_CHARS)
    )

    private companion object {
        // The thermal headroom can be asked at most once a second
        const val REFRESH_MILLIS = 2_000L
    }
}
