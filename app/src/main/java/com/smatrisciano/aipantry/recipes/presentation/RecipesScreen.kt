package com.smatrisciano.aipantry.recipes.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import com.smatrisciano.aipantry.recipes.presentation.RecipesActions.Interaction
import com.smatrisciano.aipantry.recipes.presentation.RecipesActions.Navigation
import com.smatrisciano.aipantry.recipes.presentation.composables.DifficultyBadge

@Composable
fun RecipesScreenRoot(
    viewModel: RecipesViewModel,
    onNavigation: (Navigation) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RecipesScreen(
        state = state,
        onAction = { action ->
            when (action) {
                is Interaction -> viewModel.onAction(action)
                is Navigation -> onNavigation(action)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipesScreen(
    state: RecipesState,
    onAction: (RecipesActions) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("What can I cook?") },
                navigationIcon = {
                    IconButton(onClick = { onAction(Navigation.GoBack) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.isGenerating) {
                        IconButton(onClick = { onAction(Interaction.OnRegenerateClick) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Regenerate")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.error != null) {
            GenerationError(
                message = state.error,
                onRetry = { onAction(Interaction.OnRegenerateClick) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else if (state.isGenerating) {
            GeneratingContent(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(state.recipes) { index, recipe ->
                    RecipeCard(
                        recipe = recipe,
                        onClick = { onAction(Navigation.GoToDetail(index)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GenerationError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Recipe generation failed",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun GeneratingContent(
    state: RecipesState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "${state.engineName} · 100% on-device",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        state.progressLog.forEach { line ->
            AnimatedVisibility(visible = true) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun RecipeCard(
    recipe: Recipe,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = recipe.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = recipe.whySuitable,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = onClick,
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
                AssistChip(
                    onClick = onClick,
                    label = { Text("${recipe.usedIngredients.size} ingredients") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.width(16.dp)
                        )
                    }
                )
            }
            if (recipe.missingIngredients.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "🛒 Missing: ${recipe.missingIngredients.joinToString()}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
