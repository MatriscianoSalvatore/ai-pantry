package com.smatrisciano.aipantry.core.data.ai

import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/**
 * The recipes written by Gemini Nano (AICore, ML Kit GenAI Prompt API) where the phone
 * has Gemini Nano 4: the production version of Gemma 4, the family of the Gemma the
 * app runs itself, here on the phone's AI accelerator and without a second model loaded
 * into the app. An earlier Nano (nano-v3, of the Gemma 3n generation) writes recipes
 * below Gemma 4 E2B's: it stays with the ingredients, and Gemma writes the recipes.
 */
class GeminiNanoWriter(private val choices: ModelPreferences) {

    private val model by lazy { Generation.getClient() }

    // The base model doesn't change while the app runs: asked once, when it is there
    @Volatile
    private var generation: Int? = null

    @Volatile
    private var baseModel: String? = null

    // Failed in this session (a quota, an AICore error): Gemma writes until the next launch
    @Volatile
    private var failed = false

    private val _available = MutableStateFlow(false)

    /** Whether Nano writes the recipes, as of the last [isUsable]. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _present = MutableStateFlow(false)

    /** Whether the phone has Gemini Nano downloaded and ready, whichever model is chosen: as of the last [isUsable]. */
    val present: StateFlow<Boolean> = _present.asStateFlow()

    /** The base model AICore runs ("nano-v3"), once it has said so. */
    val baseModelName: String? get() = baseModel

    /**
     * True when the phone has Gemini Nano downloaded and ready, it hasn't failed in this
     * session, and the recipes aren't given to Gemma (by choice, with Gemma there to write
     * them). Not downloaded yet, it is the ingredient detector that asks AICore for it.
     */
    suspend fun isUsable(): Boolean {
        val present = !failed && runCatching {
            val status = model.checkStatus()
            Log.i(TAG, "AICore feature status: ${status.featureStatusName()}")
            status == FeatureStatus.AVAILABLE && baseGeneration() >= MIN_GENERATION
        }.getOrElse {
            // No AICore on the phone, or not answering: not asked again in this session
            Log.w(TAG, "AICore unreachable: Gemma writes the recipes", it)
            failed = true
            false
        }
        _present.value = present
        val usable = present && !(choices.recipes.value == ModelChoice.GEMMA && choices.gemmaReady)
        _available.value = usable
        return usable
    }

    /**
     * The answer to [prompt], a piece at a time as Nano writes it; any failure is a [NanoFailureException].
     * A request writes 256 tokens at most, less than a recipe's details: when Nano stops at that
     * limit it is asked to carry on from what it has written, up to [MAX_CONTINUATIONS] times.
     */
    fun answer(prompt: String, temperature: Float, topK: Int, seed: Int, onRequest: (Int) -> Unit = {}): Flow<String> =
        flow {
            val written = StringBuilder()
            for (part in 0..MAX_CONTINUATIONS) {
                onRequest(part)
                // Built in the flow: a request the Prompt API rejects is a failure of Nano, not of the caller
                val request = generateContentRequest(
                    TextPart(if (part == 0) prompt else continuationPrompt(prompt, written))
                ) {
                    this.temperature = temperature
                    this.topK = topK
                    this.seed = seed + part
                    candidateCount = 1
                    maxOutputTokens = MAX_OUTPUT_TOKENS
                }
                var finishReason: Int? = null
                // Only whole lines go out: the one under way is held back, because a part cut at the
                // token limit ends mid-line, and the next one starts again from the last whole line
                val pending = StringBuilder()
                var partLength = 0
                var opening = part > 0
                suspend fun FlowCollector<String>.send(text: String) {
                    // A continuation may open with a markdown fence again
                    val piece = if (opening) text.replace(leadingFence, "") else text
                    if (piece.isEmpty()) return
                    opening = false
                    written.append(piece)
                    partLength += piece.length
                    emit(piece)
                }
                model.generateContentStream(request).collect { response ->
                    val candidate = response.candidates.firstOrNull() ?: return@collect
                    candidate.finishReason?.let { finishReason = it }
                    pending.append(candidate.text)
                    val wholeLines = pending.lastIndexOf("\n") + 1
                    if (wholeLines > 0) {
                        send(pending.substring(0, wholeLines))
                        pending.delete(0, wholeLines)
                    }
                }
                // Cut at the token limit with whole lines written, the line under way is written again
                // by the next part. Otherwise it is the end: of the answer, or a single line, which has
                // no whole line to start again from
                val carryOn = finishReason == Candidate.FinishReason.MAX_TOKENS && partLength > 0
                if (!carryOn) send(pending.toString())
                Log.i(TAG, "Nano part ${part + 1}: $partLength chars, finish reason $finishReason")
                if (!carryOn) break
            }
        }
            .catch { error -> throw if (error is CancellationException) error else NanoFailureException(error) }

    private fun continuationPrompt(prompt: String, written: CharSequence): String =
        "$prompt\n\n" +
            "Your answer so far was cut off by a length limit:\n$written\n\n" +
            "Continue it from exactly where it stopped. Write only the rest: never repeat what is " +
            "already written, no markdown fences, no comments."

    /** Nano failed an answer: Gemma writes the recipes for the rest of the session. */
    fun reportFailure() {
        failed = true
        _available.value = false
    }

    /** "nano-v3" → 3, "nano-v4-fast" → 4: the first number after "nano", 0 if there is none. */
    private suspend fun baseGeneration(): Int =
        generation ?: model.getBaseModelName().let { name ->
            val number = generationNumber.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Log.i(TAG, "Gemini Nano base model: $name")
            baseModel = name
            number.also { generation = it }
        }

    companion object {
        /** Shown where the recipes say which model wrote them. */
        const val DISPLAY_NAME = "Gemini Nano 4"
        const val ENGINE_NAME = "$DISPLAY_NAME · AICore"

        private const val TAG = "GeminiNanoWriter"
        private const val MIN_GENERATION = 3

        // The most the Prompt API accepts (1..256 as of genai-prompt 1.0.0-beta2), and its default
        private const val MAX_OUTPUT_TOKENS = 256

        // 4 more requests after the first: ~1.3K tokens, over a recipe's details and its list
        private const val MAX_CONTINUATIONS = 4

        private val leadingFence = Regex("""^\s*```[a-zA-Z]*\s*""")

        private val generationNumber = Regex("""nano\D*(\d+)""", RegexOption.IGNORE_CASE)
    }
}

/** The name of a [FeatureStatus] value, for the logs. */
internal fun Int.featureStatusName(): String = when (this) {
    FeatureStatus.AVAILABLE -> "AVAILABLE"
    FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
    FeatureStatus.DOWNLOADING -> "DOWNLOADING"
    FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
    else -> "UNKNOWN($this)"
}

/** Gemini Nano couldn't write an answer (a quota, an AICore error): Gemma takes over. */
class NanoFailureException(cause: Throwable) : Exception("Gemini Nano failed", cause)
