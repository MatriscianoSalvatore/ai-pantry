package com.smatrisciano.aipantry.core.data.ai

import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * The recipes written by Gemini Nano (AICore, ML Kit GenAI Prompt API) where the phone
 * has Gemini Nano 4: the production version of Gemma 4, the family of the Gemma the
 * app runs itself, here on the phone's AI accelerator and without a second model loaded
 * into the app. An earlier Nano (nano-v3, of the Gemma 3n generation) writes recipes
 * below Gemma 4 E2B's: it stays with the ingredients, and Gemma writes the recipes.
 */
class GeminiNanoWriter {

    private val model by lazy { Generation.getClient() }

    // The base model doesn't change while the app runs: asked once, when it is there
    @Volatile
    private var generation: Int? = null

    // Failed in this session (a quota, an AICore error): Gemma writes until the next launch
    @Volatile
    private var failed = false

    private val _available = MutableStateFlow(false)

    /** Whether Nano writes the recipes, as of the last [isUsable]. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    /**
     * True when the phone has Gemini Nano 4 or later downloaded and ready, and it hasn't
     * failed in this session. Not downloaded yet, it is the ingredient detector that asks
     * AICore for it.
     */
    suspend fun isUsable(): Boolean {
        val usable = !failed && runCatching {
            model.checkStatus() == FeatureStatus.AVAILABLE && baseGeneration() >= MIN_GENERATION
        }.getOrElse {
            // No AICore on the phone, or not answering: not asked again in this session
            Log.w(TAG, "AICore unreachable: Gemma writes the recipes", it)
            failed = true
            false
        }
        _available.value = usable
        return usable
    }

    /** The answer to [prompt], a piece at a time as Nano writes it; any failure is a [NanoFailureException]. */
    fun answer(prompt: String, temperature: Float, topK: Int, seed: Int): Flow<String> =
        model.generateContentStream(
            generateContentRequest(TextPart(prompt)) {
                this.temperature = temperature
                this.topK = topK
                this.seed = seed
                candidateCount = 1
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }
        )
            .map { response -> response.candidates.firstOrNull()?.text.orEmpty() }
            .filter { it.isNotEmpty() }
            .catch { error -> throw if (error is CancellationException) error else NanoFailureException(error) }

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
            number.also { generation = it }
        }

    companion object {
        /** Shown where the recipes say which model wrote them. */
        const val DISPLAY_NAME = "Gemini Nano 4"
        const val ENGINE_NAME = "$DISPLAY_NAME · AICore"

        private const val TAG = "GeminiNanoWriter"
        private const val MIN_GENERATION = 4

        // A list or a recipe's details run to 400-650 tokens: room to spare, well under
        // the 4K AICore advises against going past
        private const val MAX_OUTPUT_TOKENS = 1024

        private val generationNumber = Regex("""nano\D*(\d+)""", RegexOption.IGNORE_CASE)
    }
}

/** Gemini Nano couldn't write an answer (a quota, an AICore error): Gemma takes over. */
class NanoFailureException(cause: Throwable) : Exception("Gemini Nano failed", cause)
