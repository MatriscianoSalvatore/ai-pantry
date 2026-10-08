package com.smatrisciano.aipantry.diagnostics.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smatrisciano.aipantry.R
import com.smatrisciano.aipantry.core.data.ai.BackendChoice
import com.smatrisciano.aipantry.core.data.ai.ModelChoice
import com.smatrisciano.aipantry.core.data.ai.ModelStatus
import com.smatrisciano.aipantry.core.presentation.theme.extendedColors
import com.smatrisciano.aipantry.diagnostics.data.CpuCluster
import com.smatrisciano.aipantry.diagnostics.data.DeviceInfo
import com.smatrisciano.aipantry.diagnostics.data.DeviceSnapshot
import com.smatrisciano.aipantry.diagnostics.data.PowerSource
import com.smatrisciano.aipantry.diagnostics.data.ThermalStatus
import com.smatrisciano.aipantry.diagnostics.presentation.DiagnosticsActions.Interaction
import com.smatrisciano.aipantry.diagnostics.presentation.DiagnosticsActions.Navigation
import com.smatrisciano.aipantry.recipes.domain.CachedList
import com.smatrisciano.aipantry.recipes.domain.CachedRecipe
import com.smatrisciano.aipantry.recipes.domain.DetailsStatus
import com.smatrisciano.aipantry.recipes.domain.ListStatus
import com.smatrisciano.aipantry.recipes.domain.RecipeWork
import org.koin.androidx.compose.koinViewModel
import java.text.NumberFormat

@Composable
fun DiagnosticsScreenRoot(
    viewModel: DiagnosticsViewModel = koinViewModel(),
    onNavigation: (Navigation) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DiagnosticsScreen(
        state = state,
        onAction = { action ->
            when (action) {
                is Interaction -> viewModel.onAction(action)
                is Navigation -> onNavigation(action)
            }
        }
    )
}

/**
 * Hidden page (six taps on the brand mark in the home) with what slows on-device
 * AI down at this moment: heat and CPU throttling, memory, the state of the model,
 * and the recipes kept in memory, which can be cleared from here.
 */
@Composable
private fun DiagnosticsScreen(
    state: DiagnosticsState,
    onAction: (DiagnosticsActions) -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<GemmaWeights?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopBar(onBack = { onAction(Navigation.GoBack) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = ScreenPadding,
                end = ScreenPadding,
                bottom = padding.calculateBottomPadding() + 32.dp
            )
        ) {
            item(key = "header") { Header() }
            state.device?.let { device ->
                item(key = "heat") { HeatSection(device) }
                item(key = "memory") { MemorySection(device) }
            }
            state.model?.let { model ->
                item(key = "model") {
                    ModelSection(
                        model = model,
                        work = state.work,
                        aheadAllowed = state.aheadAllowed,
                        onRetryGpuClick = { onAction(Interaction.OnRetryGpuClick) },
                        onStopClick = { onAction(Interaction.OnStopClick) }
                    )
                }
            }
            state.selection?.let { selection ->
                item(key = "choices") { ChoicesSection(selection, onAction) }
            }
            state.weights?.let { weights ->
                item(key = "weights") {
                    WeightsSection(
                        weights = weights,
                        onRemoveClick = { confirmRemove = it },
                        onRestoreClick = { onAction(Interaction.OnRestoreModelClick(it)) }
                    )
                }
            }
            state.learned?.let { learned ->
                item(key = "learned") { LearnedSection(learned) }
            }
            item(key = "cache") {
                CacheSection(cache = state.cache, onClearClick = { confirmClear = true })
            }
            state.deviceInfo?.let { info ->
                item(key = "device") { DeviceSection(info, state.appVersion) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.diag_clear_cache_title)) },
            text = { Text(stringResource(R.string.diag_clear_cache_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onAction(Interaction.OnClearRecipeCacheClick)
                    }
                ) {
                    Text(
                        text = stringResource(R.string.diag_clear_cache_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.diag_cancel))
                }
            }
        )
    }
    confirmRemove?.let { model ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.diag_remove_gemma_title, model.name)) },
            text = { Text(stringResource(R.string.diag_remove_gemma_body, bytes((model.bytes ?: 0L) + model.cacheBytes))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = null
                        onAction(Interaction.OnRemoveModelClick(model.id))
                    }
                ) {
                    Text(
                        text = stringResource(R.string.diag_remove_gemma_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text(stringResource(R.string.diag_cancel))
                }
            }
        )
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledIconButton(
            onClick = onBack,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun Header() {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = stringResource(R.string.diag_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.diag_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HeatSection(device: DeviceSnapshot) {
    val colors = MaterialTheme.extendedColors
    val scheme = MaterialTheme.colorScheme
    Section(title = stringResource(R.string.diag_section_heat)) {
        InfoRow(
            label = stringResource(R.string.diag_thermal_status),
            value = thermalLabel(device.thermalStatus),
            dot = when (device.thermalStatus) {
                ThermalStatus.NONE, ThermalStatus.LIGHT -> colors.success
                ThermalStatus.MODERATE -> colors.warning
                ThermalStatus.UNKNOWN -> scheme.outline
                else -> scheme.error
            },
            first = true
        )
        InfoRow(
            label = stringResource(R.string.diag_thermal_headroom),
            caption = stringResource(R.string.diag_thermal_headroom_caption),
            value = device.thermalHeadroom?.let { "%.2f".format(it) } ?: stringResource(R.string.diag_unavailable),
            valueColor = when {
                device.thermalHeadroom == null -> scheme.onSurfaceVariant
                device.thermalHeadroom < 0.7f -> colors.success
                device.thermalHeadroom < 0.9f -> colors.warning
                else -> scheme.error
            }
        )
        InfoRow(
            label = stringResource(R.string.diag_battery_temperature),
            value = device.batteryTemperatureCelsius?.let { "%.1f °C".format(it) }
                ?: stringResource(R.string.diag_unavailable),
            valueColor = when {
                device.batteryTemperatureCelsius == null -> scheme.onSurfaceVariant
                device.batteryTemperatureCelsius < 38f -> scheme.onSurface
                device.batteryTemperatureCelsius < 42f -> colors.warning
                else -> scheme.error
            }
        )
        if (device.clusters.none { it.capKhz != null && it.maxKhz != null }) {
            InfoRow(label = stringResource(R.string.diag_cpu_unavailable), value = "")
        } else {
            device.clusters.forEachIndexed { index, cluster ->
                ClusterRow(cluster = cluster, role = clusterRole(index, device.clusters.size))
            }
        }
        InfoRow(
            label = stringResource(R.string.diag_battery),
            value = batteryLabel(device)
        )
        InfoRow(
            label = stringResource(R.string.diag_power_save),
            value = stringResource(if (device.powerSaveMode) R.string.diag_on else R.string.diag_off),
            valueColor = if (device.powerSaveMode) colors.warning else scheme.onSurface
        )
    }
}

/**
 * A group of cores: how much of its top frequency the system still lets it reach.
 * Below 100% the chip is being cooled down, and inference slows down by as much.
 */
@Composable
private fun ClusterRow(cluster: CpuCluster, role: ClusterRole) {
    val cap = cluster.capKhz
    val max = cluster.maxKhz
    val share = if (cap != null && max != null && max > 0) cap.toFloat() / max else null
    val cores = coresLabel(cluster.cores)
    InfoRow(
        label = stringResource(
            when (role) {
                ClusterRole.LITTLE -> R.string.diag_cpu_cores_little
                ClusterRole.MID -> R.string.diag_cpu_cores_mid
                ClusterRole.BIG -> R.string.diag_cpu_cores_big
                ClusterRole.ONLY -> R.string.diag_cpu_cores
            },
            cores
        ),
        caption = stringResource(R.string.diag_cpu_frequencies, ghz(cap), ghz(max), ghz(cluster.currentKhz)),
        value = share?.let { "${(it * 100).toInt()}%" } ?: stringResource(R.string.diag_unavailable),
        valueColor = when {
            share == null -> MaterialTheme.colorScheme.onSurfaceVariant
            share >= 0.9f -> MaterialTheme.extendedColors.success
            share >= 0.6f -> MaterialTheme.extendedColors.warning
            else -> MaterialTheme.colorScheme.error
        }
    )
}

@Composable
private fun MemorySection(device: DeviceSnapshot) {
    Section(title = stringResource(R.string.diag_section_memory)) {
        InfoRow(
            label = stringResource(R.string.diag_ram_available),
            caption = if (device.lowMemory) stringResource(R.string.diag_ram_low) else null,
            value = stringResource(
                R.string.diag_ram_available_value,
                bytes(device.availableMemoryBytes),
                bytes(device.totalMemoryBytes)
            ),
            valueColor = if (device.lowMemory) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            first = true
        )
        InfoRow(
            label = stringResource(R.string.diag_app_memory),
            value = bytes(device.appMemoryBytes)
        )
    }
}

@Composable
private fun ModelSection(
    model: ModelInfo,
    work: RecipeWork,
    aheadAllowed: Boolean,
    onRetryGpuClick: () -> Unit,
    onStopClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val colors = MaterialTheme.extendedColors
    Section(title = stringResource(R.string.diag_section_model)) {
        InfoRow(
            label = stringResource(R.string.diag_model),
            value = "${model.name} · ${modelStatusLabel(model.status)}",
            first = true
        )
        InfoRow(
            label = stringResource(R.string.diag_model_file),
            value = model.fileBytes?.let { bytes(it) } ?: stringResource(R.string.diag_unavailable)
        )
        InfoRow(
            label = stringResource(R.string.diag_engine),
            value = when {
                !model.loaded -> stringResource(R.string.diag_engine_not_loaded)
                model.gpu -> stringResource(R.string.diag_engine_gpu)
                else -> stringResource(R.string.diag_engine_cpu, model.cpuThreads)
            }
        )
        InfoRow(
            label = stringResource(R.string.diag_gpu),
            value = stringResource(if (model.gpuDisabled) R.string.diag_gpu_disabled else R.string.diag_gpu_available),
            valueColor = if (model.gpuDisabled) scheme.onSurfaceVariant else colors.success
        )
        InfoRow(
            label = stringResource(R.string.diag_caches),
            value = if (model.cacheFileCount > 0) {
                stringResource(R.string.diag_caches_value, bytes(model.cacheBytes), model.cacheFileCount)
            } else {
                stringResource(R.string.diag_caches_none)
            },
            valueColor = if (model.cacheFileCount > 0) scheme.onSurface else colors.warning
        )
        InfoRow(
            label = stringResource(R.string.diag_detector),
            value = model.detectorName
        )
        InfoRow(
            label = stringResource(R.string.diag_work),
            value = when (work) {
                RecipeWork.Idle -> stringResource(R.string.diag_work_idle)
                is RecipeWork.WritingList -> stringResource(R.string.diag_work_list, work.round)
                is RecipeWork.WritingDetails -> stringResource(R.string.diag_work_details, work.title)
            },
            dot = if (work == RecipeWork.Idle) null else colors.aiGradient[1]
        )
        InfoRow(
            label = stringResource(R.string.diag_ahead),
            value = stringResource(if (aheadAllowed) R.string.diag_ahead_allowed else R.string.diag_ahead_paused)
        )
    }
    // What the model is writing can be stopped from here, from any screen it is running on
    if (work != RecipeWork.Idle) {
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onStopClick,
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(text = stringResource(R.string.diag_stop_generation), style = MaterialTheme.typography.titleSmall)
        }
    }
    // Given up on, rightly or wrongly (a race can blame it): one tap and the next load tries it again
    if (model.gpuDisabled) {
        Spacer(modifier = Modifier.height(12.dp))
        FilledTonalButton(
            onClick = onRetryGpuClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(text = stringResource(R.string.diag_retry_gpu), style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** The model each task is given to, and the verbose display. A model that isn't there can't be picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoicesSection(selection: ModelSelection, onAction: (DiagnosticsActions) -> Unit) {
    Section(
        title = stringResource(R.string.diag_section_choices),
        caption = stringResource(R.string.diag_choices_caption)
    ) {
        ChoiceRow(
            label = stringResource(R.string.diag_choice_scan),
            selected = selection.scan,
            options = listOf(ModelChoice.AUTO, ModelChoice.NANO, ModelChoice.GEMMA, ModelChoice.CLIP, ModelChoice.EMBEDDING_GEMMA),
            selection = selection,
            first = true,
            onSelect = { onAction(Interaction.OnScanChoice(it)) }
        )
        ChoiceRow(
            label = stringResource(R.string.diag_choice_recipes),
            selected = selection.recipes,
            options = listOf(ModelChoice.AUTO, ModelChoice.NANO, ModelChoice.GEMMA),
            selection = selection,
            onSelect = { onAction(Interaction.OnRecipeChoice(it)) }
        )
        if (selection.gemmaVersions.size > 1) {
            ChipRow(
                label = stringResource(R.string.diag_choice_gemma_version),
                options = selection.gemmaVersions.map { ChipOption(it.id, it.name, it.ready) },
                selectedId = selection.activeGemmaId,
                onSelect = { onAction(Interaction.OnGemmaVersion(it)) }
            )
        }
        ChipRow(
            label = stringResource(R.string.diag_choice_backend, selection.activeGemmaName),
            options = listOf(
                ChipOption(BackendChoice.AUTO.name, stringResource(R.string.diag_choice_auto), true),
                ChipOption(BackendChoice.GPU.name, stringResource(R.string.diag_backend_gpu), true),
                ChipOption(BackendChoice.CPU.name, stringResource(R.string.diag_backend_cpu), true)
            ),
            selectedId = selection.backend.name,
            onSelect = { onAction(Interaction.OnBackendChoice(BackendChoice.valueOf(it))) }
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = RowPadding),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RowPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.diag_verbose), style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.diag_verbose_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = selection.verbose,
                onCheckedChange = { onAction(Interaction.OnVerboseChange(it)) }
            )
        }
    }
}

private class ChipOption(val id: String, val name: String, val present: Boolean)

@Composable
private fun ChoiceRow(
    label: String,
    selected: ModelChoice,
    options: List<ModelChoice>,
    selection: ModelSelection,
    onSelect: (ModelChoice) -> Unit,
    first: Boolean = false
) {
    ChipRow(
        label = label,
        options = options.map { option ->
            ChipOption(
                id = option.name,
                name = choiceName(option),
                present = when (option) {
                    ModelChoice.AUTO -> true
                    ModelChoice.NANO -> selection.nanoPresent
                    ModelChoice.GEMMA -> selection.gemmaReady
                    ModelChoice.CLIP -> selection.clipPresent
                    ModelChoice.EMBEDDING_GEMMA -> selection.embeddingPresent
                }
            )
        },
        selectedId = selected.name,
        first = first,
        onSelect = { id -> onSelect(ModelChoice.valueOf(id)) }
    )
}

/** A label and the options under it as chips; one that isn't there is greyed, unless it is the one picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    label: String,
    options: List<ChipOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    first: Boolean = false
) {
    if (!first) {
        HorizontalDivider(
            modifier = Modifier.padding(start = RowPadding),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
    Column(modifier = Modifier.padding(horizontal = RowPadding, vertical = 14.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option.id == selectedId,
                    // A choice that isn't there can still be let go of
                    enabled = option.present || option.id == selectedId,
                    onClick = { onSelect(option.id) },
                    label = {
                        Text(if (option.present) option.name else stringResource(R.string.diag_choice_missing, option.name))
                    }
                )
            }
        }
    }
}

@Composable
private fun choiceName(choice: ModelChoice): String = stringResource(
    when (choice) {
        ModelChoice.AUTO -> R.string.diag_choice_auto
        ModelChoice.NANO -> R.string.diag_choice_nano
        ModelChoice.GEMMA -> R.string.diag_choice_gemma
        ModelChoice.CLIP -> R.string.diag_choice_clip
        ModelChoice.EMBEDDING_GEMMA -> R.string.diag_choice_embedding
    }
)

/** What each model weighs and where it lives, with Gemma, the one the app owns, removable. */
@Composable
private fun WeightsSection(
    weights: ModelWeights,
    onRemoveClick: (GemmaWeights) -> Unit,
    onRestoreClick: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Section(
        title = stringResource(R.string.diag_section_weights),
        caption = stringResource(R.string.diag_weights_caption)
    ) {
        weights.gemma.forEachIndexed { index, model ->
            InfoRow(
                label = model.name,
                value = model.bytes?.let { bytes(it) } ?: stringResource(R.string.diag_weight_absent),
                caption = when {
                    model.removedByUser -> stringResource(R.string.diag_weight_gemma_removed)
                    model.bytes == null && model.manual -> stringResource(R.string.diag_weight_manual_hint, model.installPath)
                    else -> modelStatusLabel(model.status)
                },
                first = index == 0
            )
            if (model.cacheBytes > 0) {
                InfoRow(
                    label = stringResource(R.string.diag_weight_gemma_cache, model.name),
                    value = bytes(model.cacheBytes),
                    caption = stringResource(R.string.diag_weight_gemma_cache_caption)
                )
            }
        }
        InfoRow(
            label = stringResource(R.string.diag_weight_clip),
            value = weights.clipBytes?.let { bytes(it) } ?: stringResource(R.string.diag_weight_not_in_build),
            caption = stringResource(R.string.diag_weight_clip_caption)
        )
        InfoRow(
            label = stringResource(R.string.diag_weight_embedding),
            value = weights.embeddingBytes?.let { bytes(it) } ?: stringResource(R.string.diag_weight_absent),
            caption = if (weights.embeddingBytes == null) {
                stringResource(R.string.diag_weight_manual_hint, weights.embeddingInstallPath)
            } else {
                stringResource(R.string.diag_weight_embedding_caption)
            }
        )
        InfoRow(
            label = stringResource(R.string.diag_weight_nano),
            value = weights.nanoBytes?.let { bytes(it) }
                ?: stringResource(if (weights.nanoPresent) R.string.diag_weight_size_unknown else R.string.diag_weight_absent),
            caption = listOfNotNull(
                weights.nanoBaseModel,
                stringResource(R.string.diag_weight_nano_caption)
            ).joinToString(" · ")
        )
    }
    weights.gemma.forEach { model ->
        // Taken off, and one the app can provision again: restore. Otherwise, there: delete
        if (model.removedByUser && !model.manual) {
            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(
                onClick = { onRestoreClick(model.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = stringResource(R.string.diag_restore_gemma, model.name),
                    style = MaterialTheme.typography.titleSmall
                )
            }
        } else if (model.bytes != null) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { onRemoveClick(model) },
                shape = CircleShape,
                border = BorderStroke(1.dp, scheme.error),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.diag_remove_gemma, model.name),
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }
}

@Composable
private fun LearnedSection(learned: LearnedWaits) {
    val integers = NumberFormat.getIntegerInstance()
    Section(
        title = stringResource(R.string.diag_section_learned),
        caption = stringResource(R.string.diag_learned_caption)
    ) {
        InfoRow(
            label = stringResource(R.string.diag_learned_reading),
            value = stringResource(R.string.diag_seconds, "%.1f".format(learned.promptReadingMillis / 1000f)),
            first = true
        )
        InfoRow(
            label = stringResource(R.string.diag_learned_recipe),
            value = stringResource(R.string.diag_chars, integers.format(learned.listRecipeChars))
        )
        InfoRow(
            label = stringResource(R.string.diag_learned_details),
            value = stringResource(R.string.diag_chars, integers.format(learned.detailsChars))
        )
    }
}

@Composable
private fun CacheSection(cache: List<CachedList>, onClearClick: () -> Unit) {
    Column(modifier = Modifier.padding(top = SectionGap)) {
        SectionTitle(
            title = stringResource(R.string.diag_section_cache),
            caption = stringResource(R.string.diag_cache_caption)
        )
        if (cache.isEmpty()) {
            Group {
                InfoRow(label = stringResource(R.string.diag_cache_empty), value = "", first = true)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cache.forEach { list -> CachedListCard(list) }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = onClearClick,
            enabled = cache.isNotEmpty(),
            shape = CircleShape,
            border = BorderStroke(
                1.dp,
                if (cache.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
            ),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.DeleteSweep,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.diag_clear_cache),
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

/** A list in memory: what it was written for, how far it got, its recipes and their details. */
@Composable
private fun CachedListCard(list: CachedList) {
    val tags = buildList {
        add(
            stringResource(
                when (list.status) {
                    ListStatus.GENERATING -> R.string.diag_cache_generating
                    ListStatus.DONE -> R.string.diag_cache_done
                    ListStatus.FAILED -> R.string.diag_cache_failed
                }
            )
        )
        add(stringResource(if (list.opened) R.string.diag_cache_opened else R.string.diag_cache_written_ahead))
        if (list.forCurrentInventory) add(stringResource(R.string.diag_cache_current_inventory))
    }
    Group {
        InfoRow(
            label = pluralStringResource(R.plurals.diag_cache_list_title, list.ingredientCount, list.ingredientCount, list.round),
            caption = tags.joinToString(" · "),
            value = "",
            first = true
        )
        list.recipes.forEach { recipe -> CachedRecipeRow(recipe) }
    }
}

@Composable
private fun CachedRecipeRow(recipe: CachedRecipe) {
    val scheme = MaterialTheme.colorScheme
    val colors = MaterialTheme.extendedColors
    val (label, color) = when {
        recipe.details == DetailsStatus.DONE -> R.string.diag_details_done to colors.success
        recipe.details == DetailsStatus.FAILED -> R.string.diag_details_failed to scheme.error
        recipe.partial -> R.string.diag_details_partial to colors.warning
        else -> R.string.diag_details_none to scheme.onSurfaceVariant
    }
    InfoRow(
        label = recipe.title,
        value = stringResource(label),
        valueColor = color
    )
}

@Composable
private fun DeviceSection(info: DeviceInfo, appVersion: String) {
    Section(title = stringResource(R.string.diag_section_device)) {
        InfoRow(label = stringResource(R.string.diag_device_model), value = info.model, first = true)
        info.chip?.let { InfoRow(label = stringResource(R.string.diag_device_chip), value = it) }
        InfoRow(label = stringResource(R.string.diag_device_android), value = info.android)
        InfoRow(label = stringResource(R.string.diag_device_cores), value = "${info.cores}")
        InfoRow(label = stringResource(R.string.diag_app_version), value = appVersion)
    }
}

@Composable
private fun Section(
    title: String,
    caption: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.padding(top = SectionGap)) {
        SectionTitle(title = title, caption = caption)
        Group(content = content)
    }
}

@Composable
private fun SectionTitle(title: String, caption: String?) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() }
    )
    caption?.let {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
}

/** Settings-style block: the rows inside draw their own hairlines. */
@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.extendedColors.card),
        content = content
    )
}

/** A label, an optional caption under it and its value on the right; a hairline above unless [first]. */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    caption: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    dot: Color? = null,
    first: Boolean = false
) {
    if (!first) {
        HorizontalDivider(
            modifier = Modifier.padding(start = RowPadding),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RowPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            caption?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (value.isNotEmpty()) {
            Spacer(modifier = Modifier.width(12.dp))
            dot?.let {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(it, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = valueColor,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // Capped so a long value can't squeeze the label out
                modifier = Modifier.widthIn(max = 200.dp)
            )
        }
    }
}

private enum class ClusterRole { LITTLE, MID, BIG, ONLY }

/** Clusters come slowest first: with three of them, little, mid and big cores. */
private fun clusterRole(index: Int, count: Int): ClusterRole = when {
    count < 2 -> ClusterRole.ONLY
    index == 0 -> ClusterRole.LITTLE
    index == count - 1 -> ClusterRole.BIG
    else -> ClusterRole.MID
}

private fun coresLabel(cores: List<Int>): String =
    if (cores.size > 1 && cores.last() - cores.first() == cores.size - 1) {
        "${cores.first()}–${cores.last()}"
    } else {
        cores.joinToString(", ")
    }

@Composable
private fun thermalLabel(status: ThermalStatus): String = stringResource(
    when (status) {
        ThermalStatus.NONE -> R.string.diag_thermal_none
        ThermalStatus.LIGHT -> R.string.diag_thermal_light
        ThermalStatus.MODERATE -> R.string.diag_thermal_moderate
        ThermalStatus.SEVERE -> R.string.diag_thermal_severe
        ThermalStatus.CRITICAL -> R.string.diag_thermal_critical
        ThermalStatus.EMERGENCY -> R.string.diag_thermal_emergency
        ThermalStatus.SHUTDOWN -> R.string.diag_thermal_shutdown
        ThermalStatus.UNKNOWN -> R.string.diag_thermal_unknown
    }
)

@Composable
private fun batteryLabel(device: DeviceSnapshot): String {
    val percent = device.batteryPercent ?: return stringResource(R.string.diag_unavailable)
    val source = when (device.powerSource) {
        null, PowerSource.BATTERY -> return stringResource(R.string.diag_battery_discharging, percent)
        PowerSource.USB -> R.string.diag_source_usb
        PowerSource.AC -> R.string.diag_source_ac
        PowerSource.WIRELESS -> R.string.diag_source_wireless
        PowerSource.OTHER -> R.string.diag_source_other
    }
    return stringResource(R.string.diag_battery_charging, percent, stringResource(source))
}

@Composable
private fun modelStatusLabel(status: ModelStatus?): String = stringResource(
    when (status) {
        ModelStatus.Ready -> R.string.diag_model_ready
        is ModelStatus.Downloading, ModelStatus.WaitingForWifi, ModelStatus.RequiresConfirmation ->
            R.string.diag_model_downloading
        is ModelStatus.Failed, ModelStatus.NotInstalled -> R.string.diag_model_failed
        ModelStatus.Assembling, null -> R.string.diag_model_preparing
    }
)

private fun ghz(khz: Long?): String = khz?.let { "%.2f GHz".format(it / 1_000_000f) } ?: "—"

private fun bytes(value: Long): String =
    if (value >= 1_000_000_000) "%.2f GB".format(value / 1e9) else "%.0f MB".format(value / 1e6)

private val ScreenPadding = 20.dp
private val RowPadding = 16.dp
private val SectionGap = 28.dp
