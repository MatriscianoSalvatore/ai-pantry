package com.smatrisciano.aipantry.core.data.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * Cache del motore LiteRT: il caricamento del modello costa secondi e GB di RAM,
 * quindi l'istanza viene riusata finché il modello attivo non cambia.
 */
class LlmEngineHolder(private val context: Context) {

    private var engine: LlmInference? = null
    private var enginePath: String? = null

    @Synchronized
    fun acquire(model: LlmModel, file: File): LlmInference {
        val path = file.absolutePath
        if (enginePath != path) {
            engine?.close()
            engine = null
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(MAX_TOKENS)
                .apply { if (model.supportsVision) setMaxNumImages(1) }
                .build()
            engine = LlmInference.createFromOptions(context, options)
            enginePath = path
        }
        return requireNotNull(engine)
    }

    private companion object {
        const val MAX_TOKENS = 4096
    }
}
