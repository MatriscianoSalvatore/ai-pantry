package com.smatrisciano.aipantry.capture.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanTarget

/**
 * Usa il modello reale se presente sul device, altrimenti degrada al demo mode.
 * Se l'inferenza reale fallisce o non riconosce nulla, la demo resta comunque in piedi.
 */
class SmartIngredientDetector(
    private val context: Context,
    private val mediaPipe: MediaPipeIngredientDetector,
    private val demo: DemoIngredientDetector
) : IngredientDetector {

    private val useRealModel: Boolean
        get() = MediaPipeIngredientDetector.isModelAvailable(context)

    override val engineName: String
        get() = if (useRealModel) mediaPipe.engineName else demo.engineName

    override suspend fun detect(
        bitmap: Bitmap,
        target: ScanTarget
    ): List<DetectedIngredient> {
        if (useRealModel) {
            runCatching { mediaPipe.detect(bitmap, target) }
                .onSuccess { if (it.isNotEmpty()) return it }
                .onFailure { Log.w(TAG, "MediaPipe inference failed, falling back to demo", it) }
        }
        return demo.detect(bitmap, target)
    }

    private companion object {
        const val TAG = "SmartIngredientDetector"
    }
}
