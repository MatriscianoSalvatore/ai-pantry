package com.smatrisciano.aipantry.core.presentation.composables

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.exp
import kotlin.math.ln

/**
 * How long the screens keep the finished bar on screen before showing the
 * result: enough to see it reach 100%, short enough not to slow anything down.
 */
const val WAIT_COMPLETION_MILLIS = 350L

/**
 * Progress bar with a percentage for waits whose real progress can't be
 * measured (on-device inference). The progress is simulated: fast at first,
 * then slower and slower, approaching 99% without reaching it, so it keeps
 * moving even when the wait runs longer than [expectedMillis] (where it shows
 * about 90%). When [completed] turns true it runs quickly to 100%; the caller
 * holds the result back for [WAIT_COMPLETION_MILLIS] so that it can be seen.
 * It starts from 0 every time it enters the composition.
 */
@Composable
fun WaitProgressBar(
    expectedMillis: Long,
    modifier: Modifier = Modifier,
    completed: Boolean = false,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val expected by rememberUpdatedState(expectedMillis)
    val progress = remember { Animatable(0f) }
    val start = remember { SystemClock.elapsedRealtime() }

    LaunchedEffect(completed) {
        if (completed) {
            progress.animateTo(1f, tween(FINISH_ANIMATION_MILLIS))
            return@LaunchedEffect
        }
        while (isActive) {
            // Never backwards, even if the expected duration changes mid-wait
            val simulated = simulatedProgress(SystemClock.elapsedRealtime() - start, expected)
            progress.snapTo(maxOf(progress.value, simulated))
            delay(TICK_MILLIS)
        }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.weight(1f),
            color = color,
            trackColor = trackColor
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "${(progress.value * 100).toInt()}%",
            color = textColor,
            // Tabular digits: the percentage doesn't wobble as it changes
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp)
        )
    }
}

// 1 - e^(-kt), capped at 99%: with k = ln(10) / expected, t = expected gives ~90%
private fun simulatedProgress(elapsedMillis: Long, expectedMillis: Long): Float {
    val k = ln(10.0) / expectedMillis.coerceAtLeast(1)
    return (MAX_PROGRESS * (1 - exp(-k * elapsedMillis))).toFloat()
}

private const val MAX_PROGRESS = 0.99
private const val TICK_MILLIS = 50L
private const val FINISH_ANIMATION_MILLIS = 200
