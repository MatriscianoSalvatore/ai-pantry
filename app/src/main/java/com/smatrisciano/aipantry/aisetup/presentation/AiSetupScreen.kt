package com.smatrisciano.aipantry.aisetup.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smatrisciano.aipantry.aisetup.presentation.AiSetupActions.Interaction
import com.smatrisciano.aipantry.aisetup.presentation.AiSetupActions.Navigation
import com.smatrisciano.aipantry.core.data.ai.ModelSource
import com.smatrisciano.aipantry.core.data.ai.ModelStatus
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

@Composable
fun AiSetupScreenRoot(
    viewModel: AiSetupViewModel = koinViewModel(),
    onNavigation: (Navigation) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Launcher per il dialog di conferma Play (download su rete mobile)
    val confirmationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* Play riprende da solo il download dopo la conferma */ }
    DisposableEffect(confirmationLauncher) {
        viewModel.confirmationLauncher = confirmationLauncher
        onDispose { viewModel.confirmationLauncher = null }
    }

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
                        "The model is delivered with the app installation — once ready, " +
                        "everything works fully offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${model.approxSizeBytes.toGb()} · ${model.license} · " +
                            when (model.source) {
                                is ModelSource.AiPacks -> "via Google Play"
                                is ModelSource.BundledAssets -> "included in the app"
                            },
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
                LinearProgressIndicator(
                    progress = { (status.downloadedBytes.toFloat() / status.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${status.downloadedBytes.toGb()} of ${status.totalBytes.toGb()} · Google Play",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { onAction(Interaction.OnCancelClick(model.id)) }) {
                        Text("Cancel")
                    }
                }
            }
        }

        is ModelStatus.Assembling -> {
            Column {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Preparing model…",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        is ModelStatus.WaitingForWifi -> {
            Column {
                Text(
                    text = "Waiting for Wi-Fi. You can allow the download on mobile data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onAction(Interaction.OnConfirmDownloadClick) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download now")
                }
            }
        }

        is ModelStatus.RequiresConfirmation -> {
            Button(
                onClick = { onAction(Interaction.OnConfirmDownloadClick) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Approve download")
            }
        }

        is ModelStatus.NotInstalled -> {
            Button(
                onClick = { onAction(Interaction.OnDownloadClick(model.id)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when (model.source) {
                        is ModelSource.AiPacks -> "Download via Google Play"
                        is ModelSource.BundledAssets -> "Prepare model"
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
                    Text("Retry")
                }
            }
        }
    }
}

private fun Long.toGb(): String =
    String.format(Locale.US, "%.1f GB", this / 1_073_741_824.0)
