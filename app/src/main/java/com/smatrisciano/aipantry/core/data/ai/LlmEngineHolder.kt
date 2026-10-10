package com.smatrisciano.aipantry.core.data.ai

import android.content.Context
import android.os.SystemClock
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
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * LiteRT-LM engine cache: loading the model costs seconds and GBs of RAM, so
 * the instance is reused until the active model changes.
 *
 * Backend: GPU first, but on some devices the LiteRT-LM GPU accelerator doesn't
 * load at all and inference hangs (non-interruptible native call). When that
 * happens the backend is marked broken **persistently** for that model: later
 * launches go straight to CPU, without wasting time hanging on the dead GPU.
 */
class LlmEngineHolder(private val context: Context, private val choices: ModelPreferences) {

    private val prefs = context.getSharedPreferences("llm_engine", Context.MODE_PRIVATE)

    private var engine: Engine? = null
    private var enginePath: String? = null
    private var engineIsGpu: Boolean = false
    private var engineModelId: String? = null

    // Models whose GPU failed this session, where the GPU was picked by hand (not written off)
    private val sessionGpuFailed = mutableSetOf<String>()

    // Answers being written right now: the engine isn't taken out of memory under them
    private val inFlight = AtomicInteger(0)

    @Synchronized
    fun acquire(model: LlmModel, file: File): Engine {
        val path = file.absolutePath
        val backend = choices.backendFor(model.id)
        val wantGpu = when (backend) {
            BackendChoice.CPU -> false
            BackendChoice.GPU -> model.id !in sessionGpuFailed
            BackendChoice.AUTO -> !isGpuBroken(model)
        }
        if (enginePath != path || engineIsGpu != wantGpu) {
            engine?.close()
            engine = null
            engineIsGpu = false
            engine = createEngine(path, model, gpu = wantGpu)?.also { engineIsGpu = wantGpu }
                ?: run {
                    // GPU init failed right away (not a hang): unlike the watchdog, without
                    // persisting it here every future acquire() would retry and fail on the
                    // same GPU again, wasting a few seconds each time
                    // By hand, a GPU that fails is only given up on until the next launch
                    if (wantGpu) {
                        if (backend == BackendChoice.AUTO) persistGpuBroken(model) else sessionGpuFailed.add(model.id)
                    }
                    createEngine(path, model, gpu = false)?.also { engineIsGpu = false }
                }
                ?: error("Cannot initialize LLM engine for ${model.displayName}")
            enginePath = path
            engineModelId = model.id
            Log.i(TAG, "LLM engine ready for ${model.id}, gpu=$engineIsGpu")
        }
        return requireNotNull(engine)
    }

    fun currentBackendIsGpu(): Boolean = engineIsGpu

    /** Takes the model out of memory (its file is about to go): the next [acquire] loads it again. */
    @Synchronized
    fun unload() {
        engine?.close()
        engine = null
        enginePath = null
        engineIsGpu = false
        engineModelId = null
    }

    /**
     * Takes the model out of memory to leave it to something else (a scan with another model),
     * unless an answer is being written with it. The next [acquire] loads it again. True when
     * nothing of it is in memory any more.
     */
    @Synchronized
    fun unloadIfIdle(): Boolean {
        if (engine == null) return true
        if (inFlight.get() > 0) return false
        Log.i(TAG, "Taking ${engineModelId ?: "the model"} out of memory: something else needs it")
        unload()
        return true
    }

    /**
     * Like [unloadIfIdle], but waits up to [timeoutMillis] for an answer being written to let go
     * of the engine: when the camera opens, the recipes written ahead are cancelled, and the
     * engine only frees itself a moment later. The GPU probe isn't waited for: it is stopped.
     * Never on the main thread: closing the engine on the GPU takes up to a second, and a load
     * still under way is waited for first, the camera freezing all the while.
     */
    suspend fun unloadWhenIdle(timeoutMillis: Long = 5_000): Boolean = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (true) {
            probeJob?.cancel()
            if (unloadIfIdle()) break
            if (SystemClock.elapsedRealtime() >= deadline) {
                Log.w(TAG, "Not taking the model out of memory: an answer is still being written with it")
                return@withContext false
            }
            delay(100)
        }
        true
    }

    /**
     * Forgets that the GPU was given up on for [model], and takes it out of memory if it is the
     * one loaded: the next load tries the GPU again. For the times it was blamed wrongly.
     */
    /** The GPU picked by hand is tried again: it failed earlier in this session. */
    @Synchronized
    fun clearSessionFailure(model: LlmModel) {
        sessionGpuFailed.remove(model.id)
    }

    @Synchronized
    fun resetGpuBroken(model: LlmModel) {
        prefs.edit { remove(gpuBrokenKey(model)) }
        if (engineModelId == model.id) unload()
    }

    /** The model is in memory, ready to answer. */
    fun isLoaded(): Boolean = engine != null

    /** Threads the CPU backend runs on. */
    val cpuThreads: Int get() = cpuThreadCount

    /** The GPU failed with [model] on this device: from then on the CPU runs it. */
    fun isGpuDisabled(model: LlmModel): Boolean = isGpuBroken(model)

    /** Throwaway conversation on the active engine, with the requested sampling parameters. */
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
     * The model's answer to [prompt], a piece at a time as it is written, in a
     * throwaway conversation with the requested sampling parameters. Stopping the
     * collection stops the native generation too, and the flow only completes once
     * the engine has let go of it: the next conversation never overlaps this one.
     * With [stallTimeoutMillis], an answer that goes that long without a new piece
     * fails with [GenerationStalledException] (a hung GPU never writes anything).
     */
    fun streamAnswer(
        model: LlmModel,
        file: File,
        prompt: String,
        temperature: Double,
        topK: Int,
        topP: Double,
        seed: Int,
        stallTimeoutMillis: Long? = null
    ): Flow<String> = countedAnswer(prompt, stallTimeoutMillis) {
        createConversation(model, file, temperature, topK, topP, seed)
    }

    /** [answerFlow], counted among the answers being written: the engine isn't taken out of memory under it. */
    private fun countedAnswer(prompt: String, stallTimeoutMillis: Long?, open: () -> Conversation): Flow<String> =
        answerFlow(prompt, stallTimeoutMillis, open)
            .onStart { inFlight.incrementAndGet() }
            .onCompletion { inFlight.decrementAndGet() }

    /** The answer to [prompt] in the conversation [open] starts. */
    private fun answerFlow(prompt: String, stallTimeoutMillis: Long?, open: () -> Conversation): Flow<String> = flow {
        val conversation = open()
        val pieces = Channel<String>(Channel.UNLIMITED)
        val finished = CompletableDeferred<Unit>()
        val stopRequested = AtomicBoolean(false)
        try {
            conversation.sendMessageAsync(
                Contents.of(Content.Text(prompt)),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        pieces.trySend(message.text())
                    }

                    override fun onDone() {
                        finished.complete(Unit)
                        pieces.close()
                    }

                    override fun onError(throwable: Throwable) {
                        finished.complete(Unit)
                        // The engine reports its own cancellation as a CancellationException:
                        // unless it was asked for, it is a failure like any other
                        val error = if (throwable is CancellationException && !stopRequested.get()) {
                            IllegalStateException("Generation interrupted by the engine", throwable)
                        } else {
                            throwable
                        }
                        pieces.close(error)
                    }
                },
                emptyMap()
            )
            while (true) {
                val next = if (stallTimeoutMillis == null) {
                    pieces.receiveCatching()
                } else {
                    withTimeoutOrNull(stallTimeoutMillis) { pieces.receiveCatching() }
                        ?: throw GenerationStalledException(stallTimeoutMillis)
                }
                if (next.isClosed) {
                    next.exceptionOrNull()?.let { throw it }
                    break
                }
                emit(next.getOrThrow())
            }
        } finally {
            withContext(NonCancellable) {
                if (!finished.isCompleted) {
                    stopRequested.set(true)
                    conversation.cancelProcess()
                }
                // The prompt reading can't be interrupted: the engine stops at its end.
                // A native call stuck on a broken GPU never lets go: closing the
                // conversation under it would crash, so it is left to the engine teardown
                if (withTimeoutOrNull(STOP_TIMEOUT_MS) { finished.await() } != null) {
                    conversation.close()
                } else {
                    Log.w(TAG, "Generation didn't stop in ${STOP_TIMEOUT_MS}ms, conversation left open")
                }
            }
        }
    }

    /**
     * At startup: loads the engine and, if it ended up on GPU, checks with a tiny
     * inference that the GPU really works (on some devices the accelerator doesn't
     * load and inference hangs). If the probe times out, marks the GPU broken and
     * reloads on CPU, so the user's first generation doesn't pay the wait.
     * Detached job: a possibly stuck native thread doesn't block the warm-up.
     */
    suspend fun warmUp(model: LlmModel, file: File) {
        try {
            loadAndProbe(model, file)
        } finally {
            _isWarm.value = true
        }
    }

    /** No warm-up: Gemini Nano writes the recipes, and the engine loads only if Nano fails. */
    fun skipWarmUp() {
        _isWarm.value = true
    }

    private val _isWarm = MutableStateFlow(false)

    /**
     * True once the startup warm-up is over, whatever its outcome: inference nobody
     * is waiting for holds off until then, so it never runs alongside the GPU probe.
     */
    val isWarm: StateFlow<Boolean> = _isWarm.asStateFlow()

    private suspend fun loadAndProbe(model: LlmModel, file: File) {
        val loaded = acquire(model, file)
        if (!currentBackendIsGpu()) return

        // Representative prompt (generates a few sentences with the real parameters): a
        // trivial micro-generation would pass even on a broken GPU. It is an answer being
        // written like the others, and runs on the engine just loaded only: one taken out of
        // memory meanwhile isn't loaded again for it
        val probe = probeScope.async {
            countedAnswer("List five common fruits, one per line.", stallTimeoutMillis = null) {
                loaded.createConversation(
                    ConversationConfig(samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.5, seed = 0))
                )
            }.collect {}
        }
        probeJob = probe
        // A broken GPU can show up in two ways: a hang (timeout) or an immediate
        // exception on the first inference (e.g. OpenCL missing on the emulator,
        // where engine init succeeds instead)
        val ok = try {
            withTimeoutOrNull(GPU_PROBE_TIMEOUT_MS) { probe.await() } != null
        } catch (e: CancellationException) {
            // Stopped by [unloadWhenIdle], the camera wanting the memory: nothing is known of the GPU
            if (!probe.isCancelled || !currentCoroutineContext().isActive) throw e
            Log.i(TAG, "GPU probe stopped: the model is going out of memory, not marking the GPU")
            return
        } catch (e: Exception) {
            Log.w(TAG, "GPU probe threw", e)
            false
        } finally {
            probeJob = null
        }
        if (ok) {
            Log.i(TAG, "GPU probe OK — using GPU")
            return
        }
        // Taken out of memory or replaced by another model while the probe ran: nothing is known of the GPU
        if (!isCurrent(loaded)) {
            Log.w(TAG, "GPU probe inconclusive: the engine went meanwhile, not marking the GPU")
            return
        }
        probe.cancel()
        Log.w(TAG, "GPU probe failed or timed out — marking GPU unusable and reloading on CPU")
        reportGpuUnusable(model)
        acquire(model, file)
    }

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The GPU probe running, if any: [unloadWhenIdle] stops it rather than wait for it. */
    @Volatile
    private var probeJob: Job? = null

    @Synchronized
    private fun isCurrent(engine: Engine): Boolean = this.engine === engine

    // Backend.CPU() runs on a single thread by default: on a 2.6 GB multimodal
    // model that leaves almost every core idle. Using ALL the cores, though, heats
    // and throttles mobile chips under sustained load (minutes of compute), slowing
    // down again towards the end: 4 threads is a reasonable trade-off between
    // parallelism and heat. The emulator has no thermal throttling: it uses all
    // available cores but one.
    private val cpuThreadCount: Int =
        (Runtime.getRuntime().availableProcessors() - 1)
            .coerceIn(1, if (isEmulator) Int.MAX_VALUE else 4)

    /**
     * The GPU produced corrupted output or hung: marks the backend broken
     * (persistently) and reloads on CPU. Returns false if the CPU fails too, at
     * which point there is no remedy.
     */
    @Synchronized
    fun reportGpuUnusable(model: LlmModel): Boolean {
        if (engineModelId != null && engineModelId != model.id) {
            Log.w(TAG, "The engine loaded isn't ${model.id}'s: its GPU isn't blamed")
            return true
        }
        if (!engineIsGpu) {
            Log.e(TAG, "Output unusable on CPU backend too — giving up")
            return false
        }
        Log.w(TAG, "GPU unusable for ${model.id}: switching to CPU (persisted)")
        persistGpuBroken(model)
        sessionGpuFailed.add(model.id)
        engine?.close()
        engine = null
        enginePath = null
        engineIsGpu = false
        engineModelId = null
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
            visionBackend = if (model.supportsVision) backend else null,
            // Without it the caches go next to the model, and where that folder can't be
            // written the GPU doesn't start at all: it has to write its weights first
            cacheDir = modelsDir(context).absolutePath
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

        // GPU probe at startup: if the tiny inference doesn't answer in time, the
        // GPU is unusable on this device
        const val GPU_PROBE_TIMEOUT_MS = 120_000L

        // A stopped generation still finishes reading its prompt: seconds on a CPU
        const val STOP_TIMEOUT_MS = 30_000L
    }
}

/** An answer that stopped coming: on a degraded GPU driver inference can hang. */
class GenerationStalledException(millis: Long) : Exception("No output from the model for ${millis}ms")

/** LiteRT-LM exposes no direct text on [Message]: it has to be extracted from its [Content.Text]. */
internal fun Message.text(): String =
    contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
