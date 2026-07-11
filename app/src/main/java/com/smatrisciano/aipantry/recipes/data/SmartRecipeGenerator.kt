package com.smatrisciano.aipantry.recipes.data

import android.content.Context
import android.util.Log
import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.recipes.domain.RecipeGenerator
import com.smatrisciano.aipantry.recipes.domain.models.Recipe

/**
 * Usa Gemma 3n se il modello è presente sul device, altrimenti degrada al demo mode.
 * Se l'inferenza reale fallisce (OOM, output non parsabile), la demo resta in piedi.
 */
class SmartRecipeGenerator(
    private val context: Context,
    private val gemma: GemmaRecipeGenerator,
    private val demo: DemoRecipeGenerator
) : RecipeGenerator {

    private val useRealModel: Boolean
        get() = GemmaRecipeGenerator.isModelAvailable(context)

    override val engineName: String
        get() = if (useRealModel) gemma.engineName else demo.engineName

    override suspend fun generate(
        ingredients: List<Ingredient>,
        onProgress: (String) -> Unit
    ): List<Recipe> {
        if (useRealModel) {
            runCatching { gemma.generate(ingredients, onProgress) }
                .onSuccess { if (it.isNotEmpty()) return it }
                .onFailure { Log.w(TAG, "Gemma inference failed, falling back to demo", it) }
        }
        return demo.generate(ingredients, onProgress)
    }

    private companion object {
        const val TAG = "SmartRecipeGenerator"
    }
}
