package com.smatrisciano.aipantry.aisetup.presentation

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smatrisciano.aipantry.aisetup.presentation.AiSetupActions.Interaction
import com.smatrisciano.aipantry.aisetup.presentation.AiSetupActions.Navigation
import com.smatrisciano.aipantry.core.data.ai.ModelStatus
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

@Composable
fun AiSetupScreenRoot(
    viewModel: AiSetupViewModel = koinViewModel(),
    onNavigation: (Navigation) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AiSetupScreen(
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
private fun AiSetupScreen(
    state: AiSetupState,
    onAction: (AiSetupActions) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("On-device AI") },
                navigationIcon = {
                    IconButton(onClick = { onAction(Navigation.GoBack) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Recipes and ingredient recognition run entirely on your phone. " +
                        "Pick a model and download it once (Wi-Fi recommended) — " +
                        "after that the app works fully offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedTextField(
                    value = state.hfToken,
                    onValueChange = { onAction(Interaction.OnTokenChanged(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hugging Face token") },
                    supportingText = {
                        Text(
                            "Needed for Gemma models only: accept the license on " +
                                "huggingface.co, then paste a read token here."
                        )
                    },
                    singleLine = true
                )
            }
            items(state.models, key = { it.model.id }) { modelState ->
                ModelCard(modelState = modelState, onAction = onAction)
            }
        }
    }
}

@Composable
private fun ModelCard(
    modelState: ModelUiState,
    onAction: (AiSetupActions) -> Unit
) {
    val model = modelState.model
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = modelState.isActive,
                    onClick = { onAction(Interaction.OnModelSelected(model.id)) }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${model.sizeBytes.toGb()} · ${model.license}" +
                            if (model.requiresHfToken) " · HF token" else " · no login",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (model.supportsVision) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Vision") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.RemoveRedEye,
                                contentDescription = null,
                                modifier = Modifier.width(16.dp)
                            )
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            ModelStatusRow(modelState = modelState, onAction = onAction)
        }
    }
}

@Composable
private fun ModelStatusRow(
    modelState: ModelUiState,
    onAction: (AiSetupActions) -> Unit
) {
    val model = modelState.model
    when (val status = modelState.status) {
        is ModelStatus.Ready -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Ready — runs fully offline",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onAction(Interaction.OnDeleteClick(model.id)) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete model")
                }
            }
        }

        is ModelStatus.Downloading -> {
            Column {
                val progress = status.totalBytes?.let {
                    status.downloadedBytes.toFloat() / it
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${status.downloadedBytes.toGb()} of ${status.totalBytes.toGb()}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { onAction(Interaction.OnCancelClick(model.id)) }) {
                        Text("Pause")
                    }
                }
            }
        }

        is ModelStatus.NotDownloaded -> {
            Button(
                onClick = { onAction(Interaction.OnDownloadClick(model.id)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (status.resumableBytes > 0) {
                        "Resume download (${status.resumableBytes.toGb()} done)"
                    } else {
                        "Download"
                    }
                )
            }
        }

        is ModelStatus.Failed -> {
            Column {
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onAction(Interaction.OnDownloadClick(model.id)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (status.resumableBytes > 0) "Resume" else "Retry")
                }
            }
        }
    }
}

private fun Long?.toGb(): String =
    this?.let { String.format(Locale.US, "%.1f GB", it / 1_073_741_824.0) } ?: "…"
