package com.smatrisciano.aipantry.core.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit

/**
 * How long the on-device waits usually take on this device, for the progress
 * bars: on-device inference exposes no real progress, so the bars simulate it
 * and need an expected duration. It starts from a default and learns from the
 * real durations (moving average, persisted), so the same bar fits a GPU or
 * Gemini Nano that answer in seconds and a CPU that takes a minute.
 */
class WaitTimeEstimator(context: Context) {

    // Defaults sized for a Pixel 7 running on CPU, the slowest case seen so far
    // (detection measured at ~30 s); real measurements replace them
    enum class Wait(val defaultMillis: Long) {
        DETECTION(30_000),
        RECIPES(60_000),
        RECIPE_DETAILS(60_000)
    }

    private val prefs = context.getSharedPreferences("wait_times", Context.MODE_PRIVATE)

    fun expectedMillis(wait: Wait): Long = prefs.getLong(wait.name, wait.defaultMillis)

    fun record(wait: Wait, millis: Long) {
        val previous = prefs.getLong(wait.name, -1L)
        // The first real measurement replaces the default; after that, half old
        // and half new: it adapts in a couple of runs without chasing one outlier
        val updated = if (previous < 0) millis else (previous + millis) / 2
        prefs.edit { putLong(wait.name, updated) }
        Log.i(TAG, "${wait.name} took ${millis}ms, next estimate ${updated}ms")
    }

    private companion object {
        const val TAG = "WaitTimeEstimator"
    }
}
