package com.smatrisciano.aipantry.recipes.data

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.smatrisciano.aipantry.core.data.ai.LlmEngineHolder
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import com.smatrisciano.aipantry.core.data.ai.text
import com.smatrisciano.aipantry.core.domain.AppLanguage
import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.recipes.domain.GenerationProgress
import com.smatrisciano.aipantry.recipes.domain.RecipeGenerator
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-device recipe generation with the active model (Gemma 4 E2B) via LiteRT-LM.
 * Two stages to minimise generated tokens: a light list right away,
 * instructions on demand when the user opens the recipe.
 */
class LlmRecipeGenerator(
    private val modelRepository: ModelRepository,
    private val engineHolder: LlmEngineHolder
) : RecipeGenerator {

    override val engineName: String
        get() = "${modelRepository.activeModel().displayName} · LiteRT"

    override suspend fun generate(
        ingredients: List<Ingredient>,
        onProgress: (GenerationProgress) -> Unit
    ): List<Recipe> = withContext(Dispatchers.Default) {
        val model = requireNotNull(modelRepository.readyActiveModel()) { "No LLM model available" }
        onProgress(GenerationProgress.LoadingModel(model.displayName))
        engineHolder.acquire(model, modelRepository.modelFile(model))

        onProgress(GenerationProgress.Generating(ingredients.size))
        val language = AppLanguage.current()
        val names = ingredients.map { it.name }
        val round = nextRound(listKey(names, language))
        val prompt = buildListPrompt(ingredientsFor(names, round), language)
        val recipes = withRetry { attempt ->
            if (attempt > 0) onProgress(GenerationProgress.Retrying)
            val rawOutput = generateChecked(model, prompt, seedFor(round, attempt))
            Log.d(TAG, "raw list output: $rawOutput")
            val parsed = RecipeJsonParser.parse(rawOutput)
            if (language == AppLanguage.IT) soundRecipes(parsed, lastAttempt = attempt == MAX_ATTEMPTS - 1) else parsed
        }

        // Missing ingredients aren't decided by the model (unreliable): anything a
        // recipe uses that isn't in the inventory is, by definition, to buy
        val available = ingredients.map { it.name.lowercase() }
        val normalized = recipes.map { recipe ->
            val (owned, toBuy) = recipe.usedIngredients.partition { used ->
                val u = normalizeIngredientName(used.name)
                isPantryStaple(u) || available.any { it in u || u in it }
            }
            val missing = (recipe.missingIngredients + toBuy)
                .filterNot { m -> isPantryStaple(normalizeIngredientName(m.name)) }
                .distinctBy { normalizeIngredientName(it.name) }
            recipe.copy(usedIngredients = owned, missingIngredients = missing)
        }

        // Recipes that are missing something go last; with the same number of
        // missing items, the model's relevance order stays
        normalized.sortedBy { it.missingIngredients.size }
    }

    override suspend fun generateDetails(
        recipe: Recipe,
        ingredients: List<Ingredient>
    ): Recipe = withContext(Dispatchers.Default) {
        val model = requireNotNull(modelRepository.readyActiveModel()) { "No LLM model available" }
        val prompt = buildDetailsPrompt(recipe, AppLanguage.current())
        val round = nextRound(prompt)
        withRetry { attempt ->
            val rawOutput = generateChecked(model, prompt, seedFor(round, attempt))
            Log.d(TAG, "raw details output: $rawOutput")
            RecipeJsonParser.parseDetails(recipe, rawOutput)
        }
    }

    /**
     * Inference with two safeguards:
     *  - watchdog: on a degraded GPU driver `sendMessage` can block forever
     *    (non-interruptible native call). It runs in a separate job with a timeout
     *    on the await: when it expires the CPU is forced and the call retried,
     *    instead of leaving the UI loading forever;
     *  - anti-corruption: if the model emits garbage tokens (<pad>, <unused…>),
     *    it is recreated on CPU and the call retried.
     */
    private suspend fun generateChecked(
        model: com.smatrisciano.aipantry.core.data.ai.LlmModel,
        prompt: String,
        seed: Int
    ): String {
        val file = modelRepository.modelFile(model)

        // The watchdog ONLY exists to expose a GPU hang: the CPU never hangs, it's
        // just slow, so it must be allowed to finish with no limit (otherwise a long
        // CPU generation would be killed and retried).
        val rawOutput = if (engineHolder.currentBackendIsGpu()) {
            // Detached job: if the GPU hangs, the native thread stays stuck (it can't be
            // killed) but the calling coroutine moves on and recovers on CPU
            val generation = watchdogScope.async {
                engineHolder.createConversation(model, file, temperature = 0.5, topK = 25, topP = 0.9, seed = seed).use { conversation ->
                    conversation.sendMessage(Contents.of(Content.Text(prompt))).text()
                }
            }
            // Besides hanging, the GPU can fail with an immediate exception (e.g.
            // OpenCL missing on the emulator): same treatment as the timeout
            val result = try {
                withTimeoutOrNull(GENERATION_TIMEOUT_MS) { generation.await() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "GPU generation failed", e)
                null
            }
            result ?: run {
                generation.cancel()
                Log.w(TAG, "GPU generation failed or timed out, switching to CPU")
                engineHolder.reportGpuUnusable(model)
                error("Generation failed on GPU, retrying")
            }
        } else {
            engineHolder.createConversation(model, file, temperature = 0.5, topK = 40, topP = 0.9, seed = seed).use { conversation ->
                conversation.sendMessage(Contents.of(Content.Text(prompt))).text()
            }
        }

        if (garbageMarkers.any { it in rawOutput }) {
            Log.w(TAG, "corrupted output detected: ${rawOutput.take(120)}")
            check(engineHolder.reportGpuUnusable(model)) {
                "The AI model is producing corrupted output on this device"
            }
            error("Corrupted LLM output, retrying")
        }
        return rawOutput
    }

    /**
     * With a small (1-2B) model the output sometimes ignores the format:
     * retry silently before the error reaches the UI.
     */
    private inline fun <T> withRetry(attempts: Int = MAX_ATTEMPTS, block: (attempt: Int) -> T): T {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                return block(attempt)
            } catch (e: Exception) {
                Log.w(TAG, "LLM attempt ${attempt + 1}/$attempts failed", e)
                lastError = e
            }
        }
        throw requireNotNull(lastError)
    }

    /**
     * With a fixed seed the output is deterministic (bit-identical on CPU):
     * "Regenerate" and the retries would always give the same answer. So every new
     * request with the same key (a recipe's details prompt, or an ingredient set
     * for the lists) moves on to seeds not used yet, starting from 1.
     */
    private val rounds = mutableMapOf<String, Int>()

    private fun nextRound(key: String): Int = synchronized(rounds) {
        val round = rounds.getOrDefault(key, 0)
        rounds[key] = round + 1
        round
    }

    /** Same ingredients in any order, same language: the same sequence of lists. */
    private fun listKey(names: List<String>, language: AppLanguage): String =
        "$language|" + names.map { it.lowercase() }.sorted().joinToString("|")

    /**
     * A new seed alone isn't enough: at this temperature a 2B model keeps going back
     * to the same few dishes, and from the third list on it mostly repeats itself.
     * What steers it is the ingredients it is given, so every list after the first
     * gets a different, deterministic two thirds of the inventory (all of it when the
     * inventory is small), in a different order. Naming the dishes already shown and
     * asking to avoid them doesn't work: a 2B model copies them instead.
     */
    private fun ingredientsFor(names: List<String>, round: Int): List<String> {
        if (round == 0) return names
        val keep = maxOf(minOf(names.size, MIN_LIST_INGREDIENTS), (names.size * LIST_SHARE_PERCENT + 99) / 100)
        return names.sortedBy { mix32(it.lowercase().hashCode() + round * GOLDEN_RATIO_32) }.take(keep)
    }

    // MurmurHash3's finalizer: nearby inputs (the same name, the next round) land far apart
    private fun mix32(value: Int): Int {
        var h = value
        h = h xor (h ushr 16)
        h *= 0x85EBCA6B.toInt()
        h = h xor (h ushr 13)
        h *= 0xC2B2AE35.toInt()
        return h xor (h ushr 16)
    }

    /**
     * Tidies the titles and drops the dishes that don't hold together (see
     * [RecipeTitleRules]). With fewer than [MIN_SOUND_RECIPES] left the attempt
     * fails and the next seed is tried, except on the last attempt.
     */
    private fun soundRecipes(recipes: List<Recipe>, lastAttempt: Boolean): List<Recipe> {
        val (odd, sound) = recipes
            .map { it.copy(title = RecipeTitleRules.tidy(it.title)) }
            .partition { RecipeTitleRules.isOddCombination(it.title) }
        if (odd.isNotEmpty()) Log.d(TAG, "dropped odd recipes: ${odd.map { it.title }}")
        check(sound.size >= MIN_SOUND_RECIPES || (lastAttempt && sound.isNotEmpty())) {
            "Only ${sound.size} sound recipes"
        }
        return sound
    }

    // Starts at 1: for the runtime, seed 0 and seed 1 give the same output
    private fun seedFor(round: Int, attempt: Int): Int = 1 + round * MAX_ATTEMPTS + attempt

    // JSON keys and difficulty values stay in English in both languages: they
    // are the contract with the parser, only the contents are translated
    private fun buildListPrompt(ingredientNames: List<String>, language: AppLanguage): String {
        val names = ingredientNames.joinToString(", ")
        // Short prompt: fewer input tokens = less prefill = faster on CPU
        return when (language) {
            AppLanguage.EN -> """
                Ingredients: $names.
                Output ONLY a JSON array of 4 recipes, each exactly:
                {"title":string,"prepTimeMinutes":int,"difficulty":"EASY"|"MEDIUM"|"HARD","usedIngredients":[names]}
                No text, no amounts, no steps.
            """
            // Without the title rule Gemma builds the title by listing the ingredients
            // ("Risotto ai funghi e riso" half of the time). Pushing for more creativity
            // ("piatti non banali", "almeno uno al forno") or adding more title rules
            // changes the dishes for the worse: the remaining slips are fixed in code
            // (RecipeTitleRules)
            AppLanguage.IT -> """
                Ingredienti: $names.
                Proponi 4 ricette diverse tra loro (primi, secondi, contorni).
                Il titolo è il nome del piatto come in un ricettario, con la sola iniziale maiuscola: non elencare ingredienti che il nome già implica (come il riso in un risotto).
                Rispondi SOLO con un array JSON, ogni ricetta esattamente:
                {"title":string,"prepTimeMinutes":int,"difficulty":"EASY"|"MEDIUM"|"HARD","usedIngredients":[nomi]}
                Titoli e ingredienti in italiano. Niente testo, niente quantità, niente passaggi.
            """
        }.trimIndent()
    }

    private fun buildDetailsPrompt(recipe: Recipe, language: AppLanguage): String {
        val names = (recipe.usedIngredients + recipe.missingIngredients).joinToString { it.name }
        // Field descriptions, not example values (a small model would copy them
        // verbatim). Metric only. Short but with a clear schema.
        return when (language) {
            AppLanguage.EN -> """
                Recipe: "${recipe.title}". Ingredients: $names.
                Give real metric amounts (g/ml, never tbsp/cups) and real cooking steps.
                Respond with ONLY a JSON object (no markdown):
                {
                  "whySuitable": string (one short sentence why it fits),
                  "ingredients": [{"name": string, "amount": string in g or ml}],
                  "steps": [string] (4 to 8 real cooking steps),
                  "variants": [string] (up to 3 variations)
                }
            """
            AppLanguage.IT -> """
                Ricetta: "${recipe.title}". Ingredienti: $names.
                Indica quantità reali in unità metriche (g/ml, mai cucchiai o tazze) e veri passaggi di cottura.
                Rispondi SOLO con un oggetto JSON (niente markdown), con i testi in italiano:
                {
                  "whySuitable": string (una frase breve sul perché è adatta),
                  "ingredients": [{"name": string, "amount": string in g o ml}],
                  "steps": [string] (da 4 a 8 veri passaggi di cottura),
                  "variants": [string] (fino a 3 varianti)
                }
            """
        }.trimIndent()
    }

    /** Whole-word match: "pepe" must not cover "peperoni", nor "sale" "salame". */
    private fun isPantryStaple(name: String): Boolean =
        PANTRY_STAPLES.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(name) }

    /** "2 tbsp Olive Oil" / "2 cucchiai d'olio" → "olive oil" / "olio": strips quantity and unit. */
    private fun normalizeIngredientName(raw: String): String =
        raw.lowercase()
            .replace(Regex("""^[\d\s/.,½¼¾()-]+"""), "")
            .replace(
                Regex(
                    """^(tbsps?|tsps?|tablespoons?|teaspoons?|cups?|grams?|g|kg|ml|l|oz|lbs?|pcs?|pieces?|cloves?|slices?|cans?|packs?|bunch(es)?|""" +
                        """cucchia(?:ini|ino|io|i)|tazz[ae]|grammi|gr|pz|pezz[io]|spicch[io]|fett[ae]|lattin[ae]|scatolett[ae]|confezion[ei]|mazz[oi]|pizzico)\s+(of\s+|di\s+|d')?"""
                ),
                ""
            )
            .trim()

    // Detached scope for the watchdog: its jobs can stay stuck on a native GPU
    // call without dragging the calling coroutine along
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TAG = "LlmRecipeGenerator"
        const val MAX_ATTEMPTS = 4

        // A list with fewer dishes than this after dropping the odd ones is regenerated
        const val MIN_SOUND_RECIPES = 3
        const val GOLDEN_RATIO_32 = -0x61C88647 // 0x9E3779B9, spreads consecutive rounds apart

        // Share of the inventory each list after the first is built from, never below
        // MIN_LIST_INGREDIENTS (or the whole inventory, when it is smaller than that)
        const val LIST_SHARE_PERCENT = 65
        const val MIN_LIST_INGREDIENTS = 8

        // Beyond this time the generation is considered stuck (degraded GPU):
        // the CPU is forced and the call retried
        const val GENERATION_TIMEOUT_MS = 75_000L

        val garbageMarkers = listOf("<unused", "<pad>", "<unk>")

        // Basic pantry always available (as per the prompt): never "to buy".
        // Both languages together: the inventory can mix them if the user changed
        // language between one scan and the next
        val PANTRY_STAPLES = setOf(
            "water", "salt", "pepper", "olive oil", "oil",
            "sugar", "flour", "bread", "butter", "vinegar",
            "acqua", "sale", "pepe", "olio d'oliva", "olio",
            "zucchero", "farina", "pane", "burro", "aceto"
        )
    }
}
