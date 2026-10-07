package com.smatrisciano.aipantry.diagnostics.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smatrisciano.aipantry.BuildConfig
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.core.data.WaitTimeEstimator
import com.smatrisciano.aipantry.core.data.WaitTimeEstimator.Measure
import com.smatrisciano.aipantry.core.data.ai.BackgroundAiWork
import com.smatrisciano.aipantry.core.data.ai.GeminiNanoWriter
import com.smatrisciano.aipantry.core.data.ai.LlmEngineHolder
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import com.smatrisciano.aipantry.core.data.ai.NanoState
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

class DiagnosticsViewModel(
    private val deviceMonitor: DeviceMonitor,
    private val recipeRepository: RecipeRepository,
    private val modelRepository: ModelRepository,
    private val engineHolder: LlmEngineHolder,
    private val detector: IngredientDetector,
    private val waitTimes: WaitTimeEstimator,
    private val nano: GeminiNanoWriter,
    backgroundAiWork: BackgroundAiWork
) : ViewModel() {

    private class Readings(val device: DeviceSnapshot, val model: ModelInfo, val learned: LearnedWaits)

    // Read again every couple of seconds while the page is on screen: heat and
    // throttling change by the second while the model works
    private val readings = flow {
        while (true) {
            emit(Readings(deviceMonitor.snapshot(), modelInfo(), learnedWaits()))
            delay(REFRESH_MILLIS)
        }
    }.flowOn(Dispatchers.Default)

    // Asked apart from the readings, which don't wait for AICore to answer
    private val nanoState = flow<NanoState?> {
        emit(null)
        while (true) {
            emit(nano.state())
            delay(NANO_REFRESH_MILLIS)
        }
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<DiagnosticsState> = combine(
        readings,
        recipeRepository.cache,
        recipeRepository.work,
        backgroundAiWork.isAllowed,
        nanoState
    ) { readings, cache, work, aheadAllowed, nano ->
        DiagnosticsState(
            device = readings.device,
            deviceInfo = deviceMonitor.deviceInfo,
            appVersion = "${BuildConfig.VERSION_NAME} · ${BuildConfig.FLAVOR}",
            model = readings.model,
            nano = nano,
            learned = readings.learned,
            aheadAllowed = aheadAllowed,
            work = work,
            cache = cache
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsState())

    fun onAction(action: Interaction) {
        when (action) {
            Interaction.OnClearRecipeCacheClick -> recipeRepository.clearCache()
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

    private fun learnedWaits() = LearnedWaits(
        promptReadingMillis = waitTimes.expected(Measure.PROMPT_READING_MILLIS),
        listRecipeChars = waitTimes.expected(Measure.LIST_RECIPE_CHARS),
        detailsChars = waitTimes.expected(Measure.DETAILS_CHARS)
    )

    private companion object {
        // The thermal headroom can be asked at most once a second
        const val REFRESH_MILLIS = 2_000L

        // Gemini Nano changes only while AICore downloads it
        const val NANO_REFRESH_MILLIS = 5_000L
    }
}
