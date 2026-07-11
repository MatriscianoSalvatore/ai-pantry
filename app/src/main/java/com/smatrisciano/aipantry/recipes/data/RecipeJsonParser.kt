package com.smatrisciano.aipantry.recipes.data

import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import kotlinx.serialization.json.Json

/**
 * Estrae e deserializza l'array JSON di ricette dall'output dell'LLM,
 * tollerando testo extra prima/dopo (es. fence markdown ```json).
 */
object RecipeJsonParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(rawOutput: String): List<Recipe> {
        val start = rawOutput.indexOf('[')
        val end = rawOutput.lastIndexOf(']')
        require(start in 0 until end) { "No JSON array found in LLM output" }
        return json.decodeFromString<List<Recipe>>(rawOutput.substring(start, end + 1))
    }
}
