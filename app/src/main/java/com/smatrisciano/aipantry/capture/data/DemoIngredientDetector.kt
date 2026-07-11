package com.smatrisciano.aipantry.capture.data

import android.graphics.Bitmap
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import kotlinx.coroutines.delay

/**
 * Fallback demo: restituisce il set di ingredienti previsto dallo script
 * della demo Droidcon, con un ritardo che simula l'inferenza.
 */
class DemoIngredientDetector : IngredientDetector {

    override val engineName = "Demo vision model"

    override suspend fun detect(
        bitmap: Bitmap,
        target: ScanTarget
    ): List<DetectedIngredient> {
        delay(INFERENCE_DELAY_MS)
        return when (target) {
            ScanTarget.FRIDGE -> listOf(
                DetectedIngredient("Mozzarella", "1 pack", 0.94f),
                DetectedIngredient("Milk", "1 carton", 0.91f),
                DetectedIngredient("Tomatoes", "2 pcs", 0.89f),
                DetectedIngredient("Basil", "1 bunch", 0.82f)
            )
            ScanTarget.PANTRY -> listOf(
                DetectedIngredient("Pasta", "500 g", 0.96f),
                DetectedIngredient("Canned tuna", "2 cans", 0.90f),
                DetectedIngredient("Onions", "2 pcs", 0.87f),
                DetectedIngredient("Parmigiano", "1 wedge", 0.85f)
            )
        }
    }

    private companion object {
        const val INFERENCE_DELAY_MS = 1400L
    }
}
