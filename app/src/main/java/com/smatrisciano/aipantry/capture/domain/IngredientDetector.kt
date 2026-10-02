package com.smatrisciano.aipantry.capture.domain

import android.graphics.Bitmap

enum class ScanTarget { FRIDGE, PANTRY }

data class DetectedIngredient(
    val name: String,
    val quantity: String,
    val confidence: Float
)

/**
 * The regions of one pass over the photo, laid out as a grid: each region is a cell,
 * [rows] by [columns] across the whole photo.
 */
data class ScanGrid(val rows: Int, val columns: Int) {
    val cells: Int get() = rows * columns
}

/** A region of the photo: the cell it stands for in the grid of its [pass] (0 is the first). */
data class ScanCell(val pass: Int, val row: Int, val column: Int)

/**
 * How far the scan of a photo has got, for detectors that look at it one region
 * at a time, in passes from coarse to fine: the grid of each pass, the regions done
 * and the ones being looked at right now, and what has turned up so far.
 */
data class ScanProgress(
    val grids: List<ScanGrid>,
    val totalRegions: Int,
    val doneRegions: Int,
    val done: Set<ScanCell>,
    val active: List<ScanCell>,
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
