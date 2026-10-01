package com.smatrisciano.aipantry.capture.data

import com.smatrisciano.aipantry.capture.domain.ScanTarget
import com.smatrisciano.aipantry.core.domain.AppLanguage

/**
 * Detection prompt shared by the generative detectors (Gemini Nano, Gemma
 * vision). JSON keys stay in English in both languages: they are the contract
 * with [DetectionJsonParser], only the ingredient names are translated.
 */
object DetectionPrompt {

    // No confidence field in the output: the parser already has a default, and
    // every extra field means decode tokens the user waits for
    fun build(target: ScanTarget, language: AppLanguage = AppLanguage.current()): String =
        when (language) {
            AppLanguage.EN -> {
                val place = if (target == ScanTarget.FRIDGE) "fridge" else "pantry"
                """
                    This is a photo of the inside of a $place.
                    Identify the food ingredients you can see, at most 15.
                    Respond with ONLY a JSON array (no markdown, no extra text):
                    [{"name": "short ingredient name", "quantity": "approximate quantity like '2 pcs' or '1 carton'"}]
                    Only include items you actually see. Use common English ingredient names.
                """
            }
            AppLanguage.IT -> {
                val place = if (target == ScanTarget.FRIDGE) "un frigorifero" else "una dispensa"
                """
                    Questa è una foto dell'interno di $place.
                    Individua gli ingredienti alimentari che vedi, al massimo 15.
                    Rispondi SOLO con un array JSON (niente markdown, niente testo extra):
                    [{"name": "nome breve dell'ingrediente", "quantity": "quantità approssimativa, ad esempio '2 pz' o '1 confezione'"}]
                    Includi solo ciò che vedi davvero. Usa nomi di ingredienti comuni in italiano.
                """
            }
        }.trimIndent()
}
