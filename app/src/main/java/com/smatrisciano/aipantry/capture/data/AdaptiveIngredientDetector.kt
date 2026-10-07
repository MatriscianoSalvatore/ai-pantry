package com.smatrisciano.aipantry.capture.data

import android.graphics.Bitmap
import android.util.Log
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanProgress
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import com.smatrisciano.aipantry.core.data.ai.InferenceStats
import com.smatrisciano.aipantry.core.data.ai.InferenceTask
import com.smatrisciano.aipantry.core.data.ai.LlmEngineHolder
import com.smatrisciano.aipantry.core.data.ai.ModelChoice
import com.smatrisciano.aipantry.core.data.ai.ModelPreferences
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import kotlinx.coroutines.CancellationException

/**
 * Picks the detector on every scan, from the model the user chose for it (see
 * [ModelPreferences]). By default Gemini Nano (AICore) where the device supports it,
 * otherwise CLIP zero-shot. Gemma vision (slow on CPU) is only used when chosen. A chosen model
 * that isn't there, or that fails at runtime, hands the scan to the next one: Nano, then CLIP,
 * whichever are left. The choice is per scan and not persisted: the Nano model can become
 * available after a background download.
 */
class AdaptiveIngredientDetector(
    private val nano: NanoIngredientDetector,
    private val gemma: IngredientDetector,
    private val clip: IngredientDetector,
    private val modelRepository: ModelRepository,
    private val engineHolder: LlmEngineHolder,
    private val choices: ModelPreferences,
    private val stats: InferenceStats
) : IngredientDetector {

    @Volatile
    private var lastUsed: IngredientDetector = clip

    override val engineName: String
        get() = lastUsed.engineName

    /** The detectors this scan can use, in the order they are tried. */
    private suspend fun candidates(): List<IngredientDetector> = buildList {
        val choice = choices.scan.value
        val gemmaReady = modelRepository.readyActiveModel()?.supportsVision == true
        if (choice == ModelChoice.CLIP) add(clip)
        if (choice == ModelChoice.GEMMA && gemmaReady) add(gemma)
        if (nano.isUsable()) add(nano)
        if (clip !in this) add(clip)
    }

    /** Only the detector the next scan is going to use. */
    override suspend fun warmUp() {
        val first = candidates().first()
        makeRoomFor(first)
        first.warmUp()
    }

    /**
     * A scan with another model than Gemma doesn't leave Gemma in memory: a big one (E4B with
     * its caches is some 6 GB) next to the scan has Android close the app. It loads again when
     * the recipes need it.
     */
    private suspend fun makeRoomFor(detector: IngredientDetector) {
        if (detector !== gemma) engineHolder.unloadWhenIdle()
    }

    override suspend fun detect(
        bitmap: Bitmap,
        target: ScanTarget,
        onProgress: (ScanProgress) -> Unit
    ): List<DetectedIngredient> {
        val candidates = candidates()
        var lastError: Exception? = null
        for ((index, detector) in candidates.withIndex()) {
            lastUsed = detector
            makeRoomFor(detector)
            val run = stats.begin(InferenceTask.SCAN, detector.engineName, backendOf(detector))
            try {
                return detector.detect(bitmap, target, onProgress).also { run.items(it.size); run.finish() }
            } catch (e: CancellationException) {
                run.finish(failed = true)
                throw e
            } catch (e: Exception) {
                run.finish(failed = true)
                lastError = e
                val next = candidates.getOrNull(index + 1)
                Log.w(TAG, "${detector.engineName} detection failed" + (next?.let { ", falling back to ${it.engineName}" } ?: ""), e)
            }
        }
        throw lastError ?: IllegalStateException("No detector available")
    }

    private fun backendOf(detector: IngredientDetector): String = when (detector) {
        nano -> "AICore"
        gemma -> if (engineHolder.currentBackendIsGpu()) "GPU" else "CPU"
        else -> "CPU · LiteRT"
    }

    private companion object {
        const val TAG = "AdaptiveDetector"
    }
}
