package com.smatrisciano.aipantry.recipes.data

/**
 * Picks the complete top-level objects out of JSON that arrives a piece at a time
 * (a list of recipes as the model writes it): each one comes out as soon as its
 * closing brace arrives. Strings and escapes are respected, so a brace inside a
 * title doesn't count.
 */
internal class JsonObjectStream {

    private val text = StringBuilder()
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var objectStart = -1

    /** Characters written so far of the object still open, 0 between objects. */
    val pendingLength: Int get() = if (depth > 0) text.length - objectStart else 0

    /** Adds [piece] and returns the objects it completes. */
    fun append(piece: String): List<String> {
        val from = text.length
        text.append(piece)
        val complete = mutableListOf<String>()
        for (i in from until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> {}
                c == '{' -> {
                    if (depth == 0) objectStart = i
                    depth++
                }
                c == '}' && depth > 0 -> {
                    depth--
                    if (depth == 0) complete += text.substring(objectStart, i + 1)
                }
            }
        }
        return complete
    }
}

/** A JSON object cut while being written, closed after its last complete value. */
internal class ClosedJson(
    val json: String,
    /** Where the cut falls in the original text: it moves on only when another value is complete. */
    val end: Int,
    /** The top-level key whose value is being written at the cut, if any. */
    val openKey: String?
)

/**
 * [text] (a JSON object being written, cut anywhere) up to its last complete value,
 * with the brackets still open closed after it: a valid object holding everything
 * finished so far. Only the object's own values and the items of its arrays count,
 * and an item only once complete, never half written. Null until the first value is
 * complete; the whole object once its closing brace has arrived.
 */
internal fun closeAtLastValue(text: CharSequence): ClosedJson? {
    val start = text.indexOf('{')
    if (start < 0) return null
    // Brackets still open, innermost last: '{' or '['
    val open = StringBuilder()
    var inString = false
    var escaped = false
    var stringIsKey = false
    var expectKey = false
    var stringStart = 0
    var lastKey: String? = null
    var valueOpen = false
    var cutEnd = -1
    var cutClosers = ""

    fun cutAt(end: Int) {
        cutEnd = end
        cutClosers = buildString { for (i in open.indices.reversed()) append(if (open[i] == '{') '}' else ']') }
    }

    for (i in start until text.length) {
        val c = text[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> {
                    inString = false
                    if (stringIsKey) {
                        if (open.length == 1) lastKey = text.substring(stringStart, i)
                    } else if (open.length <= 2) {
                        cutAt(i + 1)
                        if (open.length == 1) valueOpen = false
                    }
                }
            }
            continue
        }
        when (c) {
            '"' -> {
                inString = true
                stringIsKey = expectKey && open.lastOrNull() == '{'
                stringStart = i + 1
            }
            '{', '[' -> {
                open.append(c)
                expectKey = c == '{'
            }
            '}', ']' -> {
                if (open.isEmpty()) break
                open.deleteCharAt(open.lastIndex)
                if (open.isEmpty()) return ClosedJson(text.substring(start, i + 1), i + 1, null)
                if (open.length <= 2) cutAt(i + 1)
                if (open.length == 1) valueOpen = false
                expectKey = false
            }
            ':' -> {
                expectKey = false
                if (open.length == 1) valueOpen = true
            }
            ',' -> {
                // Whatever came before the comma is complete (numbers included)
                if (open.length <= 2) cutAt(i)
                if (open.length == 1) valueOpen = false
                expectKey = open.lastOrNull() == '{'
            }
        }
    }
    if (cutEnd < 0) return null
    return ClosedJson(text.substring(start, cutEnd) + cutClosers, cutEnd, lastKey.takeIf { valueOpen })
}
