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
        @JsonNames("used_ingredients", "ingredients") val usedIngredients: List<JsonElement> = emptyList(),
        @JsonNames("missing_ingredients") val missingIngredients: List<JsonElement> = emptyList(),
        val steps: List<JsonElement> = emptyList(),
        val variants: List<JsonElement> = emptyList()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(rawOutput: String): List<Recipe> {
        // Il modello a volte emette un array [{...}], a volte una sequenza di
        // oggetti separati (ognuno nel suo fence ```json). Estraiamo TUTTI gli
        // oggetti bilanciati e teniamo quelli che sono ricette (hanno un title):
        // così entrambi i formati funzionano.
        val cleaned = stripFences(rawOutput)
        // 1° passaggio normale; se non trova oggetti ma l'output è escappato, de-escappa
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
                    difficulty = runCatching {
                        Difficulty.valueOf(dto.difficulty.trim().uppercase())
                    }.getOrDefault(Difficulty.EASY),
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
        @JsonNames("why_suitable", "reason") val whySuitable: String = "",
        val ingredients: List<JsonElement> = emptyList(),
        val steps: List<JsonElement> = emptyList(),
        val variants: List<JsonElement> = emptyList()
    )

    /** Completa la ricetta con quantità/steps/varianti dall'output del secondo stadio. */
    fun parseDetails(recipe: Recipe, rawOutput: String): Recipe {
        val cleaned = stripFences(rawOutput)
        val objectJson = extractBalanced(cleaned, '{', '}')
            ?: (if ("\\\"" in cleaned) extractBalanced(deEscape(cleaned), '{', '}') else null)
            ?: error("No JSON object found in LLM output")
        val dto = json.decodeFromString<DetailsDto>(objectJson)
        val steps = dto.steps.toCleanStrings()
        require(steps.isNotEmpty()) { "No steps in LLM output" }

        // Il 1B nella lista spesso omette le quantità: qui (compito focalizzato)
        // le produce in modo più affidabile — le fondiamo dove mancano.
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
     * Normalizza gli ingredienti: stringhe ("200 g Pasta") o oggetti
     * ({"name": "Pasta", "quantity": "200 g"}). Nome e quantità restano
     * separati: il nome serve al matching con l'inventario, la quantità alla UI.
     */
    private fun List<JsonElement>.toIngredients(): List<RecipeIngredient> =
        mapNotNull { element ->
            when (element) {
                // Formato principale: "Ingrediente - quantità" (leggero per il modello)
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }?.let { splitIngredient(it) }
                // Fallback: {"name": ..., "quantity": ...} se il modello usa oggetti
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
     * Separa nome e quantità e forza le unità metriche (g/ml). Il modello 1B
     * non rispetta il formato richiesto, quindi qui gestiamo entrambi i casi:
     *  - "Pasta - 200 g" (formato chiesto);
     *  - "250g Flour" / "2 tbsp Sugar" / "2 Bananas" (quantità in testa, comune).
     * tbsp/tsp/cup vengono convertiti in grammi/ml approssimati.
     */
    private fun splitIngredient(raw: String): RecipeIngredient {
        val s = raw.trim()
        // formato "Nome - quantità"
        ingredientSeparator.find(s)?.let { sep ->
            return RecipeIngredient(
                name = s.substring(0, sep.range.first).trim(),
                quantity = toMetric(s.substring(sep.range.last + 1).trim())
            )
        }
        // formato "quantità Nome" (es. "250g Flour", "2 tbsp Sugar")
        quantityPrefix.find(s)?.takeIf { it.range.first == 0 }?.let { pfx ->
            val name = s.substring(pfx.range.last + 1).trim()
            if (name.isNotEmpty()) return RecipeIngredient(name, toMetric(pfx.value.trim()))
        }
        return RecipeIngredient(name = s)
    }

    /** Converte tbsp/tsp/cup in g/ml approssimati; lascia invariato ciò che è già metrico. */
    private fun toMetric(quantity: String): String {
        val m = imperialUnit.find(quantity) ?: return quantity
        val amount = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return quantity
        val grams = when (m.groupValues[2].lowercase().removeSuffix("s")) {
            "tbsp", "tablespoon" -> amount * 15
            "tsp", "teaspoon" -> amount * 5
            "cup" -> amount * 240
            else -> return quantity
        }
        return "${grams.toInt()} g"
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

    /** Rimuove i fence markdown attorno al JSON. */
    private fun stripFences(raw: String): String =
        raw.replace("```json", "").replace("```", "")

    /**
     * De-escappa un output in cui il modello ha restituito il JSON con
     * virgolette/newline escappate (`\"title\"`, `\n`) — capita col 1B che
     * imita gli esempi del prompt: così il bracket matching riconosce le stringhe.
     */
    private fun deEscape(raw: String): String =
        raw.replace("\\n", "\n").replace("\\t", " ").replace("\\\"", "\"").replace("\\/", "/")

    /**
     * Estrae il primo blocco JSON bilanciato (array o oggetto) da [text] a
     * partire da [from], con bracket matching che rispetta stringhe ed escape.
     * Ritorna null se non trova una struttura bilanciata.
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
     * Tutti gli oggetti JSON top-level `{...}` nel testo, in ordine. Scorre
     * saltando oltre ogni oggetto completo, quindi gli oggetti annidati (es.
     * ingredienti dentro una ricetta) non vengono estratti separatamente.
     * Robusto a fence markdown, testo extra, array o oggetti-sciolti.
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
        "step", "description", "text", "variant", "name", "instruction", "detail"
    )
    private val ingredientNameKeys = listOf("name", "ingredient", "item")
    private val ingredientQtyKeys = listOf("quantity", "amount", "qty", "measure")
    private val ingredientSeparator = Regex("""\s[-–:]\s""")
    // Quantità in testa: numero (anche frazione) + eventuale unità attaccata o staccata
    private val quantityPrefix =
        Regex("""^\d+[\d/.,]*\s*(g|kg|ml|l|tbsps?|tsps?|tablespoons?|teaspoons?|cups?|pcs?|pieces?|cans?|packs?|cloves?|slices?|bunch(?:es)?)?\b""", RegexOption.IGNORE_CASE)
    private val imperialUnit =
        Regex("""([\d.,]+)\s*(tbsps?|tsps?|tablespoons?|teaspoons?|cups?)""", RegexOption.IGNORE_CASE)

    private val leadingNumberRegex = Regex("""^\s*\d+[.)]\s*""")
}
