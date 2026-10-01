package com.smatrisciano.aipantry.recipes.presentation.composables

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.smatrisciano.aipantry.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Kitchen phrases shown in rotation while Gemma generates ("Chopping the
 * vegetables…"), to make the wait feel shorter. A new random order on every
 * wait, so they don't always start from the same one.
 */
@Composable
fun CookingWaitPhrases(
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign = TextAlign.Center
) {
    val phrases = stringArrayResource(R.array.cooking_wait_phrases)
    val order = remember(phrases.size) { phrases.indices.shuffled() }
    var position by remember { mutableIntStateOf(0) }

    LaunchedEffect(order) {
        while (isActive) {
            delay(PHRASE_MILLIS)
            position = (position + 1) % order.size
        }
    }

    AnimatedContent(
        targetState = phrases[order[position]],
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        modifier = modifier,
        label = "cookingWaitPhrase"
    ) { phrase ->
        Text(text = phrase, style = style, color = color, textAlign = textAlign)
    }
}

private const val PHRASE_MILLIS = 3_000L
