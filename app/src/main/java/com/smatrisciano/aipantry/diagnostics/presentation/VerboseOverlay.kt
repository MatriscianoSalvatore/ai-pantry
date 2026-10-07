package com.smatrisciano.aipantry.diagnostics.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smatrisciano.aipantry.R
import com.smatrisciano.aipantry.core.data.ai.InferenceRun
import com.smatrisciano.aipantry.core.data.ai.InferenceTask
import org.koin.androidx.compose.koinViewModel

/**
 * Over every screen, when the verbose mode is on (hidden page): which model did each task and
 * on what, how fast, and how hot the phone is. It can be dragged anywhere on screen, and stays
 * where it was left; the rest of the screen keeps its touches.
 */
@Composable
fun VerboseOverlay(viewModel: VerboseViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.visible) return

    var container by remember { mutableStateOf(IntSize.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var offset by remember { mutableStateOf(Offset(viewModel.savedPosition.first, viewModel.savedPosition.second)) }
    val margin = with(LocalDensity.current) { (BoxMargin * 2).toPx() }

    // Kept on screen: from the top right corner it can go left and down, never out of view
    fun Offset.kept(): Offset = Offset(
        x = x.coerceIn(-(container.width - box.width - margin).coerceAtLeast(0f), 0f),
        y = y.coerceIn(0f, (container.height - box.height - margin).coerceAtLeast(0f))
    )
    // The screen turned, or the box grew: it may have ended up out of view
    LaunchedEffect(container, box) {
        if (container != IntSize.Zero && box != IntSize.Zero) offset = offset.kept()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .onSizeChanged { container = it },
        contentAlignment = Alignment.TopEnd
    ) {
        Column(
            modifier = Modifier
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .padding(BoxMargin)
                .widthIn(max = 330.dp)
                .onSizeChanged { box = it }
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.78f))
                // Dragged by any point of it; the rest of the screen keeps its touches
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { viewModel.savePosition(offset.x, offset.y) },
                        onDragCancel = { viewModel.savePosition(offset.x, offset.y) }
                    ) { change, drag ->
                        change.consume()
                        offset = (offset + drag).kept()
                    }
                }
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val tasks = listOf(InferenceTask.SCAN, InferenceTask.RECIPE_LIST, InferenceTask.RECIPE_DETAILS)
            val runs = tasks.mapNotNull { state.runs[it] }
            if (runs.isEmpty()) Line(stringResource(R.string.verbose_idle), dim = true)
            runs.forEach { run -> RunLines(run, state.nowMillis) }

            state.device?.let { device ->
                val battery = device.batteryTemperatureCelsius?.let { "%.1f °C".format(it) } ?: "—"
                val headroom = device.thermalHeadroom?.let { "%.2f".format(it) } ?: "—"
                Line(
                    stringResource(R.string.verbose_heat, battery, device.thermalStatus.name.lowercase(), headroom),
                    dim = true
                )
                Line(
                    stringResource(R.string.verbose_memory, bytes(device.appMemoryBytes), bytes(device.availableMemoryBytes)),
                    dim = true
                )
            }
        }
    }
}

private val BoxMargin = 8.dp

@Composable
private fun RunLines(run: InferenceRun, nowMillis: Long) {
    val task = stringResource(
        when (run.task) {
            InferenceTask.SCAN -> R.string.verbose_task_scan
            InferenceTask.RECIPE_LIST -> R.string.verbose_task_list
            InferenceTask.RECIPE_DETAILS -> R.string.verbose_task_details
        }
    )
    Column {
        Line(stringResource(R.string.verbose_run_head, task, run.engine, run.backend))
        val elapsed = if (run.running) nowMillis - run.startedAtMillis else run.totalMillis
        when {
            run.failed -> Line(stringResource(R.string.verbose_run_failed), dim = true)
            run.running -> Line(stringResource(R.string.verbose_run_working, elapsed / 1000f, run.requests), dim = true)
            run.task == InferenceTask.SCAN ->
                Line(stringResource(R.string.verbose_run_scan, elapsed / 1000f, run.items ?: 0), dim = true)
            else -> {
                Line(
                    stringResource(
                        R.string.verbose_run_stats,
                        (run.firstOutputMillis ?: 0L) / 1000f,
                        elapsed / 1000f,
                        run.requests
                    ),
                    dim = true
                )
                run.charsPerSecond?.let { speed ->
                    // About four characters to a token: a rule of thumb, the engines don't say
                    Line(stringResource(R.string.verbose_run_speed, run.chars, speed, speed / 4f), dim = true)
                }
            }
        }
    }
}

@Composable
private fun Line(text: String, dim: Boolean = false) {
    Text(
        text = text,
        color = Color.White.copy(alpha = if (dim) 0.72f else 1f),
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        style = MaterialTheme.typography.labelSmall
    )
}

private fun bytes(value: Long): String =
    if (value >= 1_000_000_000) "%.2f GB".format(value / 1e9) else "%.0f MB".format(value / 1e6)
