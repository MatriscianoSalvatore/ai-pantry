package com.smatrisciano.aipantry.core.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit

/**
 * What the on-device waits usually look like on this device, for the progress
 * bars: how long the model takes to read a prompt before it writes anything, and
 * how long its answers run. The bars follow what the model actually does (reading,
 * then text written) and these are the yardsticks to measure it against. They
 * start from defaults and learn from every run (moving average, persisted), so the
 * same bar fits a GPU that answers in seconds and a CPU that takes a minute.
 */
class WaitTimeEstimator(context: Context) {

    // Defaults measured on a Pixel 7 running Gemma 4 E2B on CPU, the slowest case
    // seen so far; real measurements replace them
    enum class Measure(val default: Long) {
        /** Milliseconds from sending a prompt to the first piece of the answer. */
        PROMPT_READING_MILLIS(10_000),

        /** Characters of one recipe in a list. */
        LIST_RECIPE_CHARS(250),

        /** Characters of a recipe's details. */
        DETAILS_CHARS(2_200)
    }

    private val prefs = context.getSharedPreferences("wait_times", Context.MODE_PRIVATE)

    fun expected(measure: Measure): Long = prefs.getLong(measure.name, measure.default)

    fun record(measure: Measure, value: Long) {
        val previous = prefs.getLong(measure.name, -1L)
        // The first real measurement replaces the default; after that, half old
        // and half new: it adapts in a couple of runs without chasing one outlier
        val updated = if (previous < 0) value else (previous + value) / 2
        prefs.edit { putLong(measure.name, updated) }
        Log.i(TAG, "${measure.name}: $value, next estimate $updated")
    }

    private companion object {
        const val TAG = "WaitTimeEstimator"
    }
}
