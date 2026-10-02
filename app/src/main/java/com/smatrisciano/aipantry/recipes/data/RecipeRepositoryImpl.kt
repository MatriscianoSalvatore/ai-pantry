package com.smatrisciano.aipantry.recipes.data

import android.os.SystemClock
import android.util.Log
import com.smatrisciano.aipantry.core.data.ai.BackgroundAiWork
import com.smatrisciano.aipantry.core.data.ai.LlmEngineHolder
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import com.smatrisciano.aipantry.core.data.ai.ModelStatus
import com.smatrisciano.aipantry.core.domain.AppLanguage
import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.inventory.domain.repository.InventoryRepository
import com.smatrisciano.aipantry.recipes.domain.DetailsStatus
import com.smatrisciano.aipantry.recipes.domain.DetailsUpdate
import com.smatrisciano.aipantry.recipes.domain.GenerationProgress
import com.smatrisciano.aipantry.recipes.domain.ListStatus
import com.smatrisciano.aipantry.recipes.domain.ListUpdate
import com.smatrisciano.aipantry.recipes.domain.ListedRecipe
import com.smatrisciano.aipantry.recipes.domain.NextRecipe
import com.smatrisciano.aipantry.recipes.domain.RECIPES_PER_LIST
import com.smatrisciano.aipantry.recipes.domain.RecipeDetails
import com.smatrisciano.aipantry.recipes.domain.RecipeGenerator
import com.smatrisciano.aipantry.recipes.domain.RecipeList
import com.smatrisciano.aipantry.recipes.domain.RecipeRepository
import com.smatrisciano.aipantry.recipes.domain.RecipeSession
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the recipe lists and runs every generation, one at a time: there is one
 * model and one CPU. What the user is looking at goes first: the recipe open on
 * screen, then the rest of its list. When nobody is waiting for anything the model
 * works ahead: on the recipes screen, the details of the recipe most likely to be
 * opened next; anywhere else, the first list for the current inventory, as soon as
 * the inventory changes. Work ahead stops as soon as something else needs the model
 * and later resumes where it was.
 *
 * Lists are kept per ingredient set: opening the recipes again shows the same list,
 * and only "Regenerate" moves on to another one.
 */
@OptIn(FlowPreview::class)
class RecipeRepositoryImpl(
    private val generator: RecipeGenerator,
    inventoryRepository: InventoryRepository,
    modelRepository: ModelRepository,
    engineHolder: LlmEngineHolder,
    backgroundAiWork: BackgroundAiWork,
    appScope: CoroutineScope
) : RecipeRepository {

    override val engineName: String get() = generator.engineName

    /** A recipe of a list and its details, as far as they have got. */
    private data class Entry(
        val id: Int,
        /** As the list wrote it: what the details are generated from. */
        val listed: Recipe,
        /** With the details written so far. */
        val recipe: Recipe = listed,
        val details: RecipeDetails = RecipeDetails(),
        val detailsRound: Int = 0,
        /** When the user last opened it, 0 if never. */
        val openedAt: Long = 0
    )

    /**
     * The current list for an ingredient set. With fixed seeds the model's output is
     * deterministic (bit-identical on CPU): the same [round] always gives the same list,
     * so an interrupted generation resumes into the very same recipes, and
     * "Regenerate" moves on to the next round.
     */
    private data class StoredList(
        val id: Long,
        val key: String,
        val ingredients: List<Ingredient>,
        val round: Int,
        val usedAt: Long,
        val entries: List<Entry> = emptyList(),
        val steps: List<GenerationProgress> = emptyList(),
        val progress: Float = 0f,
        val attempt: Int = 0,
        /** Recipes the current attempt has completed (odd ones included): a resumed attempt writes them again. */
        val written: Int = 0,
        /** How far the recipe being written is, null while none is. */
        val recipeProgress: Float? = null,
        val status: ListStatus = ListStatus.GENERATING,
        /** Shown on screen at least once. */
        val opened: Boolean = false
    ) {
        fun entry(recipeId: Int): Entry? = entries.firstOrNull { it.id == recipeId }

        // Recipes that are missing something go last; with the same number of
        // missing items, the model's relevance order stays
        fun displayOrder(): List<Entry> = entries.sortedBy { it.listed.missingIngredients.size }
    }

    private inner class Session(val key: String, override val writtenAhead: Boolean) : RecipeSession {

        override val list: Flow<RecipeList> =
            lists.mapNotNull { it[key]?.toRecipeList() }.distinctUntilChanged()

        override fun regenerate() {
            lists.update { all ->
                val current = all[key] ?: return@update all
                // Written under the user's eyes: already seen
                all + (key to newList(key, current.ingredients, current.round + 1).copy(opened = true))
            }
            focus.value = Focus(this, recipeId = null)
        }

        override fun showDetails(recipeId: Int) {
            updateEntry(key, recipeId) { it.copy(openedAt = SystemClock.elapsedRealtime()) }
            focus.value = Focus(this, recipeId)
        }

        override fun showList() {
            focus.update { if (it?.session == this) Focus(this, recipeId = null) else it }
        }

        override fun retryDetails(recipeId: Int) {
            updateEntry(key, recipeId) {
                it.copy(recipe = it.listed, details = RecipeDetails(), detailsRound = it.detailsRound + 1)
            }
        }

        override fun close() {
            focus.update { if (it?.session == this) null else it }
        }
    }

    /** What's on screen: a session's list, and the recipe open in it if any. */
    private data class Focus(val session: Session, val recipeId: Int?)

    private sealed interface Work {
        data class WriteList(val listId: Long) : Work
        data class WriteDetails(val listId: Long, val recipeId: Int) : Work
    }

    private val lists = MutableStateFlow<Map<String, StoredList>>(emptyMap())
    private val focus = MutableStateFlow<Focus?>(null)

    /** Key of the list for the current inventory, the one written ahead. */
    private val inventoryKey = MutableStateFlow<String?>(null)
    private val nextListId = AtomicLong()

    init {
        appScope.launch {
            inventoryRepository.observeInventory()
                // A burst of changes (ingredients removed one after the other) settles first
                .debounce(INVENTORY_SETTLE_MILLIS)
                .collect { ingredients ->
                    inventoryKey.value = ingredients.takeIf { it.isNotEmpty() }?.let { listFor(it).key }
                }
        }

        val modelReady = modelRepository.statuses
            .map { it[modelRepository.activeModel().id] == ModelStatus.Ready }
            .distinctUntilChanged()
        // Work ahead also waits for the startup warm-up, which may still be probing the GPU
        val aheadAllowed = combine(backgroundAiWork.isAllowed, engineHolder.isWarm) { allowed, warm -> allowed && warm }
        appScope.launch {
            combine(lists, focus, inventoryKey, aheadAllowed, modelReady) { lists, focus, inventoryKey, ahead, ready ->
                if (ready) nextWork(lists, focus, inventoryKey, ahead) else null
            }
                .distinctUntilChanged()
                // A new job cancels the running one and waits for it to stop
                .collectLatest { work ->
                    when (work) {
                        is Work.WriteList -> writeList(work.listId)
                        is Work.WriteDetails -> writeDetails(work.listId, work.recipeId)
                        null -> {}
                    }
                }
        }
    }

    override fun open(ingredients: List<Ingredient>): RecipeSession {
        val list = listFor(ingredients, replaceFailed = true)
        lists.update { all -> all + (list.key to all.getValue(list.key).copy(opened = true)) }
        val session = Session(list.key, writtenAhead = !list.opened && list.entries.isNotEmpty())
        focus.value = Focus(session, recipeId = null)
        return session
    }

    /**
     * What the model should be doing: the recipe open on screen, then the rest of its
     * list; then, if work ahead is allowed, the details most likely to be opened next
     * and the list for the current inventory.
     */
    private fun nextWork(
        lists: Map<String, StoredList>,
        focus: Focus?,
        inventoryKey: String?,
        aheadAllowed: Boolean
    ): Work? {
        val onScreen = focus?.let { lists[it.session.key] }
        if (focus != null && onScreen != null) {
            val recipeId = focus.recipeId
            if (recipeId != null && onScreen.entry(recipeId)?.details?.status == DetailsStatus.PENDING) {
                return Work.WriteDetails(onScreen.id, recipeId)
            }
            if (onScreen.status == ListStatus.GENERATING) return Work.WriteList(onScreen.id)
        }
        if (!aheadAllowed) return null
        if (onScreen != null) {
            onScreen.nextToOpen()?.let { return Work.WriteDetails(onScreen.id, it) }
        }
        val forInventory = inventoryKey?.let { lists[it] }
        if (forInventory?.status == ListStatus.GENERATING) return Work.WriteList(forInventory.id)
        return null
    }

    /** The recipe whose details are most likely needed next: the last one opened, else the first. */
    private fun StoredList.nextToOpen(): Int? {
        val opened = entries.filter { it.openedAt > 0 }.sortedByDescending { it.openedAt }
        return (opened + displayOrder().take(1)).firstOrNull { it.details.status == DetailsStatus.PENDING }?.id
    }

    private suspend fun writeList(listId: Long) {
        val list = lists.value.values.firstOrNull { it.id == listId } ?: return
        try {
            generator.generate(list.ingredients, list.round, list.entries.map { it.listed }, list.attempt, list.written)
                .collect { update -> updateList(listId) { it.after(update) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Recipe generation failed", e)
        }
        updateList(listId) {
            it.copy(
                status = if (it.entries.isEmpty()) ListStatus.FAILED else ListStatus.DONE,
                progress = 1f,
                recipeProgress = null
            )
        }
    }

    private suspend fun writeDetails(listId: Long, recipeId: Int) {
        val list = lists.value.values.firstOrNull { it.id == listId } ?: return
        val entry = list.entry(recipeId) ?: return
        val status = try {
            generator.generateDetails(entry.listed, entry.detailsRound)
                .collect { update -> updateEntry(list.key, recipeId, listId) { it.after(update) } }
            DetailsStatus.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Recipe details generation failed", e)
            DetailsStatus.FAILED
        }
        updateEntry(list.key, recipeId, listId) {
            it.copy(details = it.details.copy(status = status, writing = null))
        }
    }

    private fun StoredList.after(update: ListUpdate): StoredList = when (update) {
        is ListUpdate.Step -> if (update.progress in steps) this else copy(steps = steps + update.progress)
        is ListUpdate.Attempt ->
            if (update.number == attempt) this else copy(attempt = update.number, written = 0, recipeProgress = null)
        // Never backwards: a resumed generation first writes again what was already there
        is ListUpdate.Progress -> copy(
            progress = maxOf(progress, update.fraction),
            written = maxOf(written, update.written),
            recipeProgress = update.recipe
        )
        is ListUpdate.Written -> copy(entries = entries + Entry(id = entries.size, listed = update.recipe))
    }

    private fun Entry.after(update: DetailsUpdate): Entry = when (update) {
        is DetailsUpdate.Progress -> copy(details = details.copy(progress = maxOf(details.progress, update.fraction)))
        // A resumed generation writes again what was already there: it takes over once it gets further
        is DetailsUpdate.Written ->
            if (update.writing != null && update.recipe.detailsSize() < recipe.detailsSize()) {
                this
            } else {
                copy(recipe = update.recipe, details = details.copy(writing = update.writing))
            }
        DetailsUpdate.Retrying -> copy(recipe = listed, details = details.copy(writing = null))
    }

    /** How much of the details a recipe holds. */
    private fun Recipe.detailsSize(): Int =
        (if (whySuitable.isNotBlank()) 1 else 0) + usedIngredients.count { it.quantity.isNotBlank() } +
            steps.size + variants.size

    /**
     * The list for [ingredients]: the one already there, or a new first one. With
     * [replaceFailed] a list whose generation failed gives way to a new one.
     */
    private fun listFor(ingredients: List<Ingredient>, replaceFailed: Boolean = false): StoredList {
        val key = keyOf(ingredients)
        lists.update { all ->
            val existing = all[key]
            val list = when {
                existing == null -> newList(key, ingredients, round = 0)
                replaceFailed && existing.status == ListStatus.FAILED -> newList(key, ingredients, existing.round + 1)
                else -> existing.copy(usedAt = SystemClock.elapsedRealtime())
            }
            val kept = all + (key to list)
            if (kept.size > MAX_KEPT_LISTS) kept - kept.values.minBy { it.usedAt }.key else kept
        }
        return lists.value.getValue(key)
    }

    private fun newList(key: String, ingredients: List<Ingredient>, round: Int) = StoredList(
        id = nextListId.incrementAndGet(),
        key = key,
        ingredients = ingredients,
        round = round,
        usedAt = SystemClock.elapsedRealtime()
    )

    /** Same ingredients in any order, same language: the same lists. */
    private fun keyOf(ingredients: List<Ingredient>): String =
        "${AppLanguage.current()}|" + ingredients.map { it.name.lowercase() }.sorted().joinToString("|")

    private fun updateList(listId: Long, change: (StoredList) -> StoredList) {
        lists.update { all ->
            val list = all.values.firstOrNull { it.id == listId } ?: return@update all
            all + (list.key to change(list))
        }
    }

    /** Changes a recipe of the current list for [key]; with [listId], only if that list is still the current one. */
    private fun updateEntry(key: String, recipeId: Int, listId: Long? = null, change: (Entry) -> Entry) {
        lists.update { all ->
            val list = all[key]?.takeIf { listId == null || it.id == listId } ?: return@update all
            if (list.entry(recipeId) == null) return@update all
            all + (key to list.copy(entries = list.entries.map { if (it.id == recipeId) change(it) else it }))
        }
    }

    private fun StoredList.toRecipeList() = RecipeList(
        id = id,
        recipes = displayOrder().map { ListedRecipe(it.id, it.recipe, it.details) },
        status = status,
        steps = steps,
        progress = progress,
        expectedCount = RECIPES_PER_LIST,
        nextRecipe = if (status == ListStatus.GENERATING) NextRecipe("$attempt-$written", recipeProgress) else null
    )

    private companion object {
        const val TAG = "RecipeRepository"
        const val INVENTORY_SETTLE_MILLIS = 1_500L

        // Lists kept for ingredient sets other than the current one (a scan undone,
        // an ingredient removed and added back)
        const val MAX_KEPT_LISTS = 4
    }
}
