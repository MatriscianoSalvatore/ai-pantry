package com.smatrisciano.aipantry.capture.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smatrisciano.aipantry.R
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import com.smatrisciano.aipantry.capture.presentation.CaptureActions.Interaction
import com.smatrisciano.aipantry.capture.presentation.CaptureActions.Navigation
import com.smatrisciano.aipantry.core.presentation.utils.ObserveAsEvents
import com.smatrisciano.aipantry.inventory.presentation.composables.ingredientEmoji
import org.koin.androidx.compose.koinViewModel

@Composable
fun CaptureScreenRoot(
    viewModel: CaptureViewModel = koinViewModel(),
    onNavigation: (Navigation) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            CaptureEvent.InventorySaved -> onNavigation(Navigation.GoBack)
        }
    }

    CaptureScreen(
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
private fun CaptureScreen(
    state: CaptureState,
    onAction: (CaptureActions) -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            CameraContent(state = state, onAction = onAction)
        } else {
            PermissionRequest(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        }

        IconButton(
            onClick = { onAction(Navigation.GoBack) },
            modifier = Modifier
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = Color.White
            )
        }

        if (state.showResults) {
            ModalBottomSheet(
                onDismissRequest = { onAction(Interaction.OnResultsDismissed) },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                DetectionResults(state = state, onAction = onAction)
            }
        }
    }
}

@Composable
private fun CameraContent(
    state: CaptureState,
    onAction: (CaptureActions) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember {
        // The full-res sensor (e.g. 50 MP) would produce bitmaps of hundreds of MB, on
        // top of the ~3 GB LLM already resident in memory during inference.
        ImageCapture.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()
            )
            .build()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            PreviewView(viewContext).also { previewView ->
                val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                    )
                }, ContextCompat.getMainExecutor(viewContext))
            }
        }
    )

    // Freeze frame: after the shot the photo is shown, not the live preview
    state.capturedPhoto?.let { photo ->
        Image(
            bitmap = photo.asImageBitmap(),
            contentDescription = stringResource(R.string.captured_photo),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.navigationBars.asPaddingValues()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.padding(WindowInsets.statusBars.asPaddingValues()))
        Spacer(modifier = Modifier.height(56.dp))

        TargetSelector(
            selected = state.target,
            onSelect = { onAction(Interaction.OnTargetSelected(it)) }
        )

        Spacer(modifier = Modifier.weight(1f))

        if (state.accumulated.isNotEmpty()) {
            // Tappable: it is how to reopen the results sheet after closing it
            Surface(
                onClick = { onAction(Interaction.OnShowResultsClick) },
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.scan_session_count,
                        state.accumulated.size,
                        state.accumulated.size
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (state.error != null) {
            DetectionError(
                message = stringResource(
                    when (state.error) {
                        CaptureError.NO_INGREDIENTS -> R.string.error_no_ingredients
                        CaptureError.DETECTION_FAILED -> R.string.error_detection_failed
                    }
                ),
                onRetry = { onAction(Interaction.OnRetryClick) }
            )
        } else {
            val galleryLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                uri?.let {
                    decodeGalleryImage(context, it)?.let { bitmap ->
                        onAction(Interaction.OnPhotoCaptured(bitmap))
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Symmetric placeholder to keep the shutter centred
                Spacer(modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.width(28.dp))
                ShutterButton(
                    isAnalyzing = state.isAnalyzing,
                    onClick = {
                        takePhoto(context, imageCapture) { bitmap ->
                            onAction(Interaction.OnPhotoCaptured(bitmap))
                        }
                    }
                )
                Spacer(modifier = Modifier.width(28.dp))
                IconButton(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !state.isAnalyzing,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = stringResource(R.string.pick_from_gallery),
                        tint = Color.White
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(
                    when {
                        state.isAnalyzing -> R.string.detecting_ingredients
                        state.target == ScanTarget.FRIDGE -> R.string.point_at_fridge
                        else -> R.string.point_at_pantry
                    }
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DetectionError(
    message: String,
    onRetry: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Black.copy(alpha = 0.6f)
        ) {
            Text(
                text = message,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.take_another_photo))
        }
    }
}

@Composable
private fun TargetSelector(
    selected: ScanTarget,
    onSelect: (ScanTarget) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterChip(
            selected = selected == ScanTarget.FRIDGE,
            onClick = { onSelect(ScanTarget.FRIDGE) },
            label = { Text(stringResource(R.string.fridge)) },
            leadingIcon = { Icon(Icons.Default.Kitchen, contentDescription = null) }
        )
        FilterChip(
            selected = selected == ScanTarget.PANTRY,
            onClick = { onSelect(ScanTarget.PANTRY) },
            label = { Text(stringResource(R.string.pantry)) },
            leadingIcon = { Icon(Icons.Default.ShoppingBasket, contentDescription = null) }
        )
    }
}

@Composable
private fun ShutterButton(
    isAnalyzing: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (isAnalyzing) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier.size(60.dp)
            ) {}
        }
    }
}

@Composable
private fun DetectionResults(
    state: CaptureState,
    onAction: (CaptureActions) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = stringResource(R.string.ingredients_detected),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.detection_engine, state.engineName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.accumulated, key = { it.name }) { detection ->
                DetectionRow(
                    detection = detection,
                    onRemove = { onAction(Interaction.OnDetectionRemoved(detection.name)) }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onAction(Interaction.OnScanAnotherClick) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.scan_another))
            }
            Button(
                onClick = { onAction(Interaction.OnAddToPantryClick) },
                enabled = !state.isSaving && state.accumulated.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.add_to_pantry_count, state.accumulated.size))
            }
        }
    }
}

@Composable
private fun DetectionRow(
    detection: DetectedIngredient,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = ingredientEmoji(detection.name),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = detection.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { detection.confidence },
                        modifier = Modifier.width(96.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${(detection.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.remove_ingredient, detection.name)
                )
            }
        }
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.camera_permission_rationale),
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequest) {
            Text(stringResource(R.string.grant_camera_access))
        }
    }
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onCaptured: (Bitmap) -> Unit
) {
    imageCapture.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = image.use { it.toRotatedBitmap() }
                onCaptured(bitmap)
            }

            override fun onError(exception: ImageCaptureException) {
                // Demo app: don't block the flow, CameraX just logs the error
            }
        }
    )
}

/**
 * Decodes a gallery image into an ARGB software bitmap (needed to compress
 * it to JPEG before passing it to the model); ImageDecoder applies the EXIF
 * rotation on its own.
 */
private fun decodeGalleryImage(context: Context, uri: android.net.Uri): Bitmap? =
    runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }.getOrNull()

private fun ImageProxy.toRotatedBitmap(): Bitmap {
    val bitmap = toBitmap()
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return bitmap
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    bitmap.recycle()
    return rotated
}
