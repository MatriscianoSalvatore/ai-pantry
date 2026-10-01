package com.smatrisciano.aipantry.capture.presentation

import android.graphics.Bitmap
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.ScanTarget

/** Errori della detection: la UI li traduce nella lingua del device. */
enum class CaptureError { NO_INGREDIENTS, DETECTION_FAILED }

data class CaptureState(
    val target: ScanTarget = ScanTarget.FRIDGE,
    val isAnalyzing: Boolean = false,
    val engineName: String = "",
    /** Fermo-immagine mostrato al posto del preview durante l'analisi. */
    val capturedPhoto: Bitmap? = null,
    val lastDetections: List<DetectedIngredient> = emptyList(),
    val accumulated: List<DetectedIngredient> = emptyList(),
    val showResults: Boolean = false,
    val isSaving: Boolean = false,
    /** Errore da mostrare con un pulsante "Riprova" quando la detection fallisce. */
    val error: CaptureError? = null
)
