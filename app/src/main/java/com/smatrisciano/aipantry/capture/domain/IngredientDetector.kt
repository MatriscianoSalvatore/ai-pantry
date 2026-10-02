package com.smatrisciano.aipantry.capture.domain

import android.graphics.Bitmap

enum class ScanTarget { FRIDGE, PANTRY }

data class DetectedIngredient(
    val name: String,
    val quantity: String,
    val confidence: Float
)

/** A region of the photo, in fractions of its width and height (0..1). */
data class PhotoRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * How far the scan of a photo has got, for detectors that look at it one region
 * at a time: how many regions are done, which ones are being looked at right now
 * and what has turned up so far.
 */
data class ScanProgress(
    val totalRegions: Int,
    val doneRegions: Int,
    val activeRegions: List<PhotoRegion>,
    val found: List<DetectedIngredient>
)

interface IngredientDetector {
    /** Engine name shown in the UI (e.g. "Gemma 4 E2B vision · LiteRT"). */
    val engineName: String

    /** Gets the model ready ahead of the first photo; detectors with nothing to prepare do nothing. */
    suspend fun warmUp() {}

    /**
     * Detects the ingredients in [bitmap]. [onProgress] follows the scan as it goes,
     * for the detectors that can tell; the others never call it.
     */
    suspend fun detect(
        bitmap: Bitmap,
        target: ScanTarget,
        onProgress: (ScanProgress) -> Unit = {}
    ): List<DetectedIngredient>
}
