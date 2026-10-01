package com.smatrisciano.aipantry.recipes.data

import com.smatrisciano.aipantry.recipes.domain.models.Difficulty
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import com.smatrisciano.aipantry.recipes.domain.models.RecipeIngredient
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Extracts and deserialises the JSON array of recipes from the LLM output.
 * Tolerant by design: extra text around the JSON, missing or malformed fields
 * on single recipes (the invalid element is dropped, not the whole array:
 * small LLMs often get one field wrong on one recipe).
 */
@OptIn(ExperimentalSerializationApi::class)
object RecipeJsonParser {

    // snake_case aliases: small LLMs don't always respect the requested camelCase.
    // Italian aliases: with the Italian prompt the model sometimes translates the keys too.
    // steps/variants as JsonElement: the model sometimes emits objects
    // ([{"step": "..."}]) instead of strings.
    @Serializable
    private data class RecipeDto(
        @JsonNames("titolo") val title: String = "",
        @JsonNames("why_suitable", "reason", "perche", "perché") val whySuitable: String = "",
        @JsonNames("prep_time_minutes", "prepTime", "prep_time", "tempo") val prepTimeMinutes: Int = 20,
        @JsonNames("difficolta", "difficoltà") val difficulty: String = "EASY",
        @JsonNames("used_ingredients", "ingredients", "ingredienti") val usedIngredients: List<JsonElement> = emptyList(),
        @JsonNames("missing_ingredients") val missingIngredients: List<JsonElement> = emptyList(),
        @JsonNames("passaggi", "procedimento") val steps: List<JsonElement> = emptyList(),
        @JsonNames("varianti") val variants: List<JsonElement> = emptyList()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(rawOutput: String): List<Recipe> {
        // The model sometimes emits an array [{...}], sometimes a sequence of
        // separate objects (each in its own ```json fence). Extract ALL the balanced
        // objects and keep the ones that are recipes (they have a title): that way
        // both formats work.
        val cleaned = stripFences(rawOutput)
        // 1st pass as is; if it finds no objects but the output is escaped, unescape it
        val objects = extractObjects(cleaned)
            .ifEmpty { if ("\\\"" in cleaned) extractObjects(deEscape(cleaned)) else emptyList() }
        val recipes = objects
            .mapNotNull { obj -> runCatching { json.decodeFromString<RecipeDto>(obj) }.getOrNull() }
            .filter { it.title.isNotBlank() }
            .map { dto ->
                Recipe(
                    title = dto.title,
                    whySuitable = dto.whySuitable,
                    prepTimeMinutes = dto.prepTimeMinutes.coerceIn(1, 600),
                    difficulty = parseDifficulty(dto.difficulty),
                    usedIngredients = dto.usedIngredients.toIngredients(),
                    missingIngredients = dto.missingIngredients.toIngredients(),
                    steps = dto.steps.toCleanStrings(),
                    variants = dto.variants.toCleanStrings()
                )
            }

        require(recipes.isNotEmpty()) { "No valid recipes in LLM output" }
        return recipes
    }

    @Serializable
    private data class DetailsDto(
        @JsonNames("why_suitable", "reason", "perche", "perché") val whySuitable: String = "",
        @JsonNames("ingredienti") val ingredients: List<JsonElement> = emptyList(),
        @JsonNames("passaggi", "procedimento") val steps: List<JsonElement> = emptyList(),
        @JsonNames("varianti") val variants: List<JsonElement> = emptyList()
    )

    /** EASY/MEDIUM/HARD as requested, but tolerates the difficulty translated into Italian. */
    private fun parseDifficulty(raw: String): Difficulty =
        when (raw.trim().lowercase()) {
            "facile" -> Difficulty.EASY
            "media", "medio" -> Difficulty.MEDIUM
            "difficile" -> Difficulty.HARD
            else -> runCatching { Difficulty.valueOf(raw.trim().uppercase()) }.getOrDefault(Difficulty.EASY)
        }

    /** Completes the recipe with quantities/steps/variants from the second stage's output. */
    fun parseDetails(recipe: Recipe, rawOutput: String): Recipe {
        val cleaned = stripFences(rawOutput)
        val objectJson = extractBalanced(cleaned, '{', '}')
            ?: (if ("\\\"" in cleaned) extractBalanced(deEscape(cleaned), '{', '}') else null)
            ?: error("No JSON object found in LLM output")
        val dto = json.decodeFromString<DetailsDto>(objectJson)
        val steps = dto.steps.toCleanStrings()
        require(steps.isNotEmpty()) { "No steps in LLM output" }

        // In the list the small model often omits quantities: here (a focused task)
        // it produces them more reliably, so merge them where missing.
        val amounts = dto.ingredients.toIngredients()
        val enriched = recipe.usedIngredients.map { ing ->
            if (ing.quantity.isNotBlank()) return@map ing
            val match = amounts.firstOrNull {
                it.quantity.isNotBlank() && it.name.lowercase().let { n ->
                    n == ing.name.lowercase() || n in ing.name.lowercase() || ing.name.lowercase() in n
                }
            }
            if (match != null) ing.copy(quantity = match.quantity) else ing
        }

        return recipe.copy(
            whySuitable = dto.whySuitable.ifBlank { recipe.whySuitable },
            usedIngredients = enriched,
            steps = steps,
            variants = dto.variants.toCleanStrings()
        )
    }

    /**
     * Normalises the ingredients: strings ("200 g Pasta") or objects
     * ({"name": "Pasta", "quantity": "200 g"}). Name and quantity stay
     * separate: the name is for matching against the inventory, the quantity for the UI.
     */
    private fun List<JsonElement>.toIngredients(): List<RecipeIngredient> =
        mapNotNull { element ->
            when (element) {
                // Main format: "Ingredient - quantity" (light for the model)
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }?.let { splitIngredient(it) }
                // Fallback: {"name": ..., "quantity": ...} if the model uses objects
                is JsonObject -> {
                    val name = ingredientNameKeys.firstNotNullOfOrNull { (element[it] as? JsonPrimitive)?.content }
                    val qty = ingredientQtyKeys.firstNotNullOfOrNull { (element[it] as? JsonPrimitive)?.content }
                    name?.takeIf { it.isNotBlank() }
                        ?.let { RecipeIngredient(it.trim(), toMetric(qty?.trim().orEmpty())) }
                }
                else -> null
            }
        }.distinctBy { it.name.lowercase() }

    /**
     * Splits name and quantity and forces metric units (g/ml). The small model
     * doesn't respect the requested format, so both cases are handled here:
     *  - "Pasta - 200 g" (the requested format);
     *  - "250g Flour" / "2 tbsp Sugar" / "2 Bananas" (quantity first, common).
     * tbsp/tsp/cup are converted to approximate grams/ml.
     */
    private fun splitIngredient(raw: String): RecipeIngredient {
        val s = raw.trim()
        // "Name - quantity" format
        ingredientSeparator.find(s)?.let { sep ->
            return RecipeIngredient(
                name = s.substring(0, sep.range.first).trim(),
                quantity = toMetric(s.substring(sep.range.last + 1).trim())
            )
        }
        // "quantity Name" format (e.g. "250g Flour", "2 tbsp Sugar")
        quantityPrefix.find(s)?.takeIf { it.range.first == 0 }?.let { pfx ->
            val name = s.substring(pfx.range.last + 1).trim()
            if (name.isNotEmpty()) return RecipeIngredient(name, toMetric(pfx.value.trim()))
        }
        return RecipeIngredient(name = s)
    }

    /** Converts tbsp/tsp/cup (and cucchiai/cucchiaini/tazze) to approximate g/ml; leaves anything already metric unchanged. */
    private fun toMetric(quantity: String): String {
        val m = imperialUnit.find(quantity) ?: return quantity
        val amount = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return quantity
        val unit = m.groupValues[2].lowercase()
        val grams = when {
            // "cucchiaino" before "cucchiai": the latter is a prefix of the former
            unit.startsWith("tsp") || unit.startsWith("teaspoon") || unit.startsWith("cucchiain") -> amount * 5
            unit.startsWith("tbsp") || unit.startsWith("tablespoon") || unit.startsWith("cucchiai") -> amount * 15
            unit.startsWith("cup") || unit.startsWith("tazz") -> amount * 240
            else -> return quantity
        }
        return "${grams.toInt()} g"
    }

    /**
     * Normalises the steps/variants items: plain strings, or objects like
     * {"step": "..."} to pull the text from. The UI already numbers the steps,
     * so any "1. " produced by the model goes too.
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

    /** Removes the markdown fences around the JSON. */
    private fun stripFences(raw: String): String =
        raw.replace("```json", "").replace("```", "")

    /**
     * Unescapes an output where the model returned the JSON with escaped
     * quotes/newlines (`\"title\"`, `\n`): it happens when the small model imitates
     * the prompt's examples. This way bracket matching recognises the strings.
     */
    private fun deEscape(raw: String): String =
        raw.replace("\\n", "\n").replace("\\t", " ").replace("\\\"", "\"").replace("\\/", "/")

    /**
     * Extracts the first balanced JSON block (array or object) from [text]
     * starting at [from], with bracket matching that respects strings and escapes.
     * Returns null if no balanced structure is found.
     */
    private fun extractBalanced(text: String, open: Char, close: Char, from: Int = 0): String? {
        val start = text.indexOf(open, from)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> {}
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * All the top-level JSON objects `{...}` in the text, in order. It skips past
     * every complete object, so nested objects (e.g. ingredients inside a recipe)
     * aren't extracted separately. Robust to markdown fences, extra text, arrays
     * or loose objects.
     */
    private fun extractObjects(text: String): List<String> {
        val objects = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val obj = extractBalanced(text, '{', '}', cursor) ?: break
            objects += obj
            cursor = text.indexOf(obj, cursor) + obj.length
        }
        return objects
    }

    private val textKeys = listOf(
        "step", "description", "text", "variant", "name", "instruction", "detail",
        "passaggio", "descrizione", "testo", "variante", "nome"
    )
    private val ingredientNameKeys = listOf("name", "ingredient", "item", "nome", "ingrediente")
    private val ingredientQtyKeys = listOf("quantity", "amount", "qty", "measure", "quantita", "quantità", "dose")
    private val ingredientSeparator = Regex("""\s[-–:]\s""")
    // Quantity first: a number (fractions too) + an optional unit, attached or spaced
    private val quantityPrefix =
        Regex(
            """^\d+[\d/.,]*\s*(g|kg|ml|l|tbsps?|tsps?|tablespoons?|teaspoons?|cups?|pcs?|pieces?|cans?|packs?|cloves?|slices?|bunch(?:es)?|""" +
                """cucchia(?:ini|ino|io|i)|tazz[ae]|pz|pezz[io]|spicch[io]|fett[ae]|lattin[ae]|confezion[ei]|mazz[oi])?\b""",
            RegexOption.IGNORE_CASE
        )
    private val imperialUnit =
        Regex("""([\d.,]+)\s*(tbsps?|tsps?|tablespoons?|teaspoons?|cups?|cucchia(?:ini|ino|io|i)|tazz[ae])""", RegexOption.IGNORE_CASE)

    private val leadingNumberRegex = Regex("""^\s*\d+[.)]\s*""")
}
