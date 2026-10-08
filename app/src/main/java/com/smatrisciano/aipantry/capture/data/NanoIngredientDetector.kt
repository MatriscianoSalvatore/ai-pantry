package com.smatrisciano.aipantry.capture.data

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanProgress
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import com.smatrisciano.aipantry.core.data.ai.ModelPreferences
import com.smatrisciano.aipantry.core.data.ai.featureStatusName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ingredient recognition with Gemini Nano on-device via AICore (ML Kit GenAI
 * Prompt API). Available only on devices with AICore and Prompt API support
 * (e.g. Pixel 9 and later) and never on the emulator: check availability with
 * [isUsable] before calling [detect], otherwise the fallback detector is used.
 */
class NanoIngredientDetector(private val choices: ModelPreferences) : IngredientDetector {

    override val engineName: String = "Gemini Nano · AICore"

    private val model by lazy { Generation.getClient() }

    private val downloadRequested = AtomicBoolean(false)
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * True only if the model is already on the device and ready. If it can be
     * downloaded, the download starts in the background, once: meanwhile (and on
     * unsupported devices) this returns false and the caller uses the fallback.
     */
    suspend fun isUsable(): Boolean = runCatching {
        val status = model.checkStatus()
        Log.i(TAG, "AICore feature status: ${status.featureStatusName()}")
        when (status) {
            FeatureStatus.AVAILABLE -> true
            FeatureStatus.DOWNLOADABLE -> {
                if (downloadRequested.compareAndSet(false, true)) {
                    downloadScope.launch {
                        runCatching {
                            model.download().collect { status ->
                                when (status) {
                                    is DownloadStatus.DownloadStarted -> {
                                        // The only time AICore says what the model weighs
                                        choices.nanoDownloadBytes = status.bytesToDownload
                                        Log.i(TAG, "Nano model download: $status")
                                    }
                                    is DownloadStatus.DownloadFailed -> Log.w(TAG, "Nano model download failed")
                                    is DownloadStatus.DownloadCompleted -> Log.i(TAG, "Nano model download completed")
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
                false
            }
            else -> false
        }
    }.getOrElse {
        Log.w(TAG, "AICore status check failed", it)
        false
    }

    // One request for the whole photo: there is no progress to report along the way
    override suspend fun detect(
        bitmap: Bitmap,
        target: ScanTarget,
        onProgress: (ScanProgress) -> Unit
    ): List<DetectedIngredient> {
        val response = model.generateContent(
            generateContentRequest(ImagePart(bitmap.downscaled()), TextPart(DetectionPrompt.build(target))) {
                temperature = 0.2f
                candidateCount = 1
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }
        )
        val candidate = response.candidates.firstOrNull()
            ?: error("Gemini Nano returned no candidates")
        val rawOutput = candidate.text
        Log.i(TAG, "Nano output (${candidate.finishReason}, ${rawOutput.length} chars): ${rawOutput.take(600)}")
        return DetectionJsonParser.parse(rawOutput)
    }

    // The image's token cost fits within AICore's input limit (~4k tokens);
    // 768 px is the trade-off suggested by the documentation
    private fun Bitmap.downscaled(maxSide: Int = 768): Bitmap {
        val largest = maxOf(width, height)
        if (largest <= maxSide) return this
        val scale = maxSide.toFloat() / largest
        return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }

    private companion object {
        const val TAG = "NanoDetector"

        // The most the Prompt API accepts (1..256 as of genai-prompt 1.0.0-beta2): also its
        // default, which cut a pretty-printed list short. The prompt asks for a compact one-line
        // array to fit as many items as possible, and the parser keeps those complete
        const val MAX_OUTPUT_TOKENS = 256
    }
}
