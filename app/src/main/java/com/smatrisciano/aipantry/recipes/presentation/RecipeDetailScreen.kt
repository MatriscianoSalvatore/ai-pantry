package com.smatrisciano.aipantry.recipes.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smatrisciano.aipantry.inventory.presentation.composables.ingredientEmoji
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import com.smatrisciano.aipantry.recipes.presentation.composables.DifficultyBadge

@Composable
fun RecipeDetailScreenRoot(
    viewModel: RecipesViewModel,
    recipeIndex: Int,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recipe = state.recipes.getOrNull(recipeIndex)

    // Le istruzioni si generano on-demand alla prima apertura
    LaunchedEffect(recipeIndex) {
        viewModel.onAction(RecipesActions.Interaction.OnRecipeOpened(recipeIndex))
    }

    if (recipe == null) {
        onBack()
        return
    }
    RecipeDetailScreen(
        recipe = recipe,
        isDetailLoading = state.isDetailLoading,
        detailError = state.detailError,
        onRetryDetails = {
            viewModel.onAction(RecipesActions.Interaction.OnRecipeOpened(recipeIndex))
        },
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeDetailScreen(
    recipe: Recipe,
    isDetailLoading: Boolean,
    detailError: String?,
    onRetryDetails: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipe.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("${recipe.prepTimeMinutes} min") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.width(16.dp)
                        )
                    }
                )
                DifficultyBadge(difficulty = recipe.difficulty)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = recipe.whySuitable,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionTitle("Ingredients from your pantry")
            recipe.usedIngredients.forEach { ingredient ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(text = ingredientEmoji(ingredient.name))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = ingredient.display, style = MaterialTheme.typography.bodyLarge)
                }
            }

            if (recipe.missingIngredients.isNotEmpty()) {
                SectionTitle("You'll also need")
                recipe.missingIngredients.forEach { ingredient ->
                    Text(
                        text = "• ${ingredient.display}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            SectionTitle("Instructions")
            if (recipe.steps.isEmpty() && isDetailLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Writing the instructions on-device…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (recipe.steps.isEmpty() && !isDetailLoading && detailError != null) {
                Text(
                    text = detailError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Button(onClick = onRetryDetails) {
                    Text("Retry")
                }
            }
            recipe.steps.forEachIndexed { index, step ->
                Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.wrapContentSize()
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (recipe.variants.isNotEmpty()) {
                SectionTitle("Variants")
                recipe.variants.forEach { variant ->
                    Text(
                        text = "• $variant",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
}
