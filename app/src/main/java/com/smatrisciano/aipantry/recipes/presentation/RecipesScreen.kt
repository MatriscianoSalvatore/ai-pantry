package com.smatrisciano.aipantry.recipes.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smatrisciano.aipantry.R
import com.smatrisciano.aipantry.core.presentation.composables.AiOrb
import com.smatrisciano.aipantry.core.presentation.composables.EmojiAvatar
import com.smatrisciano.aipantry.core.presentation.composables.EnginePill
import com.smatrisciano.aipantry.core.presentation.composables.WaitProgressBar
import com.smatrisciano.aipantry.core.presentation.theme.extendedColors
import com.smatrisciano.aipantry.inventory.presentation.composables.ingredientEmoji
import com.smatrisciano.aipantry.recipes.domain.GenerationProgress
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import com.smatrisciano.aipantry.recipes.domain.models.RecipeIngredient
import com.smatrisciano.aipantry.recipes.presentation.RecipesActions.Interaction
import com.smatrisciano.aipantry.recipes.presentation.RecipesActions.Navigation
import com.smatrisciano.aipantry.recipes.presentation.composables.CookingWaitPhrases
import com.smatrisciano.aipantry.recipes.presentation.composables.DifficultyBadge
import com.smatrisciano.aipantry.recipes.presentation.composables.dishEmoji

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

/** Which body is on screen: a failure wins over a generation in progress. */
private enum class Phase { FAILED, GENERATING, LIST }

@Composable
private fun RecipesScreen(
    state: RecipesState,
    onAction: (RecipesActions) -> Unit
) {
    val phase = when {
        state.generationFailed -> Phase.FAILED
        state.isGenerating -> Phase.GENERATING
        else -> Phase.LIST
    }
    // One scroll state per generation: a regenerated list starts from the top,
    // while the position survives a trip to a recipe and back
    val listState = key(state.isGenerating) { rememberLazyListState() }
    val scrolledPastHeader by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RecipesTopBar(
                showTitle = phase == Phase.LIST && scrolledPastHeader,
                showRegenerate = !state.isGenerating,
                onBack = { onAction(Navigation.GoBack) },
                // The button stays tappable while it fades out: ignore taps once generating
                onRegenerate = { if (!state.isGenerating) onAction(Interaction.OnRegenerateClick) }
            )
        }
    ) { padding ->
        Crossfade(
            targetState = phase,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(PHASE_FADE_MILLIS),
            label = "recipesPhase"
        ) { target ->
            when (target) {
                Phase.FAILED -> GenerationError(
                    // Same as above: the outgoing error still takes taps during the cross-fade
                    onRetry = { if (!state.isGenerating) onAction(Interaction.OnRegenerateClick) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
                Phase.GENERATING -> GeneratingContent(
                    state = state,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
                Phase.LIST -> RecipeList(
                    state = state,
                    listState = listState,
                    padding = padding,
                    onRecipeClick = { index -> onAction(Navigation.GoToDetail(index)) }
                )
            }
        }
    }
}

/**
 * Background-coloured bar. The title and a hairline fade in only once the
 * list's own header has scrolled away, so the screen never shows two titles.
 */
@Composable
private fun RecipesTopBar(
    showTitle: Boolean,
    showRegenerate: Boolean,
    onBack: () -> Unit,
    onRegenerate: () -> Unit
) {
    val dividerAlpha by animateFloatAsState(
        targetValue = if (showTitle) 1f else 0f,
        label = "topBarDivider"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                onClick = onBack
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                // Qualified: inside a Box within a Row the RowScope overload would be resolved
                androidx.compose.animation.AnimatedVisibility(
                    visible = showTitle,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Text(
                        text = stringResource(R.string.what_can_i_cook),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Always as wide as the back button, so the title stays centred
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showRegenerate,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    CircleIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.regenerate),
                        onClick = onRegenerate
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.graphicsLayer { alpha = dividerAlpha },
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/** 40dp tonal circle with a 48dp touch target, for the bar's actions. */
@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun GenerationError(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.recipe_generation_failed),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.recipe_generation_failed_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.height(52.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 24.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.retry),
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

/**
 * The wait while Gemma writes the list: the orb, a rotating kitchen phrase,
 * the simulated progress and a checklist of the generator's real steps, with
 * the engine pinned at the bottom.
 */
@Composable
private fun GeneratingContent(
    state: RecipesState,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                // At least one screen tall, so the weighted spacers can centre the content;
                // on short screens (landscape) it scrolls instead of running under the bars
                .heightIn(min = maxHeight)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Centred in the space above the engine pill, so the two never overlap
            Spacer(modifier = Modifier.weight(1f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AiOrb()
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = stringResource(R.string.generating_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                CookingWaitPhrases(
                    // Full width: the phrase is centred by the text, not by a box that
                    // resizes (and slides) with every phrase
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))
                WaitProgressBar(
                    expectedMillis = state.generationExpectedMillis,
                    completed = state.generationCompleted,
                    modifier = Modifier.fillMaxWidth(),
                    // Neutral track: the default one is the tangerine secondary container
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                AnimatedVisibility(visible = state.progressLog.isNotEmpty()) {
                    ProgressChecklist(
                        log = state.progressLog,
                        allDone = state.generationCompleted,
                        modifier = Modifier.padding(top = 28.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            EnginePill(
                engineName = state.engineName,
                modifier = Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * The generator's steps as a checklist: every step but the last is done, and
 * the last one spins until the whole list is ready.
 */
@Composable
private fun ProgressChecklist(
    log: List<GenerationProgress>,
    allDone: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.extendedColors.card, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        log.forEachIndexed { index, progress ->
            // Steps are only ever appended: each one expands in as it arrives. The gap
            // lives inside the animated block, so it grows with the row instead of jumping
            AnimatedVisibility(
                visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
            ) {
                ProgressStep(
                    progress = progress,
                    done = index < log.lastIndex || allDone,
                    modifier = Modifier.padding(top = if (index > 0) 8.dp else 0.dp)
                )
            }
        }
    }
}

@Composable
private fun ProgressStep(
    progress: GenerationProgress,
    done: Boolean,
    modifier: Modifier = Modifier
) {
    val textColor by animateColorAsState(
        targetValue = if (done) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        label = "progressStepText"
    )
    Row(modifier = modifier) {
        // 20dp slot, as tall as a bodyMedium line: the mark stays on the first line
        Crossfade(
            targetState = done,
            modifier = Modifier.size(20.dp),
            label = "progressStepStatus"
        ) { isDone ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isDone) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.extendedColors.success,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        strokeCap = StrokeCap.Round
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = progressLabel(progress),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
    }
}

/** The generator's progress, in the device language. */
@Composable
private fun progressLabel(progress: GenerationProgress): String = when (progress) {
    is GenerationProgress.LoadingModel ->
        stringResource(R.string.progress_loading_model, progress.modelName)
    is GenerationProgress.Generating -> pluralStringResource(
        R.plurals.progress_generating,
        progress.ingredientCount,
        progress.ingredientCount
    )
    GenerationProgress.Retrying -> stringResource(R.string.progress_retrying)
}

@Composable
private fun RecipeList(
    state: RecipesState,
    listState: LazyListState,
    padding: PaddingValues,
    onRecipeClick: (Int) -> Unit
) {
    val entranceClock = rememberEntranceClock()
    // Only the top inset is applied to the list: it scrolls behind the
    // transparent navigation bar and its last card clears it via contentPadding
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 4.dp,
            end = 20.dp,
            bottom = padding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            ListHeader(
                ingredientCount = state.ingredientCount,
                engineName = state.engineName,
                modifier = Modifier.entrance(position = 0, clock = entranceClock)
            )
        }
        itemsIndexed(state.recipes) { index, recipe ->
            RecipeCard(
                recipe = recipe,
                onClick = { onRecipeClick(index) },
                modifier = Modifier.entrance(position = index + 1, clock = entranceClock)
            )
        }
    }
}

@Composable
private fun ListHeader(
    ingredientCount: Int,
    engineName: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.recipes_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = pluralStringResource(R.plurals.recipes_subtitle, ingredientCount, ingredientCount),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        EnginePill(engineName = engineName)
    }
}

@Composable
private fun RecipeCard(
    recipe: Recipe,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val emoji = remember(recipe) { dishEmoji(recipe) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.extendedColors.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiAvatar(emoji = emoji, size = 64.dp)
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recipe.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    RecipeMeta(recipe = recipe)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // whySuitable is generated on demand in the detail screen: in the list it
            // is often empty, and the ingredient strip below already says what's used
            if (recipe.whySuitable.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = recipe.whySuitable,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (recipe.usedIngredients.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                IngredientStrip(ingredients = recipe.usedIngredients)
            }
            if (recipe.missingIngredients.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                MissingIngredientsPill(missing = recipe.missingIngredients)
            }
        }
    }
}

/**
 * Time and difficulty. The ingredient count is left to the strip below: next
 * to a 64dp avatar a third item doesn't fit on one line on a phone.
 */
@Composable
private fun RecipeMeta(recipe: Recipe) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Rounded.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.prep_time_minutes, recipe.prepTimeMinutes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .size(3.dp)
                .background(MaterialTheme.colorScheme.outline, CircleShape)
        )
        DifficultyBadge(difficulty = recipe.difficulty)
    }
}

/**
 * The first used ingredients as a stack of overlapping round avatars, each cut
 * out of the previous one by a ring in the card colour, then their names.
 */
@Composable
private fun IngredientStrip(ingredients: List<RecipeIngredient>) {
    val emojis = remember(ingredients) {
        ingredients.take(STRIP_AVATARS).map { ingredientEmoji(it.name) }
    }
    val hidden = ingredients.size - emojis.size
    val ringColor = MaterialTheme.extendedColors.card
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Later avatars are drawn on top: each start offset is one step further
        Box {
            emojis.forEachIndexed { index, emoji ->
                EmojiAvatar(
                    emoji = emoji,
                    modifier = Modifier
                        .padding(start = STRIP_AVATAR_STEP * index)
                        .border(2.dp, ringColor, CircleShape),
                    size = STRIP_AVATAR_SIZE,
                    shape = CircleShape
                )
            }
            if (hidden > 0) {
                Box(
                    modifier = Modifier
                        .padding(start = STRIP_AVATAR_STEP * emojis.size)
                        .size(STRIP_AVATAR_SIZE)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        .border(2.dp, ringColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.more_count, hidden),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = ingredients.joinToString { it.name },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MissingIngredientsPill(missing: List<RecipeIngredient>) {
    val colors = MaterialTheme.extendedColors
    Row(
        modifier = Modifier
            .background(colors.warningContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.ShoppingCart,
            contentDescription = null,
            tint = colors.onWarningContainer,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = pluralStringResource(R.plurals.missing_ingredients, missing.size, missing.joinToString { it.name }),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onWarningContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Clock, in milliseconds, for the list's staggered entrance. Saved once played,
 * so coming back from a recipe doesn't replay it; a regenerated list is a new
 * composition and plays it again.
 */
@Composable
private fun rememberEntranceClock(): Animatable<Float, AnimationVector1D> {
    var played by rememberSaveable { mutableStateOf(false) }
    val clock = remember { Animatable(if (played) ENTRANCE_TOTAL_MILLIS else 0f) }
    LaunchedEffect(clock) {
        clock.animateTo(
            targetValue = ENTRANCE_TOTAL_MILLIS,
            animationSpec = tween(ENTRANCE_TOTAL_MILLIS.toInt(), easing = LinearEasing)
        )
        played = true
    }
    return clock
}

/**
 * Fades the item in while it rises into place, [position] steps after the
 * first one. Read in the layer, so the animation never recomposes the list;
 * items first composed after the entrance (scrolling) just appear.
 */
private fun Modifier.entrance(
    position: Int,
    clock: Animatable<Float, AnimationVector1D>
): Modifier = graphicsLayer {
    val start = position.coerceAtMost(MAX_STAGGERED_ITEMS) * STAGGER_MILLIS
    val fraction = FastOutSlowInEasing.transform(((clock.value - start) / ENTER_MILLIS).coerceIn(0f, 1f))
    alpha = fraction
    translationY = (1f - fraction) * ENTER_OFFSET.toPx()
}

private const val PHASE_FADE_MILLIS = 250

private const val STRIP_AVATARS = 6
private val STRIP_AVATAR_SIZE = 28.dp
// 28dp avatars overlapping by 8dp
private val STRIP_AVATAR_STEP = 20.dp

private const val STAGGER_MILLIS = 65f
private const val ENTER_MILLIS = 320f
private const val MAX_STAGGERED_ITEMS = 6
private const val ENTRANCE_TOTAL_MILLIS = MAX_STAGGERED_ITEMS * STAGGER_MILLIS + ENTER_MILLIS
private val ENTER_OFFSET = 12.dp
