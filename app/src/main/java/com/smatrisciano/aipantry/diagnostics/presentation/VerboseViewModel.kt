package com.smatrisciano.aipantry.diagnostics.presentation

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smatrisciano.aipantry.core.data.ai.InferenceRun
import com.smatrisciano.aipantry.core.data.ai.InferenceStats
import com.smatrisciano.aipantry.core.data.ai.InferenceTask
import com.smatrisciano.aipantry.core.data.ai.ModelPreferences
import com.smatrisciano.aipantry.diagnostics.data.DeviceMonitor
import com.smatrisciano.aipantry.diagnostics.data.DeviceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** What the verbose display draws: the latest answer of each task and how the device is doing. */
data class VerboseState(
    val visible: Boolean = false,
    val device: DeviceSnapshot? = null,
    val runs: Map<InferenceTask, InferenceRun> = emptyMap(),
    /** The clock the runs are timed on, as of this reading. */
    val nowMillis: Long = 0L
)

/** Reads the device every second while the verbose display is on, and not at all otherwise. */
@OptIn(ExperimentalCoroutinesApi::class)
class VerboseViewModel(
    private val choices: ModelPreferences,
    stats: InferenceStats,
    deviceMonitor: DeviceMonitor
) : ViewModel() {

    private val device = flow {
        while (true) {
            emit(deviceMonitor.snapshot())
            delay(REFRESH_MILLIS)
        }
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<VerboseState> = choices.verbose.flatMapLatest { on ->
        if (!on) {
            flowOf(VerboseState())
        } else {
            combine(device, stats.latest) { device, runs ->
                VerboseState(visible = true, device = device, runs = runs, nowMillis = SystemClock.elapsedRealtime())
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VerboseState())

    /** Where the box was left the last time, as an offset in pixels from the top right corner. */
    val savedPosition: Pair<Float, Float> get() = choices.overlayX to choices.overlayY

    fun savePosition(x: Float, y: Float) {
        choices.overlayX = x
        choices.overlayY = y
    }

    private companion object {
        const val REFRESH_MILLIS = 1_000L
    }
}
