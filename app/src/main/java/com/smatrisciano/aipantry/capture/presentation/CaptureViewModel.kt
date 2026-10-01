package com.smatrisciano.aipantry.capture.presentation

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import com.smatrisciano.aipantry.capture.presentation.CaptureActions.Interaction
import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.inventory.domain.models.IngredientSource
import com.smatrisciano.aipantry.inventory.domain.repository.InventoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CaptureEvent {
    data object InventorySaved : CaptureEvent
}

class CaptureViewModel(
    private val detector: IngredientDetector,
    private val inventoryRepository: InventoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CaptureState(engineName = detector.engineName))
    val uiState = _uiState.asStateFlow()

    private val _events = Channel<CaptureEvent>()
    val events = _events.receiveAsFlow()

    /** Da quale scan (frigo/dispensa) proviene ogni ingrediente rilevato. */
    private val sourceByName = mutableMapOf<String, ScanTarget>()

    fun onAction(action: Interaction) {
        when (action) {
            is Interaction.OnTargetSelected -> _uiState.update { it.copy(target = action.target) }
            is Interaction.OnPhotoCaptured -> analyze(action.bitmap)
            is Interaction.OnScanAnotherClick -> onScanAnother()
            is Interaction.OnAddToPantryClick -> saveInventory()
            is Interaction.OnDetectionRemoved -> removeDetection(action.name)
            // Retry = torna al preview live per scattare una nuova foto
            is Interaction.OnRetryClick -> _uiState.update {
                it.copy(error = null, capturedPhoto = null, isAnalyzing = false)
            }
            is Interaction.OnResultsDismissed -> _uiState.update { it.copy(showResults = false) }
            is Interaction.OnShowResultsClick -> _uiState.update { it.copy(showResults = true) }
        }
    }

    private fun analyze(bitmap: Bitmap) {
        val target = _uiState.value.target
        _uiState.update { it.copy(isAnalyzing = true, capturedPhoto = bitmap, error = null) }
        viewModelScope.launch {
            runCatching { detector.detect(bitmap, target) }
                .onSuccess { detections ->
                    if (detections.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                isAnalyzing = false,
                                error = CaptureError.NO_INGREDIENTS
                            )
                        }
                        return@onSuccess
                    }
                    detections.forEach { sourceByName.putIfAbsent(it.name.lowercase(), target) }
                    _uiState.update { state ->
                        val merged = (state.accumulated + detections).distinctBy { it.name.lowercase() }
                        state.copy(
                            isAnalyzing = false,
                            lastDetections = detections,
                            accumulated = merged,
                            showResults = true,
                            engineName = detector.engineName
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Ingredient detection failed", error)
                    _uiState.update {
                        it.copy(isAnalyzing = false, error = CaptureError.DETECTION_FAILED)
                    }
                }
        }
    }

    private fun onScanAnother() {
        _uiState.update { state ->
            state.copy(
                showResults = false,
                capturedPhoto = null,
                lastDetections = emptyList(),
                error = null,
                // dopo il frigo si passa automaticamente alla dispensa
                target = if (state.target == ScanTarget.FRIDGE) ScanTarget.PANTRY else state.target
            )
        }
    }

    private fun removeDetection(name: String) {
        _uiState.update { state ->
            state.copy(accumulated = state.accumulated.filterNot { it.name == name })
        }
    }

    private fun saveInventory() {
        val state = _uiState.value
        if (state.accumulated.isEmpty() || state.isSaving) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            inventoryRepository.addAll(
                state.accumulated.map { detection ->
                    Ingredient(
                        name = detection.name,
                        quantity = detection.quantity,
                        confidence = detection.confidence,
                        source = when (sourceByName[detection.name.lowercase()]) {
                            ScanTarget.FRIDGE -> IngredientSource.FRIDGE
                            ScanTarget.PANTRY -> IngredientSource.PANTRY
                            null -> IngredientSource.MANUAL
                        },
                        detectedAtMillis = now
                    )
                }
            )
            _events.send(CaptureEvent.InventorySaved)
        }
    }

    private companion object {
        const val TAG = "CaptureViewModel"
    }
}
