package com.smatrisciano.aipantry.core.data.ai

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Cache del motore LiteRT-LM: il caricamento del modello costa secondi e GB di RAM,
 * quindi l'istanza viene riusata finché il modello attivo non cambia.
 *
 * Backend: GPU-first, ma su alcuni device l'accelerator GPU di LiteRT-LM non si
 * carica affatto e l'inferenza si pianta (chiamata nativa non interrompibile).
 * Quando succede, il backend viene marcato rotto in modo **persistente** per quel
 * modello: dai lanci successivi si va dritti su CPU, senza più sprecare tempo ad
 * appendersi sulla GPU morta.
 */
class LlmEngineHolder(private val context: Context) {

    private val prefs = context.getSharedPreferences("llm_engine", Context.MODE_PRIVATE)

    private var engine: Engine? = null
    private var enginePath: String? = null
    private var engineIsGpu: Boolean = false

    @Synchronized
    fun acquire(model: LlmModel, file: File): Engine {
        val path = file.absolutePath
        val wantGpu = !isGpuBroken(model)
        if (enginePath != path || engineIsGpu != wantGpu) {
            engine?.close()
            engine = null
            engineIsGpu = false
            engine = createEngine(path, model, gpu = wantGpu)?.also { engineIsGpu = wantGpu }
                ?: run {
                    // L'init GPU è fallita subito (non un hang): a differenza del
                    // watchdog, senza persistere qui ogni acquire() futura
                    // ritenterebbe e fallirebbe di nuovo la stessa GPU, pagando
                    // qualche secondo a vuoto ogni volta
                    if (wantGpu) persistGpuBroken(model)
                    createEngine(path, model, gpu = false)?.also { engineIsGpu = false }
                }
                ?: error("Cannot initialize LLM engine for ${model.displayName}")
            enginePath = path
            Log.i(TAG, "LLM engine ready, gpu=$engineIsGpu")
        }
        return requireNotNull(engine)
    }

    fun currentBackendIsGpu(): Boolean = engineIsGpu

    /** Conversazione usa-e-getta sull'engine attivo, coi parametri di sampling richiesti. */
    fun createConversation(
        model: LlmModel,
        file: File,
        temperature: Double,
        topK: Int,
        topP: Double = 1.0,
        seed: Int = 0
    ): Conversation {
        val engine = acquire(model, file)
        return engine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = topK, topP = topP, temperature = temperature, seed = seed)
            )
        )
    }

    /**
     * All'avvio: carica l'engine e, se è finito su GPU, verifica con una micro
     * inferenza che la GPU funzioni davvero (su alcuni device l'accelerator non
     * si carica e l'inferenza si pianta). Se la sonda va in timeout, marca la GPU
     * rotta e ricarica su CPU — così la prima generazione dell'utente non paga
     * l'attesa. Job scollegato: il thread nativo eventualmente piantato non
     * blocca il warm-up.
     */
    suspend fun warmUp(model: LlmModel, file: File) {
        acquire(model, file)
        if (!currentBackendIsGpu()) return

        val probe = probeScope.async {
            // Prompt rappresentativo (genera qualche frase con i parametri reali):
            // una micro-generazione banale passerebbe anche su GPU rotta
            createConversation(model, file, temperature = 0.4, topK = 40).use { conversation ->
                conversation.sendMessage(Contents.of(Content.Text("List five common fruits, one per line.")))
            }
        }
        // La GPU rotta può manifestarsi in due modi: hang (timeout) oppure
        // eccezione immediata alla prima inferenza (es. OpenCL assente
        // sull'emulatore, dove l'init dell'engine invece riesce)
        val ok = try {
            withTimeoutOrNull(GPU_PROBE_TIMEOUT_MS) { probe.await() } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "GPU probe threw", e)
            false
        }
        if (!ok) {
            probe.cancel()
            Log.w(TAG, "GPU probe failed or timed out — marking GPU unusable and reloading on CPU")
            reportGpuUnusable(model)
            acquire(model, file)
        } else {
            Log.i(TAG, "GPU probe OK — using GPU")
        }
    }

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Backend.CPU() di default gira su 1 thread solo: su un 2.6GB multimodale
    // significa lasciare inutilizzati quasi tutti i core. Usare TUTTI i core
    // però fa scaldare e throttlare i chip mobile sotto carico sostenuto (min
    // di calcolo), rallentando di nuovo verso la fine — 4 thread è un
    // compromesso ragionevole tra parallelismo e calore. Sull'emulatore il
    // throttling termico non esiste: si usano tutti i core disponibili meno uno.
    private val cpuThreadCount: Int =
        (Runtime.getRuntime().availableProcessors() - 1)
            .coerceIn(1, if (isEmulator) Int.MAX_VALUE else 4)

    /**
     * La GPU ha prodotto output corrotto o si è piantata: marca il backend rotto
     * (persistente) e ricarica su CPU. Ritorna false se anche la CPU fallisce —
     * a quel punto non c'è rimedio.
     */
    @Synchronized
    fun reportGpuUnusable(model: LlmModel): Boolean {
        if (!engineIsGpu) {
            Log.e(TAG, "Output unusable on CPU backend too — giving up")
            return false
        }
        Log.w(TAG, "GPU unusable for ${model.id}: switching to CPU (persisted)")
        persistGpuBroken(model)
        engine?.close()
        engine = null
        enginePath = null
        engineIsGpu = false
        return true
    }

    private fun isGpuBroken(model: LlmModel): Boolean =
        prefs.getBoolean(gpuBrokenKey(model), false)

    private fun persistGpuBroken(model: LlmModel) {
        prefs.edit { putBoolean(gpuBrokenKey(model), true) }
    }

    private fun gpuBrokenKey(model: LlmModel) = "gpu_broken_${model.id}"

    private fun createEngine(path: String, model: LlmModel, gpu: Boolean): Engine? = runCatching {
        val backend = if (gpu) Backend.GPU() else Backend.CPU(threadCount = cpuThreadCount)
        val config = EngineConfig(
            modelPath = path,
            backend = backend,
            visionBackend = if (model.supportsVision) backend else null
        )
        Engine(config).apply { initialize() }
    }.onFailure {
        Log.w(TAG, "LLM engine init failed (gpu=$gpu)", it)
    }.getOrNull()

    private companion object {
        const val TAG = "LlmEngineHolder"

        val isEmulator: Boolean =
            android.os.Build.HARDWARE in setOf("ranchu", "goldfish") ||
                android.os.Build.FINGERPRINT.contains("emulator") ||
                android.os.Build.FINGERPRINT.contains("generic")

        // La sonda GPU all'avvio: se la micro inferenza non risponde in tempo,
        // la GPU è inutilizzabile su questo device
        const val GPU_PROBE_TIMEOUT_MS = 120_000L
    }
}

/** LiteRT-LM non espone testo diretto su [Message]: va estratto dalle sue [Content.Text]. */
internal fun Message.text(): String =
    contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
