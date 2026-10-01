package com.smatrisciano.aipantry.capture.data

import com.smatrisciano.aipantry.capture.domain.ScanTarget
import com.smatrisciano.aipantry.core.domain.AppLanguage

/**
 * Prompt di detection condiviso dai detector generativi (Gemini Nano, Gemma
 * vision). Le chiavi JSON restano in inglese in entrambe le lingue: sono il
 * contratto con [DetectionJsonParser], si traducono solo i nomi degli ingredienti.
 */
object DetectionPrompt {

    // Niente campo confidence nell'output: il parser ha già un default e
    // ogni campo in più sono token di decode che l'utente aspetta
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
