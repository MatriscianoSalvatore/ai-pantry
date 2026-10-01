package com.smatrisciano.aipantry.recipes.presentation.composables

import com.smatrisciano.aipantry.inventory.presentation.composables.containsKeyword
import com.smatrisciano.aipantry.inventory.presentation.composables.ingredientEmoji
import com.smatrisciano.aipantry.inventory.presentation.composables.normalizeForKeywords
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import java.util.concurrent.ConcurrentHashMap

/**
 * Emoji for a recipe, from the kind of dish in its title (risotto, salad,
 * soup…), in English or Italian. The dish type wins over the ingredients:
 * "Zuppa di pesce" is a 🍲, not a 🐟. Without a recognisable dish it falls
 * back to the emoji of the first ingredient, then to a plate.
 *
 * Remembered by title and first ingredient (all it depends on): recipe cards
 * ask again whenever they scroll back into view.
 */
fun dishEmoji(recipe: Recipe): String {
    val key = recipe.title + "\u0000" + recipe.usedIngredients.firstOrNull()?.name.orEmpty()
    return emojiByDish[key] ?: matchDishEmoji(recipe).also { emoji ->
        if (emojiByDish.size >= MAX_REMEMBERED_DISHES) emojiByDish.clear()
        emojiByDish[key] = emoji
    }
}

private fun matchDishEmoji(recipe: Recipe): String {
    val n = normalizeForKeywords(recipe.title)

    fun has(vararg keys: String) = containsKeyword(n, keys, wholeWord = false)

    // Whole word for short keys that start other words: "egg" is not "eggplant"
    fun word(vararg keys: String) = containsKeyword(n, keys, wholeWord = true)

    return when {
        has("pizz", "focacc") -> "🍕"
        has("torta salata", "quiche", "sformat", "flan") || word("pie") -> "🥧"
        // Eggs only when they are the dish ("Uova al tegamino"), not a carbonara with eggs
        has("frittat", "omelet", "shakshuk") || EGG_DISH.containsMatchIn(n) -> "🍳"
        has("zupp", "minestr", "vellutat", "brodo", "soup", "broth", "stew", "stufat",
            "spezzatino", "pasta e fagioli", "ribollit") -> "🍲"
        has("curry", "chili") -> "🍛"
        has("insalat", "salad", "caprese", "panzanell") -> "🥗"
        has("risott", "riso", "rice", "paella") -> "🍚"
        has("pasta", "spaghett", "penne", "linguin", "fusill", "rigaton", "orecchiett",
            "tagliatell", "fettuccin", "pappardell", "bucatin", "lasagn", "ravioli",
            "tortellin", "gnocch", "carbonara", "amatriciana", "cacio e pepe", "noodle",
            "mac and cheese", "maccheron", "paccher", "trofie", "farfalle", "conchiglie") -> "🍝"
        has("polenta") -> "🌽"
        has("burger") -> "🍔"
        has("panin", "sandwich", "toast", "piadin", "tramezzin", "wrap") -> "🥪"
        has("bruschett", "crostin") -> "🥖"
        has("taco") -> "🌮"
        has("gamber", "scamp", "shrimp", "prawn") -> "🍤"
        has("cozz", "vongol", "mussel", "clam", "frutti di mare", "seafood") -> "🦪"
        has("calamar", "polpo", "polip", "seppi", "squid", "octopus") -> "🦑"
        has("pesce", "salmon", "tonno", "orata", "branzin", "merluzz", "baccala", "fish",
            "tuna", "trota", "trout", "alici", "acciugh", "sgombr") || word("cod") -> "🐟"
        has("pollo", "tacchino", "chicken", "turkey") -> "🍗"
        has("carne", "manzo", "vitell", "maiale", "bistecc", "tagliata", "polpett", "salsicc",
            "scaloppin", "beef", "steak", "pork", "veal", "meatball", "sausage") -> "🥩"
        has("tiramis", "torta", "cake", "crostat", "dolce", "dessert") || word("tart") -> "🍰"
        has("biscott", "cookie") -> "🍪"
        has("pancake", "crepe", "crespell") -> "🥞"
        has("muffin", "cupcake") -> "🧁"
        has("gelat", "ice cream", "semifredd") -> "🍦"
        has("budin", "pudding", "panna cotta", "creme caramel") -> "🍮"
        has("smoothie", "frullat", "frappe") -> "🥤"
        has("verdur", "vegetab", "ortagg", "grigliat", "contorno") -> "🥦"
        else -> recipe.usedIngredients.firstOrNull()?.let { ingredientEmoji(it.name) } ?: "🍽️"
    }
}

private val EGG_DISH = Regex("^(uova|uovo|eggs?)\\b")

private val emojiByDish = ConcurrentHashMap<String, String>()

private const val MAX_REMEMBERED_DISHES = 256
