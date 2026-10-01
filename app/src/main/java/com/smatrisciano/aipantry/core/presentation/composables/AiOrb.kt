package com.smatrisciano.aipantry.core.presentation.composables

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smatrisciano.aipantry.core.presentation.theme.extendedColors

/**
 * "The model is thinking": a gradient disc with a sparkle, a comet-like ring
 * spinning around it and a soft halo breathing behind. Shown while Gemma
 * generates. The whole thing is [size] wide, halo included.
 */
@Composable
fun AiOrb(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp
) {
    val colors = MaterialTheme.extendedColors.aiGradient
    val transition = rememberInfiniteTransition(label = "aiOrb")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "ringRotation"
    )
    val breath by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "haloBreath"
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = breath
                    scaleY = breath
                }
                .background(
                    Brush.radialGradient(listOf(colors[1].copy(alpha = 0.28f), Color.Transparent)),
                    CircleShape
                )
        )
        Canvas(
            modifier = Modifier
                .size(size * 0.72f)
                .graphicsLayer { rotationZ = rotation }
        ) {
            val stroke = 3.dp.toPx()
            drawCircle(
                brush = Brush.sweepGradient(listOf(Color.Transparent, colors[0], colors[1], colors[2])),
                radius = (this.size.minDimension - stroke) / 2,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Box(
            modifier = Modifier
                .size(size * 0.54f)
                .background(Brush.linearGradient(colors), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.24f)
            )
        }
    }
}
