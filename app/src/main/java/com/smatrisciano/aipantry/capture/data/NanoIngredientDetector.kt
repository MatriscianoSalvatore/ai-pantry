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
import com.smatrisciano.aipantry.capture.domain.ScanTarget
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
class NanoIngredientDetector : IngredientDetector {

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
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> true
            FeatureStatus.DOWNLOADABLE -> {
                if (downloadRequested.compareAndSet(false, true)) {
                    downloadScope.launch {
                        runCatching {
                            model.download().collect { status ->
                                if (status is DownloadStatus.DownloadFailed) {
                                    Log.w(TAG, "Nano model download failed")
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

    override suspend fun detect(bitmap: Bitmap, target: ScanTarget): List<DetectedIngredient> {
        val response = model.generateContent(
            generateContentRequest(ImagePart(bitmap.downscaled()), TextPart(DetectionPrompt.build(target))) {
                temperature = 0.2f
                candidateCount = 1
            }
        )
        val rawOutput = response.candidates.firstOrNull()?.text
            ?: error("Gemini Nano returned no candidates")
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
    }
}
