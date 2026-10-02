package com.smatrisciano.aipantry.capture.data

import android.graphics.Bitmap
import android.util.Log
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanProgress
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import kotlinx.coroutines.CancellationException

/**
 * Picks the detector on every scan: Gemini Nano (AICore) where the device
 * supports it, otherwise CLIP zero-shot, which is also the safety net if Nano
 * fails at runtime. The choice is per scan and not persisted: the Nano model
 * can become available after a background download.
 */
class AdaptiveIngredientDetector(
    private val nano: NanoIngredientDetector,
    private val fallback: IngredientDetector
) : IngredientDetector {

    @Volatile
    private var lastUsed: IngredientDetector = fallback

    override val engineName: String
        get() = lastUsed.engineName

    /** Only the detector the next scan is going to use. */
    override suspend fun warmUp() {
        if (!nano.isUsable()) fallback.warmUp()
    }

    override suspend fun detect(
        bitmap: Bitmap,
        target: ScanTarget,
        onProgress: (ScanProgress) -> Unit
    ): List<DetectedIngredient> {
        if (nano.isUsable()) {
            lastUsed = nano
            try {
                return nano.detect(bitmap, target, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Nano detection failed, falling back to ${fallback.engineName}", e)
            }
        }
        lastUsed = fallback
        return fallback.detect(bitmap, target, onProgress)
    }

    private companion object {
        const val TAG = "AdaptiveDetector"
    }
}
