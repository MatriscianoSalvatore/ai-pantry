package com.smatrisciano.aipantry.core.data.ai

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * Cache del motore LiteRT: il caricamento del modello costa secondi e GB di RAM,
 * quindi l'istanza viene riusata finché il modello attivo non cambia.
 *
 * Backend: **GPU-first, sempre**. Su alcuni driver la GPU degrada dopo un po'
 * (token spazzatura) ma un engine fresco torna a funzionare: alla prima
 * corruzione si ricrea su GPU, alla seconda si ripiega su CPU **solo per questo
 * processo** — al prossimo avvio dell'app si riparte da GPU (auto-guarigione).
 */
class LlmEngineHolder(private val context: Context) {

    private var engine: LlmInference? = null
    private var enginePath: String? = null
    private var engineBackend: LlmInference.Backend? = null

    /** Corruzioni GPU osservate in questo processo, per modello. */
    private val gpuFailures = mutableMapOf<String, Int>()

    init {
        // Migrazione: via il vecchio flag persistente "GPU rotta per sempre"
        context.getSharedPreferences("llm_engine", Context.MODE_PRIVATE)
            .edit { clear() }
    }

    @Synchronized
    fun acquire(model: LlmModel, file: File): LlmInference {
        val path = file.absolutePath
        val wantedBackend = if ((gpuFailures[model.id] ?: 0) < MAX_GPU_FAILURES) {
            LlmInference.Backend.GPU
        } else {
            LlmInference.Backend.CPU
        }
        if (enginePath != path || engineBackend != wantedBackend) {
            engine?.close()
            engine = null
            engineBackend = null
            engine = createEngine(path, model, wantedBackend)?.also { engineBackend = wantedBackend }
                ?: createEngine(path, model, LlmInference.Backend.CPU)
                    ?.also { engineBackend = LlmInference.Backend.CPU }
                        ?: error("Cannot initialize LLM engine for ${model.displayName}")
            enginePath = path
            Log.i(TAG, "LLM engine ready on backend $engineBackend")
        }
        return requireNotNull(engine)
    }

    /**
     * Il backend attivo ha prodotto output corrotto. L'engine viene buttato:
     * il prossimo [acquire] ne crea uno fresco (GPU finché le corruzioni sono
     * sotto soglia, poi CPU per il resto del processo). Ritorna false solo se
     * anche la CPU corrompe — a quel punto non c'è rimedio da ritentare.
     */
    @Synchronized
    fun reportCorruptedOutput(model: LlmModel): Boolean {
        if (engineBackend == LlmInference.Backend.CPU) {
            Log.e(TAG, "Corrupted output on CPU backend too — giving up")
            return false
        }
        val failures = (gpuFailures[model.id] ?: 0) + 1
        gpuFailures[model.id] = failures
        Log.w(
            TAG,
            "GPU failure #$failures for ${model.id}: " +
                if (failures < MAX_GPU_FAILURES) "retrying with a fresh GPU engine" else "falling back to CPU for this session"
        )
        engine?.close()
        engine = null
        enginePath = null
        engineBackend = null
        return true
    }

    private fun createEngine(
        path: String,
        model: LlmModel,
        backend: LlmInference.Backend
    ): LlmInference? = runCatching {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(MAX_TOKENS)
            .setPreferredBackend(backend)
            .apply { if (model.supportsVision) setMaxNumImages(1) }
            .build()
        LlmInference.createFromOptions(context, options)
    }.onFailure {
        Log.w(TAG, "LLM engine init failed on backend $backend", it)
    }.getOrNull()

    private companion object {
        const val TAG = "LlmEngineHolder"

        // Alla seconda corruzione GPU nello stesso processo si passa a CPU
        const val MAX_GPU_FAILURES = 2

        // Copre prompt + risposta di lista ricette e istruzioni (poche centinaia
        // di token ciascuna) e combacia con la KV cache del modello (ekv2048)
        const val MAX_TOKENS = 2048
    }
}
