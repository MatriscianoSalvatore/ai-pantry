package com.smatrisciano.aipantry.core.data.ai

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class InferenceTask { SCAN, RECIPE_LIST, RECIPE_DETAILS }

/** One answer of a model, as the verbose display shows it. */
data class InferenceRun(
    val task: InferenceTask,
    val engine: String,
    /** When it began, on the clock of [android.os.SystemClock.elapsedRealtime]: a running answer is timed from it. */
    val startedAtMillis: Long,
    /** Where it runs: AICore, GPU, CPU. */
    val backend: String,
    /** Requests it took: more than one when Gemini Nano is asked to carry on. */
    val requests: Int,
    /** Until the first text came out: the prompt being read. Null while nothing has. */
    val firstOutputMillis: Long?,
    val totalMillis: Long,
    val chars: Long,
    /** Ingredients found, for a scan. */
    val items: Int?,
    val running: Boolean,
    val failed: Boolean
) {
    /** Characters written per second, once there is something to measure. */
    val charsPerSecond: Float?
        get() {
            val writing = totalMillis - (firstOutputMillis ?: return null)
            return if (chars > 0 && writing > 0) chars * 1000f / writing else null
        }
}

/**
 * The latest answer of each task: what the verbose display reads. Times are measured where
 * the text arrives, so Gemini Nano and Gemma are timed the same way.
 */
class InferenceStats {

    private val _latest = MutableStateFlow<Map<InferenceTask, InferenceRun>>(emptyMap())
    val latest: StateFlow<Map<InferenceTask, InferenceRun>> = _latest.asStateFlow()

    /** Starts timing an answer; the caller reports on the returned [Run]. */
    fun begin(task: InferenceTask, engine: String, backend: String): Run = Run(task, engine, backend)

    inner class Run(private val task: InferenceTask, private val engine: String, private val backend: String) {
        private val startedAt = SystemClock.elapsedRealtime()
        private var requests = 1
        private var firstOutputAt: Long? = null
        private var chars = 0L
        private var items: Int? = null
        private var finished = false

        init {
            publish(running = true, failed = false)
        }

        /** Another request for the same answer. */
        @Synchronized
        fun request() {
            requests++
            publish(running = true, failed = false)
        }

        /** [length] more characters have come out. */
        @Synchronized
        fun output(length: Int) {
            if (finished) return
            if (firstOutputAt == null) firstOutputAt = SystemClock.elapsedRealtime()
            chars += length
            publish(running = true, failed = false)
        }

        @Synchronized
        fun items(count: Int) {
            items = count
        }

        @Synchronized
        fun finish(failed: Boolean = false) {
            if (finished) return
            finished = true
            publish(running = false, failed = failed)
        }

        private fun publish(running: Boolean, failed: Boolean) {
            val now = SystemClock.elapsedRealtime()
            val run = InferenceRun(
                task = task,
                engine = engine,
                startedAtMillis = startedAt,
                backend = backend,
                requests = requests,
                firstOutputMillis = firstOutputAt?.let { it - startedAt },
                totalMillis = now - startedAt,
                chars = chars,
                items = items,
                running = running,
                failed = failed
            )
            _latest.value = _latest.value + (task to run)
        }
    }
}
