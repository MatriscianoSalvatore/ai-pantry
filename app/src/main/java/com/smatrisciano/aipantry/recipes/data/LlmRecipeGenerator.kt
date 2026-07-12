package com.smatrisciano.aipantry.recipes.data

import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.smatrisciano.aipantry.core.data.ai.LlmEngineHolder
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.recipes.domain.RecipeGenerator
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generazione ricette on-device con il modello attivo (Gemma 3n / Qwen) via LiteRT.
 * Richiede un modello scaricato: vedi [ModelRepository].
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
        val engine = engineHolder.acquire(model, modelRepository.modelFile(model))

        onProgress("Generating recipes with ${ingredients.size} ingredients…")
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(0.6f)
            .setTopK(40)
            .build()
        val rawOutput = LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
            session.addQueryChunk(buildPrompt(ingredients))
            session.generateResponse()
        }

        onProgress("Parsing model output…")
        RecipeJsonParser.parse(rawOutput)
    }

    private fun buildPrompt(ingredients: List<Ingredient>): String {
        val inventoryList = ingredients.joinToString("\n") { "- ${it.name} (${it.quantity})" }
        return """
            You are a cooking assistant. Generate recipes using ONLY these available ingredients
            (plus water, salt, pepper, olive oil which are always available).
            Prefer recipes that maximize ingredient usage and minimize waste.
            Order recipes by relevance.

            Available ingredients:
            $inventoryList

            Respond with ONLY a JSON array (no markdown, no extra text).
            Every recipe object MUST contain ALL of these fields (steps is mandatory, never omit it):
            {
              "title": string,
              "whySuitable": string (why this recipe fits the available ingredients),
              "prepTimeMinutes": int,
              "difficulty": "EASY" | "MEDIUM" | "HARD",
              "usedIngredients": [string],
              "missingIngredients": [string] (optional, keep short),
              "steps": [string] (detailed step-by-step instructions),
              "variants": [string] (possible variations)
            }

            Generate exactly 5 recipes.
        """.trimIndent()
    }
}
