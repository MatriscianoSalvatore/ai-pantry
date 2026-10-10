package com.smatrisciano.aipantry.recipes.data

import android.os.SystemClock
import android.util.Log
import com.smatrisciano.aipantry.core.data.WaitTimeEstimator
import com.smatrisciano.aipantry.core.data.WaitTimeEstimator.Measure
import com.smatrisciano.aipantry.core.data.ai.GeminiNanoWriter
import com.smatrisciano.aipantry.core.data.ai.GenerationControl
import com.smatrisciano.aipantry.core.data.ai.InferenceStats
import com.smatrisciano.aipantry.core.data.ai.InferenceTask
import com.smatrisciano.aipantry.core.data.ai.StoppedByUserException
import com.smatrisciano.aipantry.core.data.ai.LlmEngineHolder
import com.smatrisciano.aipantry.core.data.ai.LlmModel
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import com.smatrisciano.aipantry.core.data.ai.NanoFailureException
import com.smatrisciano.aipantry.core.domain.AppLanguage
import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.inventory.domain.models.IngredientSource
import com.smatrisciano.aipantry.recipes.domain.DetailsPart
import com.smatrisciano.aipantry.recipes.domain.DetailsUpdate
import com.smatrisciano.aipantry.recipes.domain.GenerationProgress
import com.smatrisciano.aipantry.recipes.domain.ListUpdate
import com.smatrisciano.aipantry.recipes.domain.RECIPES_PER_LIST
import com.smatrisciano.aipantry.recipes.domain.RecipeGenerator
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import com.smatrisciano.aipantry.recipes.domain.models.RecipeIngredient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.ln

/**
 * On-device recipe generation: Gemini Nano 4 where the phone has it (see
 * [GeminiNanoWriter]), otherwise the active model (Gemma 4 E2B) via LiteRT-LM, with the
 * same prompts either way. Two stages to minimise generated tokens: a light list first,
 * instructions only for the recipe the user opens. Both stream: every recipe of the
 * list, and every part of the instructions, goes out as soon as the model has written it.
 */
class LlmRecipeGenerator(
    private val modelRepository: ModelRepository,
    private val engineHolder: LlmEngineHolder,
    private val nano: GeminiNanoWriter,
    private val waitTimes: WaitTimeEstimator,
    private val stats: InferenceStats,
    private val control: GenerationControl
) : RecipeGenerator {

    override val engineName: String
        get() = if (nano.available.value) GeminiNanoWriter.ENGINE_NAME else "${modelRepository.activeModel().displayName} · LiteRT"

    // Whether Nano is writing the answer under way, for what a failure means (see onFailedAttempt)
    @Volatile
    private var answeredByNano = false

    override fun generate(
        ingredients: List<Ingredient>,
        round: Int,
        earlier: List<Recipe>,
        received: List<Recipe>,
        firstAttempt: Int,
        written: Int
    ): Flow<ListUpdate> = channelFlow {
        // Nano 4 is ready in AICore already; Gemma loads into the app if it isn't there yet
        val gemmaModel = if (nano.isUsable()) null else gemma()
        send(ListUpdate.Step(GenerationProgress.LoadingModel(gemmaModel?.displayName ?: GeminiNanoWriter.DISPLAY_NAME)))
        gemmaModel?.let { engineHolder.acquire(it, modelRepository.modelFile(it)) }

        send(ListUpdate.Step(GenerationProgress.Generating(ingredients.size)))
        val language = AppLanguage.current()
        val prompt = buildListPrompt(ingredientsFor(promptOrder(ingredients).map { it.name }, round), language)
        // The received recipes are among the dishes too: an attempt that is resumed or
        // repeated writes some of them again, and they mustn't show twice
        val dishes = ListDishes(earlier, received)
        // A new list makes do with fewer, so the first wait stays short. A list that grows
        // gets all its new recipes: by then its dishes come back often, and are dropped
        val enough = if (earlier.isEmpty()) MIN_SOUND_RECIPES else RECIPES_PER_LIST
        var lastError: Exception? = null
        for (attempt in firstAttempt until MAX_ATTEMPTS) {
            send(ListUpdate.Attempt(attempt))
            if (attempt > 0) send(ListUpdate.Step(GenerationProgress.Retrying))
            try {
                val replay = if (attempt == firstAttempt) written else 0
                writeList(prompt, seedFor(round, attempt), ingredients, language, dishes, replay)
                // Too few new sound recipes (odd dishes and repeats dropped, or an answer that
                // ignored the format): the next attempt adds to the ones already sent
                if (dishes.added >= enough) return@channelFlow
                lastError = IllegalStateException("Only ${dishes.added} new sound recipes")
            } catch (e: CancellationException) {
                throw e
            } catch (e: StoppedByUserException) {
                // Stopped by hand: no new attempt, and Nano or the GPU aren't to blame
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "LLM attempt ${attempt + 1}/$MAX_ATTEMPTS failed", e)
                onFailedAttempt(e)
                lastError = e
            }
        }
        // Out of attempts: whatever was written stays, however short the list
        if (dishes.added == 0) throw lastError ?: IllegalStateException("No recipes")
    }.flowOn(Dispatchers.Default)

    override fun generateDetails(recipe: Recipe, round: Int): Flow<DetailsUpdate> = channelFlow {
        if (!nano.isUsable()) gemma().let { engineHolder.acquire(it, modelRepository.modelFile(it)) }
        val prompt = buildDetailsPrompt(recipe, AppLanguage.current())
        var lastError: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) send(DetailsUpdate.Retrying)
            try {
                val details = writeDetails(prompt, seedFor(round, attempt), recipe)
                send(DetailsUpdate.Written(details, writing = null))
                return@channelFlow
            } catch (e: CancellationException) {
                throw e
            } catch (e: StoppedByUserException) {
                // Stopped by hand: no new attempt, and Nano or the GPU aren't to blame
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "LLM attempt ${attempt + 1}/$MAX_ATTEMPTS failed", e)
                onFailedAttempt(e)
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No details")
    }.flowOn(Dispatchers.Default)

    /**
     * One attempt at the list: every sound recipe goes out as soon as its object is
     * complete. The first [replay] objects were written before an interruption: the
     * same seed writes them again, and meanwhile there is no new recipe to follow.
     */
    private suspend fun ProducerScope<ListUpdate>.writeList(
        prompt: String,
        seed: Int,
        ingredients: List<Ingredient>,
        language: AppLanguage,
        dishes: ListDishes,
        replay: Int
    ) {
        val objects = JsonObjectStream()
        val output = StringBuilder()
        val recipeChars = waitTimes.expected(Measure.LIST_RECIPE_CHARS)
        // Objects completed in this answer, odd dishes included: they took as long to write
        var written = 0
        val progress = AnswerProgress(LIST_READING_SHARE, waitTimes.expected(Measure.PROMPT_READING_MILLIS))
        // Only changes that show: a whole percent of the list or of the recipe being written
        var lastSent: List<Int?>? = null
        suspend fun report(fraction: Float, recipe: Float?) {
            val shown = listOf((fraction * 100).toInt(), written, recipe?.let { (it * 100).toInt() })
            if (shown == lastSent) return
            lastSent = shown
            send(ListUpdate.Progress(fraction, written, recipe))
        }
        coroutineScope {
            val reading = launch {
                while (true) {
                    report(progress.reading(), recipe = null)
                    delay(PROGRESS_TICK_MILLIS)
                }
            }
            answer(prompt, seed, InferenceTask.RECIPE_LIST)
                // An attempt that only tops up the round is done as soon as the round is full
                .takeWhile { !dishes.isFull }
                .collect { piece ->
                    if (output.isEmpty()) {
                        reading.cancel()
                        waitTimes.record(Measure.PROMPT_READING_MILLIS, progress.elapsedMillis)
                    }
                    output.append(piece)
                    checkNotCorrupted(output)
                    for (json in objects.append(piece)) {
                        written++
                        waitTimes.record(Measure.LIST_RECIPE_CHARS, json.length.toLong())
                        RecipeJsonParser.parseRecipe(json)?.let { offer(it, ingredients, language, dishes) }
                    }
                    val current = (objects.pendingLength.toFloat() / recipeChars).coerceAtMost(UNFINISHED_RECIPE_CAP)
                    report(
                        fraction = progress.writing((written + current) / RECIPES_PER_LIST),
                        recipe = current.takeIf { written >= replay }
                    )
                }
            reading.cancel()
        }
        Log.d(TAG, "raw list output: $output")
        // Not a sequence of plain objects (escaped JSON, say): parsed as a whole at the end
        if (written == 0) RecipeJsonParser.parse(output.toString()).forEach { offer(it, ingredients, language, dishes) }
    }

    /** One attempt at the details: every complete part goes out as soon as it is written. */
    private suspend fun ProducerScope<DetailsUpdate>.writeDetails(
        prompt: String,
        seed: Int,
        recipe: Recipe
    ): Recipe {
        val output = StringBuilder()
        val expectedChars = waitTimes.expected(Measure.DETAILS_CHARS)
        var parsedUpTo = -1
        // var writing = DetailsPart.INTRO
        var writing = DetailsPart.INGREDIENTS
        val progress = AnswerProgress(DETAILS_READING_SHARE, waitTimes.expected(Measure.PROMPT_READING_MILLIS))
        // Only changes that show: a whole percent
        var lastPercent = -1
        suspend fun report(fraction: Float) {
            val percent = (fraction * 100).toInt()
            if (percent == lastPercent) return
            lastPercent = percent
            send(DetailsUpdate.Progress(fraction))
        }
        coroutineScope {
            val reading = launch {
                while (true) {
                    report(progress.reading())
                    delay(PROGRESS_TICK_MILLIS)
                }
            }
            answer(prompt, seed, InferenceTask.RECIPE_DETAILS).collect { piece ->
                if (output.isEmpty()) {
                    reading.cancel()
                    waitTimes.record(Measure.PROMPT_READING_MILLIS, progress.elapsedMillis)
                }
                output.append(piece)
                checkNotCorrupted(output)
                RecipeJsonParser.parsePartialDetails(recipe, output, after = parsedUpTo)?.let { partial ->
                    parsedUpTo = partial.end
                    partial.writing?.let { writing = it }
                    send(DetailsUpdate.Written(partial.recipe, writing))
                }
                report(progress.writing(output.length.toFloat() / expectedChars))
            }
            reading.cancel()
        }
        Log.d(TAG, "raw details output: $output")
        val details = RecipeJsonParser.parseDetails(recipe, output.toString())
        waitTimes.record(Measure.DETAILS_CHARS, output.length.toLong())
        return details
    }

    /**
     * Sends [recipe] unless the list already has that dish or (in Italian) it doesn't
     * hold together, with its title tidied (see [RecipeTitleRules]) and what's missing
     * worked out.
     */
    private suspend fun ProducerScope<ListUpdate>.offer(
        recipe: Recipe,
        ingredients: List<Ingredient>,
        language: AppLanguage,
        dishes: ListDishes
    ) {
        val tidy = if (language == AppLanguage.IT) recipe.copy(title = RecipeTitleRules.tidy(recipe.title)) else recipe
        if (language == AppLanguage.IT && RecipeTitleRules.isOddCombination(tidy.title)) {
            Log.d(TAG, "dropped odd recipe: ${tidy.title}")
            return
        }
        if (dishes.isFull) return
        if (!dishes.add(tidy.title)) {
            Log.d(TAG, "dropped repeated dish: ${tidy.title}")
            return
        }
        send(ListUpdate.Written(withMissingIngredients(tidy, ingredients, language)))
    }

    /**
     * Missing ingredients aren't decided by the model (unreliable): anything a recipe
     * uses that isn't in the inventory is, by definition, to buy. That includes what the
     * title promises and the model left out of its list ("Spaghetti alle vongole" without
     * vongole): from the inventory if it has them, otherwise to buy.
     */
    private fun withMissingIngredients(recipe: Recipe, ingredients: List<Ingredient>, language: AppLanguage): Recipe {
        val available = ingredients.map { it.name.lowercase() }
        val (listedOwned, toBuy) = recipe.usedIngredients.partition { used ->
            val u = normalizeIngredientName(used.name)
            isPantryStaple(u) || available.any { it in u || u in it }
        }
        val listedMissing = (recipe.missingIngredients + toBuy)
            .filterNot { m -> isPantryStaple(normalizeIngredientName(m.name)) }
            .distinctBy { normalizeIngredientName(it.name) }

        val (owned, missing) = if (language == AppLanguage.IT) {
            val unlisted = unlistedTitleIngredients(recipe.title, recipe.usedIngredients + recipe.missingIngredients)
            val (atHome, toGet) = unlisted.partition { TitleIngredients.isCovered(it, ingredients.map { i -> i.name }) }
            (listedOwned + atHome.map { RecipeIngredient(name = it.replaceFirstChar(Char::titlecase)) }) to
                (listedMissing + toGet.map { RecipeIngredient(name = it.replaceFirstChar(Char::titlecase)) })
        } else {
            listedOwned to listedMissing
        }
        return recipe.copy(usedIngredients = owned, missingIngredients = missing)
    }

    /** What [title] names that [listed] doesn't have. */
    private fun unlistedTitleIngredients(title: String, listed: List<RecipeIngredient>): List<String> =
        TitleIngredients.of(title).filterNot { named ->
            TitleIngredients.isCovered(named, listed.map { it.name }) || isPantryStaple(named)
        }

    /**
     * The model's answer, a piece at a time: Gemini Nano 4's where the phone has it,
     * otherwise Gemma's, with the same sampling. For Gemma, on GPU a watchdog catches a
     * hung driver (no new text for [GPU_STALL_TIMEOUT_MS]) and any failure of the engine
     * is the GPU's: see [onFailedAttempt]. The CPU never hangs, it's just slow, so there
     * an answer takes as long as it needs.
     */
    private suspend fun answer(prompt: String, seed: Int, task: InferenceTask): Flow<String> {
        answeredByNano = nano.isUsable()
        if (answeredByNano) {
            val run = stats.begin(task, "Gemini Nano · ${nano.baseModelName ?: "AICore"}", "AICore")
            return nano
                .answer(prompt, temperature = 0.5f, topK = 40, seed = seed, onRequest = { part -> if (part > 0) run.request() })
                .timed(run)
        }
        // Loaded already, unless Nano was writing until it failed
        val model = gemma()
        engineHolder.acquire(model, modelRepository.modelFile(model))
        val gpu = engineHolder.currentBackendIsGpu()
        val run = stats.begin(task, model.displayName, if (gpu) "GPU" else "CPU · ${engineHolder.cpuThreads} thread")
        return engineHolder.streamAnswer(
            model = model,
            file = modelRepository.modelFile(model),
            prompt = prompt,
            temperature = 0.5,
            topK = if (gpu) 25 else 40,
            topP = 0.9,
            seed = if (gpu) seed - FIRST_SEED + FIRST_GPU_SEED else seed,
            stallTimeoutMillis = if (gpu) GPU_STALL_TIMEOUT_MS else null
        ).catch { error ->
            // Besides hanging, the GPU can fail with an immediate exception (e.g. OpenCL
            // missing on the emulator): same treatment
            throw if (gpu && error !is CancellationException) GpuFailureException(error) else error
        }.timed(run)
    }

    /** What comes out of the model is counted and timed for the verbose display. */
    private fun Flow<String>.timed(run: InferenceStats.Run): Flow<String> =
        control.stoppable(this)
            .onEach { run.output(it.length) }
            .onCompletion { cause -> run.finish(failed = cause != null && cause !is CancellationException) }

    /** Garbage tokens (<pad>, <unused…>) in the answer: see [onFailedAttempt]. */
    private fun checkNotCorrupted(output: CharSequence) {
        if (garbageMarkers.any { output.contains(it) }) throw CorruptedOutputException(output.take(120).toString())
    }

    /**
     * Nano failing, or writing garbage tokens, hands the recipes to Gemma for the rest
     * of the session. With Gemma, a failing or hung GPU, or garbage tokens in the answer,
     * mean the GPU backend is broken on this device: it is marked unusable and the next
     * attempt runs on CPU.
     */
    private fun onFailedAttempt(error: Exception) {
        when {
            error is NanoFailureException || (answeredByNano && error is CorruptedOutputException) ->
                nano.reportFailure()
            error is GpuFailureException || error is CorruptedOutputException ->
                engineHolder.reportGpuUnusable(modelRepository.activeModel())
        }
    }

    /** The Gemma model, ready on the device. */
    private fun gemma(): LlmModel = requireNotNull(modelRepository.readyActiveModel()) { "No LLM model available" }

    private class GpuFailureException(cause: Throwable) : Exception("Generation failed on GPU", cause)

    private class CorruptedOutputException(sample: String) : Exception("Corrupted LLM output: $sample")

    /**
     * The pantry first, then the fridge, each in the inventory's own order (latest scan
     * first, then as detected). The order steers a 2B model's dishes: a fixed one keeps
     * the first list the same however the fridge and the pantry were scanned and saved,
     * one after the other or both at once.
     */
    private fun promptOrder(ingredients: List<Ingredient>): List<Ingredient> =
        ingredients.sortedBy { ingredient ->
            when (ingredient.source) {
                IngredientSource.PANTRY -> 0
                IngredientSource.FRIDGE -> 1
                IngredientSource.MANUAL -> 2
            }
        }

    /**
     * A new seed alone isn't enough: at this temperature a 2B model keeps going back
     * to the same few dishes, and from the third list on it mostly repeats itself.
     * What steers it is the ingredients it is given, so every round after the first
     * (a new list, or more recipes for the same one) gets a different, deterministic
     * two thirds of the inventory (all of it when the inventory is small), in a
     * different order. Naming the dishes already shown and asking to avoid them
     * doesn't work: a 2B model copies them instead. The ones that come back anyway
     * are dropped (see [ListDishes]).
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

    // Any start is as good as another, but not 0 (for the runtime, seed 0 and seed 1 give
    // the same output). From FIRST_SEED the demo kitchen, the fridge and pantry of the demo
    // photos, first gets pasta al pomodoro, risotto ai funghi, insalata mista con tonno e
    // capperi and melanzane alla parmigiana: with LiteRT-LM 0.17.1 on the CPU and the list
    // prompt as it is, as another runtime or prompt draws other dishes from a seed. Gemma on
    // the GPU (other sampling, other arithmetic) draws others from the same seed: there it
    // starts from FIRST_GPU_SEED, whose first list for the demo kitchen is pasta al pomodoro e
    // mozzarella, risotto ai funghi, insalata mista con tonno e capperi and melanzane al forno
    // con ricotta e mozzarella (Pixel 7)
    private fun seedFor(round: Int, attempt: Int): Int = FIRST_SEED + round * MAX_ATTEMPTS + attempt

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
            // (RecipeTitleRules). So does asking for the array on one line: 40% fewer
            // tokens, but dishes like "Risotto ai ferri". Contorni aren't asked for: with
            // them, one of the four is an insalata mista nearly every time
            AppLanguage.IT -> """
                Ingredienti: $names.
                Proponi 4 ricette diverse tra loro (primi, secondi).
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
        // verbatim). Metric only. Short but with a clear schema. The amounts go by the
        // names as written, so the model doesn't spend tokens renaming the ingredients
        // ("Pomodori (datterini o pelati)") or adding others. The layout is left to the
        // model: asked for the JSON on one line, a 2B model often runs the steps into
        // a single one or leaves the object unfinished.
        // Not in the object, to save its tokens:
        //   "whySuitable": string (one short sentence why it fits),
        //   "whySuitable": string (una frase breve sul perché è adatta),
        return when (language) {
            AppLanguage.EN -> """
                Recipe: "${recipe.title}". Ingredients: $names.
                Give real metric amounts (g/ml, never tbsp/cups) and real cooking steps.
                Respond with ONLY a JSON object (no markdown).
                In "amounts" the amount, with its unit, of each ingredient listed, with its name written exactly as above:
                {
                  "amounts": {name: string},
                  "steps": [string] (4 to 8 real cooking steps),
                  "variants": [string] (up to 3 variations)
                }
            """
            AppLanguage.IT -> """
                Ricetta: "${recipe.title}". Ingredienti: $names.
                Indica quantità reali in unità metriche (g/ml) e veri passaggi di cottura.
                Rispondi SOLO con un oggetto JSON (niente markdown), con i testi in italiano.
                In "amounts" la quantità con l'unità di misura di ciascuno degli ingredienti elencati, con il nome scritto esattamente come sopra:
                {
                  "amounts": {nome: string},
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

    private companion object {
        const val TAG = "LlmRecipeGenerator"
        const val MAX_ATTEMPTS = 4

        const val FIRST_SEED = 10
        const val FIRST_GPU_SEED = 28

        // A new list with fewer dishes than this after dropping the odd ones gets another
        // attempt, which adds to it
        const val MIN_SOUND_RECIPES = 3
        const val GOLDEN_RATIO_32 = -0x61C88647 // 0x9E3779B9, spreads consecutive rounds apart

        // Share of the inventory each list after the first is built from, never below
        // MIN_LIST_INGREDIENTS (or the whole inventory, when it is smaller than that)
        const val LIST_SHARE_PERCENT = 65
        const val MIN_LIST_INGREDIENTS = 8

        // Share of the wait spent reading the prompt, before anything is written (Pixel 7
        // CPU: ~10 s of ~36 for a list, of ~70-90 for the longer details)
        const val LIST_READING_SHARE = 0.3f
        const val DETAILS_READING_SHARE = 0.15f

        // A recipe still being written never counts as finished, however long it gets
        const val UNFINISHED_RECIPE_CAP = 0.95f

        // While the model reads the prompt, the progress is a matter of time: updated this often
        const val PROGRESS_TICK_MILLIS = 100L

        // On GPU, this long without any new text means the generation is stuck
        // (degraded driver): the CPU is forced and the attempt repeated
        const val GPU_STALL_TIMEOUT_MS = 60_000L

        val garbageMarkers = listOf("<unused", "<pad>", "<unk>")

        // Basic pantry always available (as per the prompt): never "to buy". Nor are
        // onions, shallots, garlic, the usual herbs and broth (a stock cube will do),
        // which any kitchen has.
        // Both languages together: the inventory can mix them if the user changed
        // language between one scan and the next
        val PANTRY_STAPLES = setOf(
            "water", "salt", "pepper", "olive oil", "oil",
            "sugar", "flour", "bread", "butter", "vinegar",
            "onion", "onions", "shallot", "shallots", "garlic", "basil", "parsley", "broth", "stock",
            "acqua", "sale", "pepe", "olio d'oliva", "olio",
            "zucchero", "farina", "pane", "burro", "aceto",
            "cipolla", "cipolle", "scalogno", "scalogni", "aglio", "basilico", "prezzemolo", "brodo", "dado"
        )
    }
}

/**
 * The dishes of a list while a round writes it: those of the [earlier] rounds and
 * those this round has already [received] keep out a dish that comes back, under
 * the same name or another (see [SameDish]). The round is full at
 * [RECIPES_PER_LIST] new recipes.
 */
private class ListDishes(earlier: List<Recipe>, received: List<Recipe>) {
    private val titles = (earlier + received).mapTo(mutableListOf()) { it.title }

    /** New recipes in this round. */
    var added: Int = received.size
        private set

    val isFull: Boolean get() = added >= RECIPES_PER_LIST

    /** Adds the dish called [title], unless the list already has it. */
    fun add(title: String): Boolean {
        if (titles.any { SameDish.matches(it, title) }) return false
        titles += title
        added++
        return true
    }
}

/**
 * Progress of one answer (0..1), from what the model actually does: while it reads
 * the prompt it creeps towards [readingShare] at the pace reading usually takes on
 * this device; once it writes, the share written so far fills the rest.
 */
private class AnswerProgress(
    private val readingShare: Float,
    expectedReadingMillis: Long
) {
    private val startedAt = SystemClock.elapsedRealtime()

    // 90% of the reading share at the usual reading time, then ever slower
    private val k = ln(10.0) / expectedReadingMillis.coerceAtLeast(1)

    val elapsedMillis: Long get() = SystemClock.elapsedRealtime() - startedAt

    /** While the model reads the prompt. */
    fun reading(): Float = readingShare * (1 - exp(-k * elapsedMillis)).toFloat()

    /** Once it writes: [written] is the share of the answer written so far (0..1). */
    fun writing(written: Float): Float = readingShare + (1 - readingShare) * written.coerceIn(0f, MAX_WRITTEN)

    private companion object {
        // An answer longer than usual mustn't reach 100% before it ends
        const val MAX_WRITTEN = 0.98f
    }
}
