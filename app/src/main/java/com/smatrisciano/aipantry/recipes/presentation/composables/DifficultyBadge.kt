package com.smatrisciano.aipantry.recipes.presentation.composables

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smatrisciano.aipantry.R
import com.smatrisciano.aipantry.recipes.domain.models.Difficulty

@Composable
fun DifficultyBadge(difficulty: Difficulty) {
    val (label, color) = when (difficulty) {
        Difficulty.EASY -> stringResource(R.string.difficulty_easy) to Color(0xFF4CAF50)
        Difficulty.MEDIUM -> stringResource(R.string.difficulty_medium) to Color(0xFFFF9800)
        Difficulty.HARD -> stringResource(R.string.difficulty_hard) to Color(0xFFF44336)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.18f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
