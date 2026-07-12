package com.smatrisciano.aipantry.capture.data

import android.graphics.Bitmap
import android.util.Log
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanTarget

/**
 * Strategia a cascata, sempre on-device:
 * 1. LLM multimodale (Gemma 3n vision) se il modello attivo è scaricato — detection completa;
 * 2. classificatore EfficientNet bundlato nell'APK — zero setup;
 * 3. demo mode come ultima rete di sicurezza (risultati dello script Droidcon).
 */
class SmartIngredientDetector(
    private val llmVision: LlmVisionIngredientDetector,
    private val classifier: MediaPipeIngredientDetector,
    private val demo: DemoIngredientDetector
) : IngredientDetector {

    // L'engine che ha prodotto l'ultimo risultato: la UI deve mostrare cosa ha
    // davvero riconosciuto gli ingredienti, non cosa ci ha provato per primo
    @Volatile
    private var lastUsedEngine: String? = null

    override val engineName: String
        get() = lastUsedEngine
            ?: if (llmVision.isAvailable()) llmVision.engineName else classifier.engineName

    override suspend fun detect(
        bitmap: Bitmap,
        target: ScanTarget
    ): List<DetectedIngredient> {
        if (llmVision.isAvailable()) {
            runCatching { llmVision.detect(bitmap, target) }
                .onSuccess {
                    if (it.isNotEmpty()) {
                        lastUsedEngine = llmVision.engineName
                        return it
                    }
                }
                .onFailure { Log.w(TAG, "LLM vision failed, trying classifier", it) }
        }
        runCatching { classifier.detect(bitmap, target) }
            .onSuccess {
                if (it.isNotEmpty()) {
                    lastUsedEngine = classifier.engineName
                    return it
                }
            }
            .onFailure { Log.w(TAG, "Classifier failed, falling back to demo", it) }
        lastUsedEngine = demo.engineName
        return demo.detect(bitmap, target)
    }

    private companion object {
        const val TAG = "SmartIngredientDetector"
    }
}
