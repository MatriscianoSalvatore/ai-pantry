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
 * Generazione ricette on-device con il modello attivo (Gemma 4 E2B) via LiteRT-LM.
 * Due stadi per minimizzare i token generati: lista leggera subito,
 * istruzioni on-demand quando l'utente apre la ricetta.
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
        val recipes = withRetry { attempt ->
            if (attempt > 0) onProgress(GenerationProgress.Retrying)
            val rawOutput = generateChecked(model, buildListPrompt(ingredients, language))
            Log.d(TAG, "raw list output: $rawOutput")
            RecipeJsonParser.parse(rawOutput)
        }

        // I missing non li decide il modello (inaffidabile): tutto ciò che una
        // ricetta usa e non è nell'inventario è, per definizione, da comprare
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

        // Le ricette per cui manca qualcosa vanno in fondo,
        // a pari mancanze resta l'ordine di rilevanza del modello
        normalized.sortedBy { it.missingIngredients.size }
    }

    override suspend fun generateDetails(
        recipe: Recipe,
        ingredients: List<Ingredient>
    ): Recipe = withContext(Dispatchers.Default) {
        val model = requireNotNull(modelRepository.readyActiveModel()) { "No LLM model available" }
        withRetry {
            val rawOutput = generateChecked(model, buildDetailsPrompt(recipe, AppLanguage.current()))
            Log.d(TAG, "raw details output: $rawOutput")
            RecipeJsonParser.parseDetails(recipe, rawOutput)
        }
    }

    /**
     * Inferenza con due protezioni:
     *  - watchdog: su un driver GPU degradato `sendMessage` può bloccarsi
     *    per sempre (chiamata nativa non interrompibile). La eseguiamo in un job
     *    separato e facciamo timeout sull'await: allo scadere si forza la CPU e
     *    si ritenta, invece di lasciare la UI a caricare all'infinito;
     *  - anti-corruzione: se il modello emette token spazzatura (<pad>,
     *    <unused…>) si ricrea su CPU e si ritenta.
     */
    private suspend fun generateChecked(
        model: com.smatrisciano.aipantry.core.data.ai.LlmModel,
        prompt: String
    ): String {
        val file = modelRepository.modelFile(model)

        // Il watchdog serve SOLO a smascherare l'hang della GPU: la CPU non si
        // pianta mai, è solo lenta, quindi va lasciata completare senza limite
        // (altrimenti una generazione CPU lunga verrebbe uccisa e ritentata).
        val rawOutput = if (engineHolder.currentBackendIsGpu()) {
            // Job scollegato: se la GPU si pianta, il thread nativo resta bloccato
            // (non killabile) ma la coroutine chiamante prosegue e recupera su CPU
            val generation = watchdogScope.async {
                engineHolder.createConversation(model, file, temperature = 0.5, topK = 25, topP = 0.9).use { conversation ->
                    conversation.sendMessage(Contents.of(Content.Text(prompt))).text()
                }
            }
            // Oltre all'hang, la GPU può fallire con eccezione immediata (es.
            // OpenCL assente sull'emulatore): stessa sorte del timeout
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
            engineHolder.createConversation(model, file, temperature = 0.5, topK = 40, topP = 0.9).use { conversation ->
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
     * Con un modello 1/2B l'output ogni tanto non rispetta il formato:
     * si ritenta in silenzio prima di far arrivare l'errore alla UI.
     */
    private inline fun <T> withRetry(attempts: Int = 4, block: (attempt: Int) -> T): T {
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

    // Chiavi JSON e valori di difficulty restano in inglese in entrambe le
    // lingue: sono il contratto col parser, si traducono solo i contenuti
    private fun buildListPrompt(ingredients: List<Ingredient>, language: AppLanguage): String {
        val names = ingredients.joinToString(", ") { it.name }
        // Prompt breve: meno token in input = meno prefill = più veloce su CPU
        return when (language) {
            AppLanguage.EN -> """
                Ingredients: $names.
                Output ONLY a JSON array of 4 recipes, each exactly:
                {"title":string,"prepTimeMinutes":int,"difficulty":"EASY"|"MEDIUM"|"HARD","usedIngredients":[names]}
                No text, no amounts, no steps.
            """
            AppLanguage.IT -> """
                Ingredienti: $names.
                Rispondi SOLO con un array JSON di 4 ricette, ognuna esattamente:
                {"title":string,"prepTimeMinutes":int,"difficulty":"EASY"|"MEDIUM"|"HARD","usedIngredients":[nomi]}
                Titoli e ingredienti in italiano. Niente testo, niente quantità, niente passaggi.
            """
        }.trimIndent()
    }

    private fun buildDetailsPrompt(recipe: Recipe, language: AppLanguage): String {
        val names = (recipe.usedIngredients + recipe.missingIngredients).joinToString { it.name }
        // Descrizioni dei campi (non valori di esempio: il 1B li copierebbe pari
        // pari). Metric only. Corto ma con schema chiaro.
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

    /** Match a parola intera: "pepe" non deve coprire "peperoni", né "sale" "salame". */
    private fun isPantryStaple(name: String): Boolean =
        PANTRY_STAPLES.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(name) }

    /** "2 tbsp Olive Oil" / "2 cucchiai d'olio" → "olive oil" / "olio": via quantità e unità. */
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

    // Scope scollegato per il watchdog: i job che vi girano possono restare
    // bloccati su una chiamata GPU nativa senza trascinarsi la coroutine chiamante
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TAG = "LlmRecipeGenerator"

        // Oltre questo tempo la generazione è considerata bloccata (GPU degradata):
        // si forza la CPU e si ritenta
        const val GENERATION_TIMEOUT_MS = 75_000L

        val garbageMarkers = listOf("<unused", "<pad>", "<unk>")

        // Dispensa di base sempre disponibile (come da prompt): mai "da comprare".
        // Entrambe le lingue insieme: l'inventario può mescolarle se l'utente
        // ha cambiato lingua tra una scansione e l'altra
        val PANTRY_STAPLES = setOf(
            "water", "salt", "pepper", "olive oil", "oil",
            "sugar", "flour", "bread", "butter", "vinegar",
            "acqua", "sale", "pepe", "olio d'oliva", "olio",
            "zucchero", "farina", "pane", "burro", "aceto"
        )
    }
}
