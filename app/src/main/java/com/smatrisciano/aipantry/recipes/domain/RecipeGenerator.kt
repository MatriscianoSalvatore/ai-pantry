package com.smatrisciano.aipantry.recipes.domain

import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import kotlinx.coroutines.flow.Flow

/** Generation progress: the UI translates it into the device language. */
sealed interface GenerationProgress {
    data class LoadingModel(val modelName: String) : GenerationProgress
    data class Generating(val ingredientCount: Int) : GenerationProgress
    data object Retrying : GenerationProgress
}

/** The part of a recipe's details the model is writing. */
enum class DetailsPart { INTRO, INGREDIENTS, STEPS, VARIANTS }

/** What a list generation reports while the model writes. */
sealed interface ListUpdate {
    /** A step of the generator, for the checklist on screen. */
    data class Step(val progress: GenerationProgress) : ListUpdate

    /** Attempt [number] has started (0 is the first): an interrupted generation resumes from it. */
    data class Attempt(val number: Int) : ListUpdate

    /**
     * How far the list is (0..1), from what the model has read and written so far:
     * [written] recipes of this attempt are complete (odd ones included), and [recipe]
     * is how far the next one is (0..1), null while the model reads the prompt or
     * writes again what it had already written before an interruption.
     */
    data class Progress(val fraction: Float, val written: Int, val recipe: Float?) : ListUpdate

    /** A recipe the model has finished writing, checked and ready to show. */
    data class Written(val recipe: Recipe) : ListUpdate
}

/** What a details generation reports while the model writes. */
sealed interface DetailsUpdate {
    /** How far the details are (0..1), from what the model has read and written so far. */
    data class Progress(val fraction: Float) : DetailsUpdate

    /** The recipe with the details written so far; [writing] is the part still coming, null once complete. */
    data class Written(val recipe: Recipe, val writing: DetailsPart?) : DetailsUpdate

    /** The answer was unusable: another one starts from scratch. */
    data object Retrying : DetailsUpdate
}

interface RecipeGenerator {
    /** Engine name shown in the UI (e.g. "Gemma 4 E2B · LiteRT"). */
    val engineName: String

    /**
     * Writes a list of recipes for [ingredients] (without instructions: leaving them
     * out cuts the tokens to generate by ~4x), every recipe sent as soon as the model
     * has finished it. [round] picks the variation: 0 is the first list for these
     * ingredients, and the same round always gives the same recipes. A list can grow
     * round after round: the dishes it already has from [earlier] rounds don't come
     * back, not even under another name. An interrupted generation resumes with what
     * it had [received], from the attempt it was at and the [written] recipes that
     * attempt had completed, without sending those recipes again.
     */
    fun generate(
        ingredients: List<Ingredient>,
        round: Int,
        earlier: List<Recipe> = emptyList(),
        received: List<Recipe> = emptyList(),
        firstAttempt: Int = 0,
        written: Int = 0
    ): Flow<ListUpdate>

    /**
     * Completes a recipe with quantities, steps and variants, sending each part as
     * soon as it is written. [round] picks the variation, as for the lists.
     */
    fun generateDetails(recipe: Recipe, round: Int): Flow<DetailsUpdate>
}

/** How many recipes a list asks the model for, at first and every time it grows. */
const val RECIPES_PER_LIST = 4
