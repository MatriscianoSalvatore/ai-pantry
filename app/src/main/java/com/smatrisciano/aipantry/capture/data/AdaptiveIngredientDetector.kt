package com.smatrisciano.aipantry.capture.data

import android.graphics.Bitmap
import android.util.Log
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import kotlinx.coroutines.CancellationException

/**
 * Sceglie il detector a ogni scansione: Gemini Nano (AICore) dove il device
 * lo supporta, altrimenti lo zero-shot CLIP — che fa anche da rete di
 * sicurezza se Nano fallisce a runtime. La scelta è per-scan e non persistita:
 * il modello Nano può diventare disponibile dopo un download in background.
 */
class AdaptiveIngredientDetector(
    private val nano: NanoIngredientDetector,
    private val fallback: IngredientDetector
) : IngredientDetector {

    @Volatile
    private var lastUsed: IngredientDetector = fallback

    override val engineName: String
        get() = lastUsed.engineName

    override suspend fun detect(bitmap: Bitmap, target: ScanTarget): List<DetectedIngredient> {
        if (nano.isUsable()) {
            lastUsed = nano
            try {
                return nano.detect(bitmap, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Nano detection failed, falling back to ${fallback.engineName}", e)
            }
        }
        lastUsed = fallback
        return fallback.detect(bitmap, target)
    }

    private companion object {
        const val TAG = "AdaptiveDetector"
    }
}
