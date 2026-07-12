package com.smatrisciano.aipantry.recipes.data

import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.smatrisciano.aipantry.core.data.ai.LlmEngineHolder
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.recipes.domain.RecipeGenerator
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generazione ricette on-device con il modello attivo (Gemma 3n) via LiteRT.
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
        onProgress: (String) -> Unit
    ): List<Recipe> = withContext(Dispatchers.Default) {
        val model = requireNotNull(modelRepository.readyActiveModel()) { "No LLM model available" }
        onProgress("Loading ${model.displayName} on-device…")
        engineHolder.acquire(model, modelRepository.modelFile(model))

        onProgress("Generating recipes with ${ingredients.size} ingredients…")
        val recipes = withRetry { attempt ->
            if (attempt > 0) onProgress("Output not parseable, retrying…")
            val rawOutput = generateChecked(model, buildListPrompt(ingredients))
            Log.d(TAG, "raw list output: $rawOutput")
            RecipeJsonParser.parse(rawOutput)
        }

        // I missing non li decide il modello (inaffidabile): tutto ciò che una
        // ricetta usa e non è nell'inventario è, per definizione, da comprare
        val available = ingredients.map { it.name.lowercase() }
        val normalized = recipes.map { recipe ->
            val (owned, toBuy) = recipe.usedIngredients.partition { used ->
                val u = normalizeIngredientName(used)
                PANTRY_STAPLES.any { it in u } || available.any { it in u || u in it }
            }
            val missing = (recipe.missingIngredients + toBuy)
                .filterNot { m -> PANTRY_STAPLES.any { it in normalizeIngredientName(m) } }
                .distinctBy { normalizeIngredientName(it) }
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
            val rawOutput = generateChecked(model, buildDetailsPrompt(recipe))
            Log.d(TAG, "raw details output: $rawOutput")
            RecipeJsonParser.parseDetails(recipe, rawOutput)
        }
    }

    /**
     * Inferenza con controllo anti-corruzione: su alcuni driver GPU il modello
     * quantizzato emette token spazzatura (<pad>, <unused…>) — in quel caso il
     * motore viene ricreato su CPU e la chiamata fallisce per far scattare il retry.
     */
    private fun generateChecked(
        model: com.smatrisciano.aipantry.core.data.ai.LlmModel,
        prompt: String
    ): String {
        val engine = engineHolder.acquire(model, modelRepository.modelFile(model))
        val rawOutput = LlmInferenceSession.createFromOptions(engine, sessionOptions()).use { session ->
            session.addQueryChunk(prompt)
            session.generateResponse()
        }
        if (garbageMarkers.any { it in rawOutput }) {
            Log.w(TAG, "corrupted output detected: ${rawOutput.take(120)}")
            check(engineHolder.reportCorruptedOutput(model)) {
                "The AI model is producing corrupted output on this device"
            }
            error("Corrupted LLM output, engine switched to CPU")
        }
        return rawOutput
    }

    /**
     * Con un modello 1B l'output ogni tanto non rispetta il formato:
     * si ritenta in silenzio prima di far arrivare l'errore alla UI.
     */
    private inline fun <T> withRetry(attempts: Int = 3, block: (attempt: Int) -> T): T {
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

    // Temperatura bassa: più aderenza al formato JSON richiesto
    private fun sessionOptions() = LlmInferenceSession.LlmInferenceSessionOptions.builder()
        .setTemperature(0.4f)
        .setTopK(40)
        .build()

    private fun buildListPrompt(ingredients: List<Ingredient>): String {
        val inventoryList = ingredients.joinToString("\n") { "- ${it.name} (${it.quantity})" }
        return """
            You are a cooking assistant. Suggest recipes using ONLY these available ingredients
            (plus pantry staples which are always available: water, salt, pepper, olive oil,
            sugar, flour, bread, butter, vinegar).
            Prefer recipes that maximize ingredient usage and minimize waste. Order by relevance.

            Available ingredients:
            $inventoryList

            Respond with ONLY a JSON array (no markdown, no extra text) of exactly 5 recipes.
            Each recipe object has EXACTLY these fields and nothing else:
            {
              "title": string,
              "whySuitable": string (one short sentence),
              "prepTimeMinutes": int,
              "difficulty": "EASY" | "MEDIUM" | "HARD",
              "usedIngredients": [string] (plain ingredient names, NO quantities),
              "missingIngredients": [string] (ALWAYS present: ingredients the cook still needs to buy, [] if none)
            }
            Do NOT include cooking steps.
        """.trimIndent()
    }

    private fun buildDetailsPrompt(recipe: Recipe): String = """
        Recipe: "${recipe.title}".
        Ingredients to use: ${recipe.usedIngredients.joinToString()}
        (plus water, salt, pepper, olive oil${
        if (recipe.missingIngredients.isNotEmpty()) ", and: " + recipe.missingIngredients.joinToString() else ""
    }).

        Respond with ONLY a JSON object (no markdown, no extra text):
        {
          "steps": [string] (4 to 8 clear step-by-step cooking instructions),
          "variants": [string] (up to 3 possible variations)
        }
    """.trimIndent()

    /** "2 tbsp Olive Oil" → "olive oil": via quantità e unità di misura. */
    private fun normalizeIngredientName(raw: String): String =
        raw.lowercase()
            .replace(Regex("""^[\d\s/.,½¼¾()-]+"""), "")
            .replace(
                Regex("""^(tbsps?|tsps?|tablespoons?|teaspoons?|cups?|grams?|g|kg|ml|l|oz|lbs?|pcs?|pieces?|cloves?|slices?|cans?|packs?|bunch(es)?)\s+(of\s+)?"""),
                ""
            )
            .trim()

    private companion object {
        const val TAG = "LlmRecipeGenerator"

        val garbageMarkers = listOf("<unused", "<pad>", "<unk>")

        // Dispensa di base sempre disponibile (come da prompt): mai "da comprare"
        val PANTRY_STAPLES = setOf(
            "water", "salt", "pepper", "olive oil", "oil",
            "sugar", "flour", "bread", "butter", "vinegar"
        )
    }
}
