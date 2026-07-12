package com.smatrisciano.aipantry.recipes.data

import com.smatrisciano.aipantry.recipes.domain.models.Difficulty
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

/**
 * Estrae e deserializza l'array JSON di ricette dall'output dell'LLM.
 * Tollerante per costruzione: testo extra attorno al JSON, campi mancanti o
 * malformati su singole ricette (l'elemento invalido viene scartato, non
 * l'intero array — gli LLM piccoli sbagliano spesso un campo su una ricetta).
 */
object RecipeJsonParser {

    @Serializable
    private data class RecipeDto(
        val title: String = "",
        val whySuitable: String = "",
        val prepTimeMinutes: Int = 20,
        val difficulty: String = "EASY",
        val usedIngredients: List<String> = emptyList(),
        val missingIngredients: List<String> = emptyList(),
        val steps: List<String> = emptyList(),
        val variants: List<String> = emptyList()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(rawOutput: String): List<Recipe> {
        val start = rawOutput.indexOf('[')
        val end = rawOutput.lastIndexOf(']')
        require(start in 0 until end) { "No JSON array found in LLM output" }

        val recipes = json.parseToJsonElement(rawOutput.substring(start, end + 1))
            .jsonArray
            .mapNotNull { element ->
                runCatching { json.decodeFromJsonElement<RecipeDto>(element) }.getOrNull()
            }
            .filter { it.title.isNotBlank() && it.steps.isNotEmpty() }
            .map { dto ->
                Recipe(
                    title = dto.title,
                    whySuitable = dto.whySuitable,
                    prepTimeMinutes = dto.prepTimeMinutes.coerceIn(1, 600),
                    difficulty = runCatching {
                        Difficulty.valueOf(dto.difficulty.trim().uppercase())
                    }.getOrDefault(Difficulty.EASY),
                    usedIngredients = dto.usedIngredients,
                    missingIngredients = dto.missingIngredients,
                    // la UI numera già i passi: via l'eventuale "1. " dell'LLM
                    steps = dto.steps.map { it.replace(leadingNumberRegex, "") },
                    variants = dto.variants
                )
            }

        require(recipes.isNotEmpty()) { "No valid recipes in LLM output" }
        return recipes
    }

    private val leadingNumberRegex = Regex("""^\s*\d+[.)]\s*""")
}
