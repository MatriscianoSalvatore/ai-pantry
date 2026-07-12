package com.smatrisciano.aipantry.core.data.ai

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Cache del motore LiteRT: il caricamento del modello costa secondi e GB di RAM,
 * quindi l'istanza viene riusata finché il modello attivo non cambia.
 *
 * Backend: GPU-first, ma su alcuni device l'accelerator GPU di LiteRT non si
 * carica affatto e `generateResponse` si pianta (chiamata nativa non
 * interrompibile). Quando succede, il backend viene marcato rotto in modo
 * **persistente** per quel modello: dai lanci successivi si va dritti su CPU,
 * senza più sprecare tempo ad appendersi sulla GPU morta.
 */
class LlmEngineHolder(private val context: Context) {

    private val prefs = context.getSharedPreferences("llm_engine", Context.MODE_PRIVATE)

    private var engine: LlmInference? = null
    private var enginePath: String? = null
    private var engineBackend: LlmInference.Backend? = null

    @Synchronized
    fun acquire(model: LlmModel, file: File): LlmInference {
        val path = file.absolutePath
        val wantedBackend = if (isGpuBroken(model)) {
            LlmInference.Backend.CPU
        } else {
            LlmInference.Backend.GPU
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

    fun currentBackendIsGpu(): Boolean = engineBackend == LlmInference.Backend.GPU

    /**
     * All'avvio: carica l'engine e, se è finito su GPU, verifica con una micro
     * inferenza che la GPU funzioni davvero (su alcuni device l'accelerator non
     * si carica e `generateResponse` si pianta). Se la sonda va in timeout, marca
     * la GPU rotta e ricarica su CPU — così la prima generazione dell'utente non
     * paga l'attesa. Job scollegato: il thread nativo eventualmente piantato non
     * blocca il warm-up.
     */
    suspend fun warmUp(model: LlmModel, file: File) {
        acquire(model, file)
        if (!currentBackendIsGpu()) return

        val engine = requireNotNull(engine)
        val probe = probeScope.async {
            // Prompt rappresentativo (genera qualche frase con i parametri reali):
            // una micro-generazione banale passerebbe anche su GPU rotta
            LlmInferenceSession.createFromOptions(
                engine,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTemperature(0.4f).setTopK(40).build()
            ).use { session ->
                session.addQueryChunk("List five common fruits, one per line.")
                session.generateResponse()
            }
        }
        val ok = withTimeoutOrNull(GPU_PROBE_TIMEOUT_MS) { probe.await() } != null
        if (!ok) {
            probe.cancel()
            Log.w(TAG, "GPU probe timed out — marking GPU unusable and reloading on CPU")
            reportGpuUnusable(model)
            acquire(model, file)
        } else {
            Log.i(TAG, "GPU probe OK — using GPU")
        }
    }

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * La GPU ha prodotto output corrotto o si è piantata: marca il backend rotto
     * (persistente) e ricarica su CPU. Ritorna false se anche la CPU fallisce —
     * a quel punto non c'è rimedio.
     */
    @Synchronized
    fun reportGpuUnusable(model: LlmModel): Boolean {
        if (engineBackend == LlmInference.Backend.CPU) {
            Log.e(TAG, "Output unusable on CPU backend too — giving up")
            return false
        }
        Log.w(TAG, "GPU unusable for ${model.id}: switching to CPU (persisted)")
        prefs.edit { putBoolean(gpuBrokenKey(model), true) }
        engine?.close()
        engine = null
        enginePath = null
        engineBackend = null
        return true
    }

    private fun isGpuBroken(model: LlmModel): Boolean =
        prefs.getBoolean(gpuBrokenKey(model), false)

    private fun gpuBrokenKey(model: LlmModel) = "gpu_broken_${model.id}"

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

        // Copre prompt + risposta di lista ricette e istruzioni (poche centinaia
        // di token ciascuna) e combacia con la KV cache del modello (ekv2048)
        const val MAX_TOKENS = 2048

        // La sonda GPU all'avvio: se la micro inferenza non risponde in tempo,
        // la GPU è inutilizzabile su questo device
        const val GPU_PROBE_TIMEOUT_MS = 30_000L
    }
}
