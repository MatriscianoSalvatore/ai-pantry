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
import com.smatrisciano.aipantry.core.data.ai.BackendChoice
import com.smatrisciano.aipantry.core.data.ai.LlmCatalog
import com.smatrisciano.aipantry.core.data.ai.ModelChoice
import com.smatrisciano.aipantry.core.data.ai.ModelPreferences
import com.smatrisciano.aipantry.core.data.ai.ModelSource
import com.smatrisciano.aipantry.core.data.ai.ModelStatus
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
            // A model copied in with adb while the page is open shows up on the next reading
            modelRepository.rescanManual()
            emit(Readings(deviceMonitor.snapshot(), modelInfo(), weights(), selection(), learnedWaits()))
            delay(REFRESH_MILLIS)
        }
    }.flowOn(Dispatchers.Default)

    // The choices are read live, not with the readings: a tap must show at once
    private class LiveChoices(
        val scan: ModelChoice,
        val recipes: ModelChoice,
        val verbose: Boolean,
        val gemmaId: String,
        val backends: Map<String, BackendChoice>
    )

    private val liveChoices = combine(
        choices.scan, choices.recipes, choices.verbose, choices.activeModelId, choices.backends
    ) { scan, recipes, verbose, gemmaId, backends ->
        LiveChoices(scan, recipes, verbose, gemmaId, backends)
    }

    val uiState: StateFlow<DiagnosticsState> = combine(
        readings,
        recipeRepository.cache,
        recipeRepository.work,
        backgroundAiWork.isAllowed,
        liveChoices
    ) { readings, cache, work, aheadAllowed, live ->
        DiagnosticsState(
            device = readings.device,
            deviceInfo = deviceMonitor.deviceInfo,
            appVersion = "${BuildConfig.VERSION_NAME} · ${BuildConfig.FLAVOR}",
            model = readings.model,
            weights = readings.weights,
            selection = readings.selection.copy(
                scan = live.scan,
                recipes = live.recipes,
                verbose = live.verbose,
                activeGemmaId = live.gemmaId,
                backend = live.backends[live.gemmaId] ?: BackendChoice.AUTO
            ),
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
            is Interaction.OnGemmaVersion -> modelRepository.selectActive(action.id)
            is Interaction.OnBackendChoice -> {
                choices.setBackend(modelRepository.activeModel().id, action.choice)
                viewModelScope.launch { modelRepository.reloadActive() }
            }
            Interaction.OnStopClick -> recipeRepository.stopGeneration()
            Interaction.OnRetryGpuClick -> engineHolder.resetGpuBroken(modelRepository.activeModel())
            is Interaction.OnRemoveModelClick -> viewModelScope.launch {
                modelRepository.remove(LlmCatalog.byId(action.id))
                nano.isUsable()
            }
            is Interaction.OnRestoreModelClick -> viewModelScope.launch {
                modelRepository.restore(LlmCatalog.byId(action.id))
            }
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
        val active = modelRepository.activeModel()
        return ModelWeights(
            gemma = LlmCatalog.all.map { model ->
                val file = modelRepository.modelFile(model)
                GemmaWeights(
                    id = model.id,
                    name = model.displayName,
                    bytes = file.length().takeIf { file.exists() },
                    cacheBytes = modelRepository.cacheFiles(model).sumOf { it.length() },
                    status = modelRepository.statuses.value[model.id],
                    removedByUser = choices.isRemoved(model.id),
                    active = model.id == active.id,
                    manual = model.source == ModelSource.Manual,
                    installPath = modelRepository.installPath(model)
                )
            },
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
        clipPresent = clip.modelSizeBytes() != null,
        gemmaVersions = LlmCatalog.all.map { model ->
            GemmaVersion(model.id, model.displayName, modelRepository.statuses.value[model.id] == ModelStatus.Ready)
        },
        activeGemmaId = modelRepository.activeModel().id,
        activeGemmaName = modelRepository.activeModel().displayName,
        backend = choices.backendFor(modelRepository.activeModel().id)
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
