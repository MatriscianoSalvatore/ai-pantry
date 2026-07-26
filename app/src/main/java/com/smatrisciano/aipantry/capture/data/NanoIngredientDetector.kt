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
 * Riconoscimento ingredienti con Gemini Nano on-device via AICore (ML Kit GenAI
 * Prompt API). Disponibile solo su device con AICore (Pixel 9+, S25+, ecc.) e
 * mai sull'emulatore: la disponibilità va verificata con [isUsable] prima di
 * chiamare [detect] — altrimenti si usa il detector di fallback.
 */
class NanoIngredientDetector : IngredientDetector {

    override val engineName: String = "Gemini Nano · AICore"

    private val model by lazy { Generation.getClient() }

    private val downloadRequested = AtomicBoolean(false)
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * True solo se il modello è già sul device e pronto. Se è scaricabile, il
     * download parte in background una volta sola: nel frattempo (e su device
     * non supportati) si risponde false e il chiamante usa il fallback.
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
            generateContentRequest(ImagePart(bitmap.downscaled()), TextPart(buildPrompt(target))) {
                temperature = 0.2f
                candidateCount = 1
            }
        )
        val rawOutput = response.candidates.firstOrNull()?.text
            ?: error("Gemini Nano returned no candidates")
        return DetectionJsonParser.parse(rawOutput)
    }

    private fun buildPrompt(target: ScanTarget): String {
        val place = if (target == ScanTarget.FRIDGE) "fridge" else "pantry"
        return """
            This is a photo of the inside of a $place.
            Identify the food ingredients you can see, at most 15.
            Respond with ONLY a JSON array (no markdown, no extra text):
            [{"name": "short ingredient name", "quantity": "approximate quantity like '2 pcs' or '1 carton'"}]
            Only include items you actually see. Use common English ingredient names.
        """.trimIndent()
    }

    // Il costo in token dell'immagine rientra nel limite input (~4k token) di
    // AICore; 768px è il compromesso suggerito dalla documentazione
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
