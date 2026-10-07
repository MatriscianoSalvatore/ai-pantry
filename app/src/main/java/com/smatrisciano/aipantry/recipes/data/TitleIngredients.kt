package com.smatrisciano.aipantry.recipes.data

/**
 * The ingredients an Italian title promises ("Spaghetti alle vongole" → vongole). The
 * small model sometimes names a dish after an ingredient it then leaves out of its list:
 * nothing is missing, as far as the list goes, and the dish shows without its main
 * ingredient. Read from what follows "con", "ai", "agli", "alle" or "di" only: "al" and
 * "alla" are as often a method or a name ("al forno", "alla Norma") as an ingredient, so
 * they are left alone.
 */
internal object TitleIngredients {

    /** The ingredients named by [title], cleaned of articles; empty when it names none. */
    fun of(title: String): List<String> {
        val text = title.lowercase()
        val afterConnector = connector.find(text)?.let { text.substring(it.range.last + 1) } ?: return emptyList()
        val named = endOfList.find(afterConnector)?.let { afterConnector.substring(0, it.range.first) } ?: afterConnector
        return named.split(listSeparator)
            .map { it.trim().replace(leadingConnectives, "").trim() }
            .filter { chunk -> contentWords(chunk).isNotEmpty() }
            .distinct()
    }

    /** Whether [ingredient] names, in whole or in part, something [named] holds. */
    fun isCovered(ingredient: String, named: Collection<String>): Boolean {
        val wanted = contentWords(ingredient)
        if (wanted.isEmpty()) return true
        return named.any { other -> contentWords(other).any { word -> wanted.any { sameWord(it, word) } } }
    }

    private fun contentWords(text: String): List<String> =
        word.findAll(text.lowercase()).map { it.value }.filterNot { it in filler }.toList()

    // "vongola" and "vongole", "fungo" and "funghi", "uovo" and "uova" are the same word. A
    // short stem has to be the same exactly ("pepe" mustn't cover "peperoni")
    private fun sameWord(a: String, b: String): Boolean {
        val x = a.trimEnd(*VOWELS)
        val y = b.trimEnd(*VOWELS)
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return short == long || (short.length >= MIN_PREFIX && long.startsWith(short))
    }

    private const val MIN_PREFIX = 4
    private val VOWELS = charArrayOf('a', 'e', 'i', 'o', 'u')

    private val word = Regex("""\p{L}+""")

    // "d'" too: "crema d'asparagi"
    private val connector = Regex("""\b(?:con|ai|agli|alle|di)\s|\bd['’]""")

    // What closes the list of ingredients: a method, a place, a "without"
    private val endOfList =
        Regex("""\s+(?:al|alla|allo|in|su|per|senza|ripien\w*|gratinat\w*|stufat\w*)(?=\s|$)|\s+all['’]""")
    private val listSeparator = Regex("""\s*,\s*|\s+(?:e|ed|con)\s+""")
    private val leadingConnectives =
        Regex("""^(?:(?:il|lo|la|le|i|gli|un|uno|una|dei|del|della|delle|degli|di)\s+|[ld]['’])+""")

    // Articles and prepositions, and words that say how an ingredient comes rather than what it is
    private val filler = setOf(
        "il", "lo", "la", "le", "l", "i", "gli", "un", "uno", "una", "di", "d", "del", "della", "dello", "dell",
        "dei", "degli", "delle", "con", "e", "ed", "o", "al", "alla", "allo", "ai", "alle", "agli", "in", "su", "per",
        "salsa", "sugo", "crema", "ripieno", "ripiena", "ripieni", "ripiene", "misto", "mista", "misti", "miste",
        "fresco", "fresca", "freschi", "fresche", "piccolo", "piccola", "piccoli", "piccole",
        "fritto", "fritta", "fritti", "fritte", "stagione", "stagionale", "stagionali"
    )
}
