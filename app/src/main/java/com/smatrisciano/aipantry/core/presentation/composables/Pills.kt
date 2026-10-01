package com.smatrisciano.aipantry.core.presentation.composables

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smatrisciano.aipantry.R

/**
 * Small, quiet pill for engine and privacy facts: a leading mark and one line of text. The size
 * animates (background first, so the rounded ends follow) when the text changes length.
 */
@Composable
fun InfoPill(
    text: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .animateContentSize()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Quiet engine credit: which model ran, and that it ran on the phone. */
@Composable
fun EnginePill(
    engineName: String,
    modifier: Modifier = Modifier
) {
    InfoPill(
        text = stringResource(R.string.engine_on_device, engineName),
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.Rounded.Memory,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * A count in a small capsule. By default it is tinted with the surrounding content colour, so
 * inside a button it follows the button's own colours, disabled state included.
 */
@Composable
fun CountPill(
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = LocalContentColor.current.copy(alpha = 0.18f),
    contentColor: Color = LocalContentColor.current
) {
    Text(
        text = count.toString(),
        color = contentColor,
        // Tabular digits: the pill doesn't wobble as the count changes
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .background(containerColor, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
