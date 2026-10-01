package com.smatrisciano.aipantry.recipes.presentation.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smatrisciano.aipantry.R
import com.smatrisciano.aipantry.core.presentation.theme.extendedColors
import com.smatrisciano.aipantry.recipes.domain.models.Difficulty

/**
 * Difficulty as three ascending bars, filled up to the level (one for easy,
 * three for hard) and coloured green / amber / red, followed by the label.
 */
@Composable
fun DifficultyBadge(
    difficulty: Difficulty,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DifficultyBars(difficulty)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = difficultyLabel(difficulty),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DifficultyBars(
    difficulty: Difficulty,
    modifier: Modifier = Modifier
) {
    val level = difficulty.ordinal + 1
    val color = when (difficulty) {
        Difficulty.EASY -> MaterialTheme.extendedColors.success
        Difficulty.MEDIUM -> MaterialTheme.extendedColors.warning
        Difficulty.HARD -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((6 + index * 3).dp)
                    .background(
                        color = if (index < level) color else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

@Composable
fun difficultyLabel(difficulty: Difficulty): String = stringResource(
    when (difficulty) {
        Difficulty.EASY -> R.string.difficulty_easy
        Difficulty.MEDIUM -> R.string.difficulty_medium
        Difficulty.HARD -> R.string.difficulty_hard
    }
)
