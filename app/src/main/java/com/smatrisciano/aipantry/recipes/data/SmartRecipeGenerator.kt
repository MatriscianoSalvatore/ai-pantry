package com.smatrisciano.aipantry.recipes.data

import android.util.Log
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.recipes.domain.RecipeGenerator
import com.smatrisciano.aipantry.recipes.domain.models.Recipe

/**
 * Usa il modello LLM attivo se scaricato, altrimenti degrada al demo mode.
 * Se l'inferenza reale fallisce (OOM, output non parsabile), la demo resta in piedi.
 */
class SmartRecipeGenerator(
    private val modelRepository: ModelRepository,
    private val llm: LlmRecipeGenerator,
    private val demo: DemoRecipeGenerator
) : RecipeGenerator {

    // L'engine che ha prodotto l'ultimo risultato: la UI deve mostrare cosa ha
    // davvero generato le ricette, non cosa ci ha provato per primo
    @Volatile
    private var lastUsedEngine: String? = null

    override val engineName: String
        get() = lastUsedEngine
            ?: if (modelRepository.readyActiveModel() != null) llm.engineName else demo.engineName

    override suspend fun generate(
        ingredients: List<Ingredient>,
        onProgress: (String) -> Unit
    ): List<Recipe> {
        if (modelRepository.readyActiveModel() != null) {
            runCatching { llm.generate(ingredients, onProgress) }
                .onSuccess {
                    if (it.isNotEmpty()) {
                        lastUsedEngine = llm.engineName
                        return it
                    }
                }
                .onFailure { Log.w(TAG, "LLM inference failed, falling back to demo", it) }
        }
        lastUsedEngine = demo.engineName
        return demo.generate(ingredients, onProgress)
    }

    private companion object {
        const val TAG = "SmartRecipeGenerator"
    }
}
