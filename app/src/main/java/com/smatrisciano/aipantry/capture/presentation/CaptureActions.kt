package com.smatrisciano.aipantry.capture.presentation

import android.graphics.Bitmap
import com.smatrisciano.aipantry.capture.domain.ScanTarget

sealed interface CaptureActions {

    sealed interface Interaction : CaptureActions {
        data class OnTargetSelected(val target: ScanTarget) : Interaction
        data class OnPhotoCaptured(val bitmap: Bitmap) : Interaction
        data object OnScanAnotherClick : Interaction
        data object OnAddToPantryClick : Interaction
        data class OnDetectionRemoved(val name: String) : Interaction
        data object OnRetryClick : Interaction

        /** Swipe-down on the results sheet: hides it without touching anything else. */
        data object OnResultsDismissed : Interaction

        /** Reopens the results sheet (the "N ingredients in this scan session" pill). */
        data object OnShowResultsClick : Interaction
    }

    sealed interface Navigation : CaptureActions {
        data object GoBack : Navigation
    }
}
