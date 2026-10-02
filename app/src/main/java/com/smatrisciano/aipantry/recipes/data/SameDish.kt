package com.smatrisciano.aipantry.recipes.data

/**
 * Whether two recipe titles name the same dish, so that a list never offers it
 * twice, not even under another name: every word of one title is in the other
 * ("Risotto ai funghi", "Risotto ai funghi e parmigiano"), with any pasta shape
 * counting as pasta ("Spaghetti al pomodoro", "Penne al pomodoro") and singular and
 * plural alike. Dishes that only share their base stay apart ("Insalata di riso",
 * "Insalata di farro").
 */
internal object SameDish {

    fun matches(a: String, b: String): Boolean {
        val wordsA = dishWords(a)
        val wordsB = dishWords(b)
        // Nothing but connectives: only the very same title counts
        if (wordsA.isEmpty() || wordsB.isEmpty()) return a.trim().equals(b.trim(), ignoreCase = true)
        return wordsA.containsAll(wordsB) || wordsB.containsAll(wordsA)
    }

    private fun dishWords(title: String): Set<String> =
        word.findAll(title.lowercase())
            .map { it.value }
            .filterNot { it in connectives }
            .map { if (RecipeTitleRules.pastaShapes.containsMatchIn(it)) PASTA else stem(it) }
            .toSet()

    // "Pomodoro" and "pomodori", "melanzana" and "melanzane", "egg" and "eggs" end up the same
    private fun stem(word: String): String =
        if (word.length <= 3) word else word.removeSuffix("s").trimEnd('a', 'e', 'i', 'o', 'u')

    private const val PASTA = "pasta"

    private val word = Regex("""\p{L}+""")

    // Articles, prepositions and conjunctions, in Italian and in English
    private val connectives = setOf(
        "a", "ad", "al", "allo", "alla", "all", "ai", "agli", "alle",
        "con", "col", "coi", "e", "ed", "o", "di", "del", "dello", "della", "dell", "dei", "degli", "delle",
        "da", "dal", "dalla", "dai", "in", "nel", "nella", "nei", "su", "sul", "sulla", "sui", "per",
        "il", "lo", "la", "l", "i", "gli", "le", "un", "uno", "una",
        "with", "and", "or", "of", "the", "an", "on"
    )
}
