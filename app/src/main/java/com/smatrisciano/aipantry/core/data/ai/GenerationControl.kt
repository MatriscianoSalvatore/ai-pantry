package com.smatrisciano.aipantry.core.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The user stopped the generation: an error of its own, so nothing retries it or blames the model. */
class StoppedByUserException : Exception("Generation stopped by the user")

/**
 * The stop button. Every answer being written is passed through [stoppable]: [stop] ends them,
 * and with them the native generation (stopping the collection stops it). Answers that start
 * afterwards are not affected.
 */
class GenerationControl {

    private val epoch = MutableStateFlow(0)

    /** Ends every answer being written right now. */
    fun stop() {
        epoch.update { it + 1 }
    }

    /** [upstream], until [stop]: then it fails with [StoppedByUserException], even while the model is still reading its prompt. */
    fun <T> stoppable(upstream: Flow<T>): Flow<T> = channelFlow {
        val started = epoch.value
        val watcher = launch {
            epoch.first { it != started }
            throw StoppedByUserException()
        }
        try {
            upstream.collect { send(it) }
        } finally {
            watcher.cancel()
        }
    }
}
