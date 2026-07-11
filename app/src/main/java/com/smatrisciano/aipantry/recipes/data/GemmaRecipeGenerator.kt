package com.smatrisciano.aipantry.recipes.data

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.recipes.domain.RecipeGenerator
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Generazione ricette on-device con Gemma 3n eseguito via LiteRT (MediaPipe GenAI).
 *
 * Il modello .task va copiato sul device in [modelFile] (vedi README). Se assente,
 * lo [SmartRecipeGenerator] usa il fallback demo.
 */
class GemmaRecipeGenerator(
    private val context: Context
) : RecipeGenerator {

    override val engineName = "Gemma 3n · LiteRT"

    private val llm: LlmInference by lazy {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile(context).absolutePath)
            .setMaxTokens(2048)
            .build()
        LlmInference.createFromOptions(context, options)
    }

    override suspend fun generate(
        ingredients: List<Ingredient>,
        onProgress: (String) -> Unit
    ): List<Recipe> = withContext(Dispatchers.Default) {
        onProgress("Loading Gemma 3n on-device…")
        val prompt = buildPrompt(ingredients)
        onProgress("Generating recipes with ${ingredients.size} ingredients…")
        val rawOutput = llm.generateResponse(prompt)
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

            Respond with ONLY a JSON array (no markdown, no extra text) where each recipe has:
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

    companion object {
        fun modelFile(context: Context): File {
            val pushed = File("/data/local/tmp/llm/gemma-3n.task")
            if (pushed.exists()) return pushed
            return File(context.filesDir, "models/gemma-3n.task")
        }

        fun isModelAvailable(context: Context): Boolean = modelFile(context).exists()
    }
}
