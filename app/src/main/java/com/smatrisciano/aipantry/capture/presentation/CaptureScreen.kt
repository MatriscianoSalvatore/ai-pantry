package com.smatrisciano.aipantry.capture.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.util.Size
import androidx.activity.compose.LocalActivity
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Kitchen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smatrisciano.aipantry.R
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.ScanCell
import com.smatrisciano.aipantry.capture.domain.ScanProgress
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import com.smatrisciano.aipantry.capture.presentation.CaptureActions.Interaction
import com.smatrisciano.aipantry.capture.presentation.CaptureActions.Navigation
import com.smatrisciano.aipantry.core.presentation.composables.CountPill
import com.smatrisciano.aipantry.core.presentation.composables.EmojiAvatar
import com.smatrisciano.aipantry.core.presentation.composables.EnginePill
import com.smatrisciano.aipantry.core.presentation.composables.WaitProgressBar
import com.smatrisciano.aipantry.core.presentation.theme.extendedColors
import com.smatrisciano.aipantry.core.presentation.utils.ObserveAsEvents
import com.smatrisciano.aipantry.inventory.presentation.composables.ingredientEmoji
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import androidx.compose.ui.geometry.Size as DrawSize

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

    LightSystemBarIcons()

    // The camera is dark in both themes: white is the default content (and ripple) colour here
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (hasCameraPermission) {
                CameraContent(state = state, onAction = onAction)
            } else {
                PermissionRequest(
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onClose = { onAction(Navigation.GoBack) }
                )
            }

            if (state.showResults) {
                ModalBottomSheet(
                    onDismissRequest = { onAction(Interaction.OnResultsDismissed) },
                    // Outside the sheet's surface and anchors: a short sheet stays at the bottom,
                    // a long scan stops below the status bar instead of running under it
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    LightStatusBarIconsInSheet()
                    DetectionResults(state = state, onAction = onAction)
                }
            }
        }
    }
}

/**
 * The camera is always dark, whatever the app theme: light system bar icons while it is
 * shown, then the app's own appearance again.
 *
 * Tied to the destination being resumed, not to composition: the icons go back as soon as the
 * pop starts rather than after the exit animation, and they go back to the theme's value, not to
 * a captured one that a second camera instance (Scan tapped again mid-exit) could have changed.
 */
@Composable
private fun LightSystemBarIcons() {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()
    LifecycleResumeEffect(window, view, darkTheme) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = false
        // The 3-button bar and the gesture handle sit on the dark bottom scrim too
        controller.isAppearanceLightNavigationBars = false
        onPauseOrDispose {
            // What enableEdgeToEdge sets from the system theme, which the app theme follows
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

/**
 * The results sheet runs in its own window, and Material gives it dark status bar icons in the
 * light theme: over the dimmed camera they would all but vanish. Light ones there too; the
 * navigation bar keeps Material's choice, as it sits on the light sheet.
 */
@Composable
private fun LightStatusBarIconsInSheet() {
    val sheetWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(sheetWindow) {
        sheetWindow?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        }
        onDispose {}
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
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            decodeGalleryImage(context, it)?.let { bitmap ->
                onAction(Interaction.OnPhotoCaptured(bitmap))
            }
        }
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

    // Freeze frame: after the shot the photo is shown, not the live preview. Cropped to
    // fill the screen it loses its edges: while the model looks at it, it blurs into a
    // backdrop and the whole photo sits in the frame (see Viewfinder)
    state.capturedPhoto?.let { photo ->
        val backdrop by animateFloatAsState(
            targetValue = if (state.isAnalyzing) 1f else 0f,
            animationSpec = tween(BACKDROP_MILLIS),
            label = "photoBackdrop"
        )
        Image(
            bitmap = photo.asImageBitmap(),
            contentDescription = stringResource(R.string.captured_photo),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(BackdropBlur * backdrop)
                .drawWithContent {
                    drawContent()
                    drawRect(Color.Black.copy(alpha = BACKDROP_DIM * backdrop))
                }
        )
    }

    CameraScrims()

    Column(modifier = Modifier.fillMaxSize()) {
        CaptureTopBar(onClose = { onAction(Navigation.GoBack) }) {
            TargetSelector(
                selected = state.target,
                onSelect = { onAction(Interaction.OnTargetSelected(it)) }
            )
        }

        Viewfinder(
            isAnalyzing = state.isAnalyzing,
            photo = state.capturedPhoto,
            scan = state.scan,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 16.dp)
        )

        BottomControls(
            state = state,
            onAction = onAction,
            onShutterClick = {
                takePhoto(context, imageCapture) { bitmap ->
                    onAction(Interaction.OnPhotoCaptured(bitmap))
                }
            },
            onGalleryClick = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                // Same room in every state: the viewfinder above doesn't jump when
                // the shutter gives way to the progress card
                .heightIn(min = ControlsMinHeight)
        )
    }
}

/**
 * Dark gradients between the camera image (live preview or captured photo) and the
 * controls on top: a lit fridge is often brighter than the white text and buttons.
 */
@Composable
private fun CameraScrims() {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.25f)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.8f),
                        0.55f to Color.Black.copy(alpha = 0.4f),
                        1f to Color.Transparent
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.52f)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Black.copy(alpha = 0.9f)
                    )
                )
        )
    }
}

/** Close on the left, an optional centred control, and a matching blank on the right. */
@Composable
private fun CaptureTopBar(
    onClose: () -> Unit,
    center: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            // 2dp less than the 16/8 margins: the close circle sits 2dp inside its 48dp target
            .padding(start = 14.dp, end = 14.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassCircleButton(
            icon = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.close),
            onClick = onClose
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            center()
        }
        // Mirrors the close button's touch target, so the centre control sits at the true centre
        Spacer(modifier = Modifier.size(MinTouchTarget))
    }
}

/** Icon button on a translucent dark circle: reads on any camera image. */
@Composable
private fun GlassCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = TopButtonSize,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            // A 44dp circle still gets a 48dp touch target
            .minimumInteractiveComponentSize()
            .size(size)
            .clip(CircleShape)
            .background(GlassColor)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

/** Fridge / pantry switch: a segmented control on glass, the selected half solid white. */
@Composable
private fun TargetSelector(
    selected: ScanTarget,
    onSelect: (ScanTarget) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(GlassColor)
            .padding(4.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TargetSegment(
            icon = Icons.Rounded.Kitchen,
            label = stringResource(R.string.fridge),
            selected = selected == ScanTarget.FRIDGE,
            onClick = { onSelect(ScanTarget.FRIDGE) }
        )
        TargetSegment(
            icon = Icons.Rounded.ShoppingBasket,
            label = stringResource(R.string.pantry),
            selected = selected == ScanTarget.PANTRY,
            onClick = { onSelect(ScanTarget.PANTRY) }
        )
    }
}

@Composable
private fun TargetSegment(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    // Fades to a transparent white, not to transparent black: no grey midway
    val containerColor by animateColorAsState(
        targetValue = if (selected) Color.White else Color.White.copy(alpha = 0f),
        label = "segmentContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) InkColor else Color.White.copy(alpha = 0.85f),
        label = "segmentContent"
    )
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1
        )
    }
}

/**
 * Corner brackets framing the shot. While the model looks at the photo, the photo sits
 * in the frame whole, whatever its shape, in brackets of the AI accent: the regions it
 * is looking at are drawn on it, and what it has found so far pops up over it.
 */
@Composable
private fun Viewfinder(
    isAnalyzing: Boolean,
    photo: Bitmap?,
    scan: ScanProgress?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = !isAnalyzing,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.matchParentSize()
        ) {
            // Their line just inside the frame, so they touch its edges
            Brackets(
                color = Color.White.copy(alpha = 0.9f),
                radius = BracketRadius,
                offset = -BracketStroke / 2,
                modifier = Modifier.fillMaxSize()
            )
        }
        AnimatedVisibility(
            visible = isAnalyzing && photo != null,
            enter = fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 1.04f),
            exit = fadeOut(tween(250)),
            modifier = Modifier.matchParentSize()
        ) {
            // Still the shot's photo while it fades out after the analysis
            photo?.let { AnalyzedPhoto(photo = it, scan = scan, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/**
 * The photo the model is looking at, whole and in its own shape, centred in the
 * space it has: the regions being looked at drawn on it, or a scan line sweeping it
 * for detectors that look at the whole photo at once. Brackets frame it a little way
 * out, their corners turning around the photo's own.
 */
@Composable
private fun AnalyzedPhoto(
    photo: Bitmap,
    scan: ScanProgress?,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.extendedColors.aiGradient[1]
    val shape = RoundedCornerShape(PhotoCorner)
    Box(
        // Room for the brackets around the photo
        modifier = modifier.padding(FrameGap + BracketStroke),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.aspectRatio(photo.width.toFloat() / photo.height)) {
            Image(
                bitmap = photo.asImageBitmap(),
                // The backdrop behind already describes the photo
                contentDescription = null,
                // The box has the photo's shape: nothing is cut
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
            )
            if (scan == null) {
                ScanLine(
                    color = accent,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                )
            } else {
                ScanGridOverlay(
                    scan = scan,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                )
                FoundIngredients(
                    found = scan.found,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                )
            }
            // Concentric with the photo's corners, FrameGap outside its edges
            Brackets(
                color = accent,
                radius = PhotoCorner + FrameGap + BracketStroke / 2,
                offset = FrameGap + BracketStroke / 2,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Four rounded corner brackets around the space they are given: their line runs
 * [offset] outside its edges (inside, when negative) and turns with [radius].
 */
@Composable
private fun Brackets(
    color: Color,
    radius: Dp,
    offset: Dp,
    modifier: Modifier = Modifier
) {
    Spacer(
        modifier = modifier.drawWithCache {
            val stroke = BracketStroke.toPx()
            val turn = radius.toPx()
            val arm = turn + BracketStraight.toPx()
            // Where the line runs, from the top-left corner of the space
            val edge = -offset.toPx()
            // Top-left corner only: the other three are its mirror images
            val corner = Path().apply {
                moveTo(edge, edge + arm)
                lineTo(edge, edge + turn)
                arcTo(
                    rect = Rect(edge, edge, edge + 2 * turn, edge + 2 * turn),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false
                )
                lineTo(edge + arm, edge)
            }
            val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                for (scaleX in MIRRORS) {
                    for (scaleY in MIRRORS) {
                        scale(scaleX, scaleY, pivot = center) {
                            drawPath(corner, color, style = style)
                        }
                    }
                }
            }
        }
    )
}

/** A thin bright line with a soft glow band, sweeping up and down while the model works. */
@Composable
private fun ScanLine(
    color: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "scanLine")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SCAN_SWEEP_MILLIS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanProgress"
    )
    val lineColor = lerp(color, Color.White, 0.55f)

    Canvas(
        modifier = modifier
            .clipToBounds()
            // Offscreen, so the side fade below masks only the line and its glow
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        val y = size.height * (0.04f + 0.92f * progress)
        val glow = ScanGlowHeight.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.5f to color.copy(alpha = 0.35f),
                1f to Color.Transparent,
                startY = y - glow / 2,
                endY = y + glow / 2
            )
        )
        drawLine(
            color = lineColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = ScanLineStroke.toPx()
        )
        // Fade both ends: the sweep reads as light, not as a hard-edged bar
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.2f to Color.Black,
                0.8f to Color.Black,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }
}

/** What the bottom of the camera shows; drives the cross-fade between the three. */
private sealed interface ControlsMode {
    data class Error(val error: CaptureError) : ControlsMode
    data object Analyzing : ControlsMode
    data object Idle : ControlsMode
}

@Composable
private fun BottomControls(
    state: CaptureState,
    onAction: (CaptureActions) -> Unit,
    onShutterClick: () -> Unit,
    onGalleryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val error = state.error
    val mode = when {
        error != null -> ControlsMode.Error(error)
        state.isAnalyzing -> ControlsMode.Analyzing
        else -> ControlsMode.Idle
    }

    AnimatedContent(
        targetState = mode,
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 60)) +
                slideInVertically(tween(220, delayMillis = 60)) { it / 8 })
                .togetherWith(fadeOut(tween(120)))
                .using(SizeTransform(clip = false))
        },
        label = "captureControls"
    ) { target ->
        // AnimatedContent passes its minimum height down: without this Box the cards
        // would stretch to fill it instead of sitting at the bottom
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            when (target) {
                is ControlsMode.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // A failed shot must not hide what earlier shots found, nor the way to add it
                    if (state.accumulated.isNotEmpty()) {
                        ReviewPill(
                            count = state.accumulated.size,
                            onClick = { onAction(Interaction.OnShowResultsClick) }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    DetectionError(
                        message = stringResource(
                            when (target.error) {
                                CaptureError.NO_INGREDIENTS -> R.string.error_no_ingredients
                                CaptureError.DETECTION_FAILED -> R.string.error_detection_failed
                            }
                        ),
                        onRetry = { onAction(Interaction.OnRetryClick) }
                    )
                }

                ControlsMode.Analyzing -> AnalyzingCard(scan = state.scan)

                ControlsMode.Idle -> IdleControls(
                    accumulatedCount = state.accumulated.size,
                    target = state.target,
                    // Off as soon as the analysis starts, even while this fades out: a second
                    // shot must not start a second analysis
                    enabled = mode == ControlsMode.Idle,
                    onReviewClick = { onAction(Interaction.OnShowResultsClick) },
                    onShutterClick = onShutterClick,
                    onGalleryClick = onGalleryClick
                )
            }
        }
    }
}

/** Translucent dark card for the messages over the camera image. */
@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlassCardShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), GlassCardShape)
            .padding(20.dp),
        content = content
    )
}

@Composable
private fun DetectionError(
    message: String,
    onRetry: () -> Unit
) {
    GlassCard {
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        WhiteButton(
            text = stringResource(R.string.take_another_photo),
            onClick = onRetry,
            minHeight = 52.dp
        )
    }
}

@Composable
private fun AnalyzingCard(scan: ScanProgress?) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp,
                trackColor = Color.White.copy(alpha = 0.2f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.analyzing_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White
                )
                Text(
                    text = stringResource(R.string.analyzing_caption),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (scan != null) {
            WaitProgressBar(
                progress = scan.doneRegions.toFloat() / scan.totalRegions.coerceAtLeast(1),
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
                textColor = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.scan_regions_done, scan.doneRegions, scan.totalRegions),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = Color.White.copy(alpha = 0.7f)
            )
        } else {
            // A detector that looks at the whole photo at once can't tell how far it is
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp
            )
        }
    }
}

/**
 * The scan on the photo, one pass at a time: the photo split into the grid of that
 * pass's regions, one cell each where the region lies. Cells still to be looked at
 * are dimmed, each one lights up as its region is done, and the ones being looked
 * at right now are outlined. Cells never overlap, however much the regions behind
 * them do (each region reaches a little into its neighbours).
 */
@Composable
private fun ScanGridOverlay(
    scan: ScanProgress,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.extendedColors.aiGradient[1]
    val outline = lerp(accent, Color.White, 0.35f)
    // The pass on screen: the first one not finished yet, or the last once all are
    val pass = scan.grids.indices
        .firstOrNull { pass -> scan.done.count { it.pass == pass } < scan.grids[pass].cells }
        ?: scan.grids.lastIndex
    val grid = scan.grids.getOrNull(pass) ?: return

    // Each cell lights up on its own as its region is done
    val scope = rememberCoroutineScope()
    val lit = remember(pass) { mutableStateMapOf<ScanCell, Animatable<Float, AnimationVector1D>>() }
    LaunchedEffect(scan.done, pass) {
        for (cell in scan.done) {
            if (cell.pass != pass || cell in lit) continue
            val light = Animatable(0f)
            lit[cell] = light
            scope.launch { light.animateTo(1f, tween(CELL_LIGHT_MILLIS)) }
        }
    }
    // The next pass dims the photo again, gently rather than all at once
    val dim = remember(pass) { Animatable(if (pass == 0) 1f else 0f) }
    LaunchedEffect(pass) { dim.animateTo(1f, tween(PASS_DIM_MILLIS)) }

    val active = scan.active.filter { it.pass == pass }
    Canvas(modifier = modifier) {
        val cellWidth = size.width / grid.columns
        val cellHeight = size.height / grid.rows
        val cellSize = DrawSize(cellWidth, cellHeight)
        for (row in 0 until grid.rows) {
            for (column in 0 until grid.columns) {
                val light = lit[ScanCell(pass, row, column)]?.value ?: 0f
                val topLeft = Offset(column * cellWidth, row * cellHeight)
                drawRect(Color.Black.copy(alpha = CELL_DIM * dim.value * (1f - light)), topLeft, cellSize)
                if (light > 0f) drawRect(accent.copy(alpha = 0.1f * light), topLeft, cellSize)
            }
        }
        val gridLine = Color.White.copy(alpha = 0.18f)
        val gridStroke = 1.dp.toPx()
        for (column in 1 until grid.columns) {
            val x = column * cellWidth
            drawLine(gridLine, Offset(x, 0f), Offset(x, size.height), gridStroke)
        }
        for (row in 1 until grid.rows) {
            val y = row * cellHeight
            drawLine(gridLine, Offset(0f, y), Offset(size.width, y), gridStroke)
        }
        // Their line runs just inside the cell; at the photo's corners it turns with them
        val line = RegionStroke.toPx()
        val turn = CornerRadius(PhotoCorner.toPx() - line / 2)
        for (cell in active) {
            val top = cell.row == 0
            val bottom = cell.row == grid.rows - 1
            val left = cell.column == 0
            val right = cell.column == grid.columns - 1
            val bounds = Rect(
                left = cell.column * cellWidth + line / 2,
                top = cell.row * cellHeight + line / 2,
                right = (cell.column + 1) * cellWidth - line / 2,
                bottom = (cell.row + 1) * cellHeight - line / 2
            )
            val shape = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = bounds,
                        topLeft = if (top && left) turn else CornerRadius.Zero,
                        topRight = if (top && right) turn else CornerRadius.Zero,
                        bottomRight = if (bottom && right) turn else CornerRadius.Zero,
                        bottomLeft = if (bottom && left) turn else CornerRadius.Zero
                    )
                )
            }
            drawPath(shape, accent.copy(alpha = 0.22f))
            drawPath(shape, outline, style = Stroke(width = line))
        }
    }
}

/**
 * What the scan has found so far, as chips that pop up one at a time. In the order
 * they turned up rather than by score, so a chip stays where it first appeared.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoundIngredients(
    found: List<DetectedIngredient>,
    modifier: Modifier = Modifier
) {
    var names by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(found) {
        val current = found.map { it.name }
        names = names.filter { it in current } + current.filterNot { it in names }
    }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (name in names) {
            key(name) {
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
                }
                FoundChip(
                    name = name,
                    modifier = Modifier.graphicsLayer {
                        alpha = appear.value.coerceIn(0f, 1f)
                        scaleX = 0.7f + 0.3f * appear.value
                        scaleY = 0.7f + 0.3f * appear.value
                    }
                )
            }
        }
    }
}

/** On dark glass, like the hint above the shutter: the photo shows through. */
@Composable
private fun FoundChip(name: String, modifier: Modifier = Modifier) {
    val emoji = remember(name) { ingredientEmoji(name) }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = emoji,
            // Platform default font: the glyph comes from the emoji font anyway
            style = TextStyle(fontSize = 14.sp, lineHeight = 18.sp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1
        )
    }
}

/** Above the shutter: the way back to the results, or a hint on what to frame. */
private sealed interface ScanPill {
    data class Review(val count: Int) : ScanPill
    data class Hint(val target: ScanTarget) : ScanPill
}

@Composable
private fun IdleControls(
    accumulatedCount: Int,
    target: ScanTarget,
    enabled: Boolean,
    onReviewClick: () -> Unit,
    onShutterClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val targetPill =
            if (accumulatedCount > 0) ScanPill.Review(accumulatedCount) else ScanPill.Hint(target)
        AnimatedContent(
            targetState = targetPill,
            // A new count updates the pill in place; only switching pill (or hint) cross-fades
            contentKey = { pill -> if (pill is ScanPill.Review) ScanPill.Review::class else pill },
            transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(120))) },
            contentAlignment = Alignment.Center,
            label = "scanPill"
        ) { pill ->
            when (pill) {
                is ScanPill.Review -> ReviewPill(count = pill.count, onClick = onReviewClick)
                is ScanPill.Hint -> HintPill(target = pill.target)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                GlassCircleButton(
                    icon = Icons.Rounded.PhotoLibrary,
                    contentDescription = stringResource(R.string.pick_from_gallery),
                    onClick = onGalleryClick,
                    size = 52.dp,
                    enabled = enabled
                )
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ShutterButton(onClick = onShutterClick, enabled = enabled)
            }
            // Empty third slot: keeps the shutter at the centre of the screen
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/** Tappable: it is how to reopen the results sheet after closing it. */
@Composable
private fun ReviewPill(
    count: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White,
        contentColor = InkColor
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = primaryOnWhite(),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = pluralStringResource(R.plurals.review_scan, count, count),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = InkColor.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun HintPill(target: ScanTarget) {
    Text(
        text = stringResource(
            if (target == ScanTarget.FRIDGE) R.string.point_at_fridge else R.string.point_at_pantry
        ),
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

/**
 * Brand green that reads on a white pill in both themes: the dark scheme's primary is a
 * pastel made for dark surfaces, its inversePrimary the deep green made for light ones.
 */
@Composable
private fun primaryOnWhite(): Color = with(MaterialTheme.colorScheme) {
    if (background.luminance() < 0.5f) inversePrimary else primary
}

/** White ring and disc; the disc sinks a little while pressed, like a physical shutter. */
@Composable
private fun ShutterButton(
    onClick: () -> Unit,
    enabled: Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val discScale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        label = "shutterScale"
    )
    val description = stringResource(R.string.scan)

    Box(
        modifier = Modifier
            .size(80.dp)
            .border(4.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = discScale
                    scaleY = discScale
                }
                .background(Color.White, CircleShape)
        )
    }
}

/** Solid white call to action for the dark camera screen. */
@Composable
private fun WhiteButton(
    text: String,
    onClick: () -> Unit,
    minHeight: Dp
) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = InkColor),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DetectionResults(
    state: CaptureState,
    onAction: (CaptureActions) -> Unit
) {
    val count = state.accumulated.size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            // The sheet already pads its content by the navigation bar
            .padding(top = 4.dp, bottom = 16.dp)
    ) {
        Text(
            text = pluralStringResource(R.plurals.ingredients_found, count, count),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.results_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        EnginePill(engineName = state.engineName)
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            itemsIndexed(state.accumulated, key = { _, detection -> detection.name }) { index, detection ->
                DetectionRow(
                    detection = detection,
                    shape = groupedRowShape(index, count),
                    showDivider = index > 0,
                    onRemove = { onAction(Interaction.OnDetectionRemoved(detection.name)) },
                    modifier = Modifier.animateItem()
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onAction(Interaction.OnAddToPantryClick) },
                enabled = !state.isSaving && state.accumulated.isNotEmpty(),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                // Named after where the ingredients come from: fridge, pantry or both
                val targets = state.accumulatedTargets.ifEmpty { setOf(state.target) }
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        when (targets) {
                            setOf(ScanTarget.FRIDGE) -> R.string.add_to_fridge
                            setOf(ScanTarget.PANTRY) -> R.string.add_to_pantry
                            else -> R.string.add_to_fridge_and_pantry
                        }
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Shrinks before the count pill does
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(10.dp))
                CountPill(count = count)
            }
            OutlinedButton(
                onClick = { onAction(Interaction.OnScanAnotherClick) },
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.scan_another),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Grouped list: only the outer corners of the block are rounded. */
private fun groupedRowShape(index: Int, count: Int): Shape = when {
    count == 1 -> RoundedCornerShape(GroupedRadius)
    index == 0 -> RoundedCornerShape(topStart = GroupedRadius, topEnd = GroupedRadius)
    index == count - 1 -> RoundedCornerShape(bottomStart = GroupedRadius, bottomEnd = GroupedRadius)
    else -> RectangleShape
}

@Composable
private fun DetectionRow(
    detection: DetectedIngredient,
    shape: Shape,
    showDivider: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.extendedColors.card)
    ) {
        if (showDivider) {
            // Starts after the avatar, as in a settings list
            HorizontalDivider(
                modifier = Modifier.padding(start = RowDividerInset),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Tight end padding: the 48dp remove target lines the X up with the margin
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val emoji = remember(detection.name) { ingredientEmoji(detection.name) }
            EmojiAvatar(emoji = emoji, size = 40.dp)
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = detection.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            ConfidencePill(confidence = detection.confidence)
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.remove_ingredient, detection.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Detection confidence, coloured by how much to trust it. */
@Composable
private fun ConfidencePill(confidence: Float) {
    val extended = MaterialTheme.extendedColors
    val scheme = MaterialTheme.colorScheme
    val (contentColor, containerColor) = when {
        confidence >= 0.7f -> extended.success to extended.success.copy(alpha = 0.12f)
        confidence >= 0.4f -> extended.warning to extended.warning.copy(alpha = 0.14f)
        else -> scheme.onSurfaceVariant to scheme.surfaceContainerHigh
    }
    Text(
        text = "${(confidence * 100).toInt()}%",
        // Tabular digits: the pills line up down the list
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = contentColor,
        modifier = Modifier
            .background(containerColor, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun PermissionRequest(
    onRequest: () -> Unit,
    onClose: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(Color.White.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoCamera,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.camera_permission_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.camera_permission_rationale),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))
            WhiteButton(
                text = stringResource(R.string.grant_camera_access),
                onClick = onRequest,
                minHeight = 56.dp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.private_on_device),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }

        CaptureTopBar(onClose = onClose)
    }
}

// Translucent black: controls stay legible on any camera image
private val GlassColor = Color.Black.copy(alpha = 0.35f)

// Near-black content on the white controls
private val InkColor = Color(0xFF111813)

private val GlassCardShape = RoundedCornerShape(24.dp)
private val TopButtonSize = 44.dp
private val MinTouchTarget = 48.dp

// Hint pill (40) + gap (20) + shutter (80)
private val ControlsMinHeight = 140.dp

private val GroupedRadius = 20.dp

// Row start padding (16) + avatar (40) + gap (14)
private val RowDividerInset = 70.dp

private val BracketStroke = 3.dp
// Straight part of a bracket, after its turn
private val BracketStraight = 12.dp
private val BracketRadius = 18.dp

// The analysed photo's corners: the regions on it and the brackets around it turn with them
private val PhotoCorner = 16.dp
private val FrameGap = 6.dp
private val MIRRORS = floatArrayOf(1f, -1f)

private val ScanGlowHeight = 90.dp
private val ScanLineStroke = 2.dp
private const val SCAN_SWEEP_MILLIS = 1800

// The photo behind the analysed one: blurred and dimmed, so the whole photo in the frame stands out
private val BackdropBlur = 24.dp
private const val BACKDROP_DIM = 0.5f
private const val BACKDROP_MILLIS = 400

private val RegionStroke = 2.5.dp

// Cells of the scan grid: how dark the ones still to be looked at are, how quickly
// one lights up once done, how gently the next pass dims the photo again
private const val CELL_DIM = 0.5f
private const val CELL_LIGHT_MILLIS = 250
private const val PASS_DIM_MILLIS = 400

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
