package com.smatrisciano.aipantry.capture.presentation

import android.graphics.Bitmap
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.ScanProgress
import com.smatrisciano.aipantry.capture.domain.ScanTarget

/** Detection errors: the UI translates them into the device language. */
enum class CaptureError { NO_INGREDIENTS, DETECTION_FAILED }

data class CaptureState(
    val target: ScanTarget = ScanTarget.FRIDGE,
    val isAnalyzing: Boolean = false,
    val engineName: String = "",
    /** Freeze frame shown instead of the preview during the analysis. */
    val capturedPhoto: Bitmap? = null,
    val lastDetections: List<DetectedIngredient> = emptyList(),
    val accumulated: List<DetectedIngredient> = emptyList(),
    /** Where the accumulated ingredients come from (fridge, pantry or both). */
    val accumulatedTargets: Set<ScanTarget> = emptySet(),
    val showResults: Boolean = false,
    val isSaving: Boolean = false,
    /** Error shown with a "Retry" button when the detection fails. */
    val error: CaptureError? = null,
    /**
     * How far the analysis has got, region by region; null for detectors that look
     * at the whole photo at once, which can't tell.
     */
    val scan: ScanProgress? = null
)
