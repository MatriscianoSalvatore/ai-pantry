package com.smatrisciano.aipantry.core.presentation.composables

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * How long the screens keep the finished bar on screen before showing the
 * result: enough to see it reach 100%, short enough not to slow anything down.
 */
const val WAIT_COMPLETION_MILLIS = 350L

/**
 * Progress bar with a percentage for the on-device waits. [progress] (0..1) is
 * what the model has actually done: the bar glides from one value to the next
 * instead of jumping, and never goes back. It starts from 0 every time it enters
 * the composition.
 */
@Composable
fun WaitProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        if (progress > shown.value) {
            shown.animateTo(progress.coerceAtMost(1f), tween(GLIDE_MILLIS, easing = LinearOutSlowInEasing))
        }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // Thick rounded bar without track gap or end dot: a plain, calm loading bar
        LinearProgressIndicator(
            progress = { shown.value },
            modifier = Modifier
                .weight(1f)
                .height(6.dp),
            color = color,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {}
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "${(shown.value * 100).toInt()}%",
            color = textColor,
            // Tabular digits: the percentage doesn't wobble as it changes
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp)
        )
    }
}

// Long enough to smooth over the gaps between updates, short enough to keep up
private const val GLIDE_MILLIS = 450
