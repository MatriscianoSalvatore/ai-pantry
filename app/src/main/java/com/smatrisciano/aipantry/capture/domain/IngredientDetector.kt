package com.smatrisciano.aipantry.capture.domain

import android.graphics.Bitmap

enum class ScanTarget { FRIDGE, PANTRY }

data class DetectedIngredient(
    val name: String,
    val quantity: String,
    val confidence: Float
)

interface IngredientDetector {
    /** Engine name shown in the UI (e.g. "Gemma 4 E2B vision · LiteRT"). */
    val engineName: String

    suspend fun detect(bitmap: Bitmap, target: ScanTarget): List<DetectedIngredient>
}
