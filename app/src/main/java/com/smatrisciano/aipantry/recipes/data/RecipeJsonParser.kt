package com.smatrisciano.aipantry.recipes.data

import com.smatrisciano.aipantry.recipes.domain.models.Difficulty
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

/**
 * Estrae e deserializza l'array JSON di ricette dall'output dell'LLM.
 * Tollerante per costruzione: testo extra attorno al JSON, campi mancanti o
 * malformati su singole ricette (l'elemento invalido viene scartato, non
 * l'intero array — gli LLM piccoli sbagliano spesso un campo su una ricetta).
 */
@OptIn(ExperimentalSerializationApi::class)
object RecipeJsonParser {

    // Alias snake_case: gli LLM piccoli non rispettano sempre il camelCase richiesto.
    // steps/variants come JsonElement: il modello a volte emette oggetti
    // ([{"step": "..."}]) invece di stringhe.
    @Serializable
    private data class RecipeDto(
        val title: String = "",
        @JsonNames("why_suitable", "reason") val whySuitable: String = "",
        @JsonNames("prep_time_minutes", "prepTime", "prep_time") val prepTimeMinutes: Int = 20,
        val difficulty: String = "EASY",
        @JsonNames("used_ingredients", "ingredients") val usedIngredients: List<String> = emptyList(),
        @JsonNames("missing_ingredients") val missingIngredients: List<String> = emptyList(),
        val steps: List<JsonElement> = emptyList(),
        val variants: List<JsonElement> = emptyList()
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
            // steps facoltativi: la lista arriva senza istruzioni (generate on-demand)
            .filter { it.title.isNotBlank() }
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
                    steps = dto.steps.toCleanStrings(),
                    variants = dto.variants.toCleanStrings()
                )
            }

        require(recipes.isNotEmpty()) { "No valid recipes in LLM output" }
        return recipes
    }

    @Serializable
    private data class DetailsDto(
        val steps: List<JsonElement> = emptyList(),
        val variants: List<JsonElement> = emptyList()
    )

    /** Completa la ricetta con steps/varianti dall'output del secondo stadio. */
    fun parseDetails(recipe: Recipe, rawOutput: String): Recipe {
        val start = rawOutput.indexOf('{')
        val end = rawOutput.lastIndexOf('}')
        require(start in 0 until end) { "No JSON object found in LLM output" }
        val dto = json.decodeFromString<DetailsDto>(rawOutput.substring(start, end + 1))
        val steps = dto.steps.toCleanStrings()
        require(steps.isNotEmpty()) { "No steps in LLM output" }
        return recipe.copy(
            steps = steps,
            variants = dto.variants.toCleanStrings()
        )
    }

    /**
     * Normalizza gli elementi di steps/variants: stringhe dirette, oppure
     * oggetti tipo {"step": "..."} da cui estrarre il testo. La UI numera già
     * i passi, quindi via anche l'eventuale "1. " prodotto dal modello.
     */
    private fun List<JsonElement>.toCleanStrings(): List<String> =
        mapNotNull { element -> element.extractText() }
            .map { it.replace(leadingNumberRegex, "").trim() }
            .filter { it.isNotBlank() }

    private fun JsonElement.extractText(): String? = when (this) {
        is JsonPrimitive -> if (isString) content else content.takeIf { it.isNotBlank() }
        is JsonObject -> textKeys.firstNotNullOfOrNull { key ->
            (get(key) as? JsonPrimitive)?.content
        } ?: values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        else -> null
    }

    private val textKeys = listOf(
        "step", "description", "text", "variant", "name", "instruction", "detail"
    )

    private val leadingNumberRegex = Regex("""^\s*\d+[.)]\s*""")
}
