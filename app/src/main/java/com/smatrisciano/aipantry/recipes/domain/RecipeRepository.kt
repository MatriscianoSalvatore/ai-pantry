package com.smatrisciano.aipantry.recipes.domain

import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import kotlinx.coroutines.flow.Flow

enum class ListStatus { GENERATING, DONE, FAILED }

enum class DetailsStatus { PENDING, DONE, FAILED }

/** How far a recipe's details are: the recipe itself carries the parts written so far. */
data class RecipeDetails(
    val status: DetailsStatus = DetailsStatus.PENDING,
    val progress: Float = 0f,
    /** The part being written while [status] is pending, null when nothing is being written. */
    val writing: DetailsPart? = null
)

/** A recipe of a list: [id] stays the same while the list grows and reorders. */
data class ListedRecipe(
    val id: Int,
    val recipe: Recipe,
    val details: RecipeDetails
)

/**
 * The recipe the model is writing for a list. [key] changes with every recipe it
 * starts; [progress] (0..1) is null while it hasn't started one yet.
 */
data class NextRecipe(val key: String, val progress: Float?)

/** A list of recipes as the model writes it. */
data class RecipeList(
    /** Different for every list, regenerated ones included. */
    val id: Long,
    /** In display order: recipes missing something go last. */
    val recipes: List<ListedRecipe>,
    val status: ListStatus,
    /** The generator's steps so far, for the checklist on screen. */
    val steps: List<GenerationProgress>,
    val progress: Float,
    val expectedCount: Int,
    /** While the list grows: the recipe on its way. */
    val nextRecipe: NextRecipe?
)

/** The recipes on screen for an inventory: what the user does there steers the model. */
interface RecipeSession {
    val list: Flow<RecipeList>

    /**
     * The list was written, at least in part, ahead of time while the user was
     * elsewhere, and this is the first time it is opened.
     */
    val writtenAhead: Boolean

    /** A new list for the same ingredients. */
    fun regenerate()

    /** The recipe is on screen: its details go first. */
    fun showDetails(recipeId: Int)

    /** Back to the list: the rest of it goes first. */
    fun showList()

    /** The details failed: another try, with a new variation. */
    fun retryDetails(recipeId: Int)

    /** The recipes are no longer on screen. */
    fun close()
}

interface RecipeRepository {
    /** Engine name shown in the UI (e.g. "Gemma 4 E2B · LiteRT"). */
    val engineName: String

    /**
     * The recipes for [ingredients]: the list already written for them if there is
     * one (it may still be in progress, started ahead of time), a new one otherwise.
     */
    fun open(ingredients: List<Ingredient>): RecipeSession
}
