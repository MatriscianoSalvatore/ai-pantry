package com.smatrisciano.aipantry.recipes.data

/**
 * Checks on the Italian recipe titles Gemma writes. A 2B model now and then
 * capitalises every word, closes the name of a first course with a minor
 * ingredient ("Risotto ai funghi e latte") or puts together things no cook would
 * ("Polenta con ricotta e insalata", "Risotto al farro e funghi"). Caught here
 * rather than with more rules in the prompt, which would change the recipes
 * themselves.
 *
 * Deliberately narrow: aromatics and fats are often the point of a dish
 * ("Spaghetti aglio, olio e peperoncino", "Frittata di uova e cipolla", "Cacio e
 * pepe"), so they are never touched. Italian only: the rules rely on the dish
 * coming first in the title ("Risotto ai funghi", not "Mushroom risotto").
 */
internal object RecipeTitleRules {

    /** Sentence case, without minor ingredients or a redundant rice at the end. */
    fun tidy(title: String): String {
        var current = sentenceCase(title)
        while (true) {
            val next = withoutRedundantRice(withoutMinorTail(current))
            if (next == current) return current
            current = next
        }
    }

    /**
     * True when the dish doesn't hold together: two starchy bases ("Risotto al farro",
     * "Orecchiette con fusilli"), polenta, rice or pasta with salad leaves, polenta or
     * risotto with eggs. Only for dishes named after their base: salads, omelettes or
     * baked vegetables can mix freely ("Insalata di riso e farro").
     */
    fun isOddCombination(title: String): Boolean {
        val text = title.lowercase()
        val leadFamily = leadFamily(text) ?: return false
        return distinctBases(text) >= 2 ||
            (leadFamily in basesWithoutLeaves && saladLeaves.containsMatchIn(text)) ||
            (leadFamily in basesWithoutEggs && eggs.containsMatchIn(text))
    }

    /**
     * "Risotto ai Funghi e Parmigiano" → "Risotto ai funghi e parmigiano": the prompt
     * asks for a single initial capital, which the model now and then ignores. Words
     * after the first are lowered only when two or more are capitalised, so that a
     * lone proper name ("Pasta alla Norma") stays as it is.
     */
    private fun sentenceCase(title: String): String {
        val words = title.trim().split(whitespace)
        fun isCapitalised(word: String) =
            word.length > 3 && word.first().isUpperCase() && word.drop(1).none { it.isUpperCase() }
        val lowerRest = words.drop(1).count(::isCapitalised) >= 2
        return words
            .mapIndexed { i, word -> if (i > 0 && lowerRest && isCapitalised(word)) word.lowercase() else word }
            .joinToString(" ")
            .replaceFirstChar { it.titlecase() }
    }

    /**
     * "Risotto ai funghi e latte" → "Risotto ai funghi", "Orecchiette con funghi e olio"
     * → "Orecchiette con funghi". Only for first courses named after their base, and a
     * title that would be left too bare keeps its tail ("Spaghetti aglio e olio").
     */
    private fun withoutMinorTail(title: String): String {
        if (leadFamily(title.lowercase()) == null) return title
        val tail = minorTail.find(title) ?: return title
        return cut(title, tail)
    }

    /**
     * "Risotto ai funghi e riso" → "Risotto ai funghi": a risotto is made of rice
     * already. The prompt asks not to name what the dish's name implies, but Gemma
     * still slips now and then.
     */
    private fun withoutRedundantRice(title: String): String {
        if (!title.lowercase().startsWith("risott")) return title
        val tail = redundantRice.find(title) ?: return title
        return cut(title, tail)
    }

    /**
     * The title without [tail]. A list loses its last item cleanly ("Pasta al pomodoro,
     * basilico e olio" → "Pasta al pomodoro e basilico"), and a title that would be left
     * too bare keeps its tail.
     */
    private fun cut(title: String, tail: MatchResult): String {
        var rest = title.substring(0, tail.range.first).trimEnd(' ', ',')
        val lastComma = rest.lastIndexOf(", ")
        if (lastComma >= 0) rest = rest.substring(0, lastComma) + " e " + rest.substring(lastComma + 2)
        return if (rest.split(whitespace).size < 3) title else rest
    }

    /** The base the dish is named after ("Risotto ai funghi" → rice), if any. */
    private fun leadFamily(text: String): String? {
        val lead = firstWord.find(text)?.value ?: return null
        return baseFamilies.entries.firstOrNull { it.value.containsMatchIn(lead) }?.key
    }

    /**
     * Different families count once each, different pasta shapes count apart. "di
     * farro" or "di riso" is what the base is made of ("Spaghetti di farro"), not a
     * second base.
     */
    private fun distinctBases(text: String): Int =
        baseFamilies.flatMap { (family, pattern) ->
            pattern.findAll(text)
                .filterNot { text.substring(0, it.range.first).endsWith("di ") }
                .map { if (family == PASTA) it.value else family }
                .toList()
        }.toSet().size

    private const val PASTA = "pasta"
    private const val POLENTA = "polenta"
    private const val RICE = "riso"

    private val baseFamilies = mapOf(
        POLENTA to Regex("""\bpolent"""),
        RICE to Regex("""\bris[oi]\b|\brisott|\brice\b"""),
        "farro" to Regex("""\bfarro|\bspelt"""),
        "orzo" to Regex("""\borzo\b|\bbarley"""),
        "cereali" to Regex("""\bcouscous|\bquinoa"""),
        PASTA to Regex(
            """\bpasta\b|\bspaghett|\bpenne\b|\bfusill|\borecchiett|\blinguin|\btagliatell|\brigaton|\bgnocch|\blasagn|""" +
                """\bmaccheron|\bpaccher|\bbucatin|\bfarfall|\btortellin|\bravioli|\bnoodle|\btrofie|\bconchigli|""" +
                """\bmezze maniche|\bditalini"""
        )
    )
    private val basesWithoutLeaves = setOf(POLENTA, RICE, PASTA)
    private val basesWithoutEggs = setOf(POLENTA, RICE)

    private val saladLeaves = Regex("""\binsalat|\blattug|\bvaleriana|\bsongino|\bmisticanza|\bsalad\b|\blettuce""")
    private val eggs = Regex("""\buov[ao]\b|\beggs?\b""")
    private val firstWord = Regex("""[a-zàèéìòù]+""")
    private val whitespace = Regex("""\s+""")

    // Rice closing a risotto's title, with the variety or "per risotto" the inventory may carry
    private val redundantRice = Regex(
        """\s+(?:e|con)\s+(?:il )?riso(?: per risotto| carnaroli| arborio| vialone(?: nano)?)?$""",
        RegexOption.IGNORE_CASE
    )

    // A liquid or a pantry basic closing the title, after "e" or "con" and an optional
    // article. Never aromatics, fats or cheeses: they often are what the dish is about
    private val minorTail = Regex(
        """\s+(?:e|con)\s+(?:il |lo |la |l'|i |gli |le )?""" +
            """(?:latte|vino(?: bianco| rosso)?|brodo(?: vegetale| di carne)?|acqua|farina|""" +
            """olio(?: d['’]oliva| extravergine(?: d['’]oliva)?)?)$""",
        RegexOption.IGNORE_CASE
    )
}
