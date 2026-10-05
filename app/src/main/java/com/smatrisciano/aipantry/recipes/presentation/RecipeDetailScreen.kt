package com.smatrisciano.aipantry.recipes.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smatrisciano.aipantry.R
import com.smatrisciano.aipantry.core.presentation.composables.AiOrb
import com.smatrisciano.aipantry.core.presentation.composables.EmojiAvatar
import com.smatrisciano.aipantry.core.presentation.composables.WaitProgressBar
import com.smatrisciano.aipantry.core.presentation.composables.emojiTone
import com.smatrisciano.aipantry.core.presentation.theme.Tone
import com.smatrisciano.aipantry.core.presentation.theme.extendedColors
import com.smatrisciano.aipantry.inventory.presentation.composables.ingredientEmoji
import com.smatrisciano.aipantry.recipes.domain.DetailsPart
import com.smatrisciano.aipantry.recipes.domain.DetailsStatus
import com.smatrisciano.aipantry.recipes.domain.RecipeDetails
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import com.smatrisciano.aipantry.recipes.domain.models.RecipeIngredient
import com.smatrisciano.aipantry.recipes.presentation.composables.CookingWaitPhrases
import com.smatrisciano.aipantry.recipes.presentation.composables.DifficultyBars
import com.smatrisciano.aipantry.recipes.presentation.composables.difficultyLabel
import com.smatrisciano.aipantry.recipes.presentation.composables.dishEmoji

@Composable
fun RecipeDetailScreenRoot(
    viewModel: RecipesViewModel,
    recipeId: Int,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val item = state.recipes.firstOrNull { it.id == recipeId }

    // The instructions are written for the recipe on screen first, unless they are
    // ready already (written ahead of time, or on an earlier visit)
    DisposableEffect(recipeId) {
        viewModel.onAction(RecipesActions.Interaction.OnRecipeOpened(recipeId))
        onDispose { viewModel.onAction(RecipesActions.Interaction.OnRecipeClosed(recipeId)) }
    }

    if (item == null) {
        onBack()
        return
    }
    RecipeDetailScreen(
        recipe = item.recipe,
        details = item.details,
        onRetryDetails = {
            viewModel.onAction(RecipesActions.Interaction.OnRetryDetailsClick(recipeId))
        },
        onBack = onBack
    )
}

@Composable
private fun RecipeDetailScreen(
    recipe: Recipe,
    details: RecipeDetails,
    onRetryDetails: () -> Unit,
    onBack: () -> Unit
) {
    val isWriting = details.status == DetailsStatus.PENDING
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barBottomPx = with(density) { (statusBarTop + TopBarHeight).toPx() }
    // The hero is statusBar + HeroHeight tall and the bar statusBar + TopBarHeight:
    // the bar is fully over the hero until this much has been scrolled
    val heroCoveredPx = with(density) { (HeroHeight - TopBarHeight).toPx() }
    // Bottom of the big title in the scrolling content, measured once laid out
    var titleBottomPx by remember { mutableFloatStateOf(Float.POSITIVE_INFINITY) }

    // The bar stays clear over the hero and turns solid when the page slides under it;
    // the small title appears only once the big one has gone, as with iOS large titles
    val barFilled by remember(heroCoveredPx) {
        derivedStateOf { scrollState.value.toFloat() >= heroCoveredPx }
    }
    val titleInBar by remember(barBottomPx) {
        derivedStateOf { scrollState.value.toFloat() >= titleBottomPx - barBottomPx }
    }

    // Keyed on the title: the hero must not switch emoji when the steps arrive
    val emoji = remember(recipe.title) { dishEmoji(recipe) }

    // A Surface rather than a bare Box: no Scaffold here, and Text needs the
    // on-background content colour to be right in dark mode
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                DishHero(emoji = emoji, statusBarTop = statusBarTop, scrollState = scrollState)

                Text(
                    text = recipe.title,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = ScreenPadding, end = ScreenPadding, top = 24.dp)
                        .semantics { heading() }
                        // Position in the content, not on screen: it doesn't change with the scroll
                        .onGloballyPositioned { titleBottomPx = it.positionInParent().y + it.size.height }
                )
                // if (recipe.whySuitable.isNotBlank()) {
                //     Text(
                //         text = recipe.whySuitable,
                //         style = MaterialTheme.typography.bodyLarge,
                //         color = MaterialTheme.colorScheme.onSurfaceVariant,
                //         modifier = Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 8.dp)
                //     )
                // }

                StatsCard(
                    recipe = recipe,
                    modifier = Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 20.dp)
                )

                SectionTitle(stringResource(R.string.ingredients_from_pantry))
                IngredientGroup(ingredients = recipe.usedIngredients) { ingredient ->
                    val avatarEmoji = remember(ingredient.name) { ingredientEmoji(ingredient.name) }
                    EmojiAvatar(emoji = avatarEmoji, size = LeadingSize)
                }

                if (recipe.missingIngredients.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.you_will_also_need))
                    IngredientGroup(ingredients = recipe.missingIngredients) {
                        MissingIngredientIcon()
                    }
                }

                SectionTitle(stringResource(R.string.instructions))
                Instructions(
                    steps = recipe.steps,
                    details = details,
                    onRetryDetails = onRetryDetails
                )

                val writingVariants = isWriting && details.writing == DetailsPart.VARIANTS
                if (recipe.variants.isNotEmpty() || writingVariants) {
                    SectionTitle(stringResource(R.string.variants))
                    Variants(recipe.variants, writingMore = writingVariants)
                }

                // While the panel below is up, the page scrolls far enough to show it all above it
                val panelRoom by animateDpAsState(
                    targetValue = if (isWriting) WritingPanelRoom else 0.dp,
                    label = "writingPanelRoom"
                )
                Spacer(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .height(32.dp + panelRoom)
                )
            }

            AnimatedVisibility(
                visible = isWriting,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                WritingPanel(progress = details.progress)
            }

            DetailTopBar(
                title = recipe.title,
                filled = barFilled,
                showTitle = titleInBar,
                onBack = onBack
            )
        }
    }
}

/**
 * Bar overlaid on the content: clear over the hero, so the dish runs up to
 * the status bar, then solid with a hairline once content scrolls under it.
 */
@Composable
private fun DetailTopBar(
    title: String,
    filled: Boolean,
    showTitle: Boolean,
    onBack: () -> Unit
) {
    val background = MaterialTheme.colorScheme.background
    // Fading the same colour's alpha avoids a grey midpoint in the animation
    val barColor by animateColorAsState(
        targetValue = if (filled) background else background.copy(alpha = 0f),
        animationSpec = tween(BAR_ANIMATION_MILLIS),
        label = "barColor"
    )
    val dividerAlpha by animateFloatAsState(
        targetValue = if (filled) 1f else 0f,
        animationSpec = tween(BAR_ANIMATION_MILLIS),
        label = "barDivider"
    )
    val titleAlpha by animateFloatAsState(
        targetValue = if (showTitle) 1f else 0f,
        animationSpec = tween(BAR_ANIMATION_MILLIS),
        label = "barTitle"
    )
    // White over the hero; on the solid bar the same grey circle as the recipe list's back button
    val backButtonColor by animateColorAsState(
        targetValue = if (filled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.extendedColors.card,
        animationSpec = tween(BAR_ANIMATION_MILLIS),
        label = "backButton"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barColor)
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(TopBarHeight)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = onBack,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = backButtonColor,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .graphicsLayer {
                        alpha = titleAlpha
                        translationY = (1f - titleAlpha) * 6.dp.toPx()
                    }
                    // A visual echo of the big title, which screen readers already read
                    .clearAndSetSemantics {}
            )
            // Same width as the back button, so the title is truly centred
            Spacer(modifier = Modifier.size(48.dp))
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.graphicsLayer { alpha = dividerAlpha }
        )
    }
}

/**
 * Full-bleed tinted header with the dish emoji. The emoji drifts down and
 * fades as the page scrolls over it, a light parallax that adds depth.
 */
@Composable
private fun DishHero(emoji: String, statusBarTop: Dp, scrollState: ScrollState) {
    val glow = MaterialTheme.colorScheme.surfaceBright
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(statusBarTop + HeroHeight)
            .clip(HeroShape)
            .background(MaterialTheme.extendedColors.tint(emojiTone(emoji)))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarTop + 16.dp)
                .graphicsLayer {
                    // Read in the layer block: scrolling redraws, it doesn't recompose
                    val scrolled = scrollState.value.toFloat()
                    translationY = scrolled * 0.4f
                    alpha = 1f - (scrolled / HeroHeight.toPx()).coerceIn(0f, 1f) * 0.6f
                },
            contentAlignment = Alignment.Center
        ) {
            // Soft light pooling behind the dish, so it doesn't sit flat on the tint
            Box(
                modifier = Modifier
                    .size(176.dp)
                    .background(
                        Brush.radialGradient(listOf(glow.copy(alpha = 0.55f), glow.copy(alpha = 0f))),
                        CircleShape
                    )
            )
            Text(
                text = emoji,
                // Platform default font: the glyph comes from the emoji font anyway
                style = TextStyle(fontSize = 96.sp, lineHeight = 116.sp)
            )
        }
    }
}

/** Time, difficulty and ingredient count side by side, each with its own visual. */
@Composable
private fun StatsCard(recipe: Recipe, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    val iconTint = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.extendedColors.card)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Stat(
            value = stringResource(R.string.prep_time_minutes, recipe.prepTimeMinutes),
            label = stringResource(R.string.stat_time),
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        StatDivider()
        Stat(
            value = difficultyLabel(recipe.difficulty),
            label = stringResource(R.string.stat_difficulty),
            modifier = Modifier.weight(1f)
        ) {
            DifficultyBars(recipe.difficulty)
        }
        StatDivider()
        Stat(
            value = "${recipe.usedIngredients.size}",
            label = stringResource(R.string.stat_ingredients),
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Rounded.ShoppingBasket, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Stat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    indicator: @Composable () -> Unit
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Fixed slot: the bars are shorter than the icons, the values must still line up
        Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) {
            indicator()
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatDivider() {
    VerticalDivider(
        modifier = Modifier.height(36.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier
            .padding(start = ScreenPadding, end = ScreenPadding, top = 32.dp, bottom = 12.dp)
            .semantics { heading() }
    )
}

/**
 * Settings-style grouped list: one rounded block whose hairlines start after
 * the leading visual, so the avatars read as a single column.
 */
@Composable
private fun IngredientGroup(
    ingredients: List<RecipeIngredient>,
    leading: @Composable (RecipeIngredient) -> Unit
) {
    Column(
        modifier = Modifier
            .padding(horizontal = ScreenPadding)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.extendedColors.card)
    ) {
        ingredients.forEachIndexed { index, ingredient ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = RowPadding + LeadingSize + LeadingGap),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = RowPadding, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                leading(ingredient)
                Spacer(modifier = Modifier.width(LeadingGap))
                Text(
                    text = ingredient.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (ingredient.quantity.isNotBlank()) {
                    Text(
                        text = ingredient.quantity,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        // Capped so a verbose quantity can't squeeze the name out
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .widthIn(max = 140.dp)
                    )
                }
            }
        }
    }
}

/** Leading slot for what isn't at home: a cart, in the "missing" amber. */
@Composable
private fun MissingIngredientIcon() {
    Box(
        modifier = Modifier
            .size(LeadingSize)
            .background(MaterialTheme.extendedColors.warningContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.ShoppingCart,
            contentDescription = null,
            tint = MaterialTheme.extendedColors.onWarningContainer,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** What the instructions section shows: the steps once there are any, else the wait or the error. */
private enum class InstructionsContent { NONE, LOADING, FAILED, STEPS }

@Composable
private fun Instructions(
    steps: List<String>,
    details: RecipeDetails,
    onRetryDetails: () -> Unit
) {
    val isWriting = details.status == DetailsStatus.PENDING
    val content = when {
        steps.isNotEmpty() -> InstructionsContent.STEPS
        isWriting -> InstructionsContent.LOADING
        details.status == DetailsStatus.FAILED -> InstructionsContent.FAILED
        else -> InstructionsContent.NONE
    }
    // Crossfade, so the first step replaces the wait card instead of popping in
    AnimatedContent(
        targetState = content,
        transitionSpec = { fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(90)) },
        label = "instructions"
    ) { target ->
        when (target) {
            InstructionsContent.STEPS -> StepsTimeline(
                steps = steps,
                writingMore = isWriting && details.writing == DetailsPart.STEPS
            )
            InstructionsContent.LOADING -> StepsSkeleton(modifier = Modifier.padding(horizontal = ScreenPadding))
            InstructionsContent.FAILED -> InstructionsFailedCard(onRetry = onRetryDetails)
            InstructionsContent.NONE -> Spacer(modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * While Gemma writes the recipe: the orb, the kitchen phrases and the progress,
 * floating at the bottom so they stay in sight wherever the page is scrolled, as
 * the amounts and the steps fill in.
 */
@Composable
private fun WritingPanel(progress: Float) {
    Surface(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.extendedColors.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiOrb(size = 44.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.writing_instructions),
                        style = MaterialTheme.typography.titleSmall
                    )
                    CookingWaitPhrases(
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        // Full width and a minimum height, so the panel doesn't jump as phrases change
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            WaitProgressBar(
                progress = progress,
                // Neutral track: the default secondary container is tangerine
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Pulsing placeholders for the steps, until the model writes the first one. */
@Composable
private fun StepsSkeleton(modifier: Modifier = Modifier) {
    val pulse by rememberPulse()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = pulse },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        repeat(3) {
            Row {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                )
                Spacer(modifier = Modifier.width(14.dp))
                SkeletonLines(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

/** Two bars standing in for a line and a half of text still being written. */
@Composable
private fun SkeletonLines(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh
) {
    val lineShape = RoundedCornerShape(5.dp)
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(color, lineShape)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(10.dp)
                .background(color, lineShape)
        )
    }
}

/** The slow breathing of the placeholders while the model writes. */
@Composable
private fun rememberPulse(): State<Float> = rememberInfiniteTransition(label = "skeleton").animateFloat(
    initialValue = 0.45f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    label = "skeletonPulse"
)

@Composable
private fun InstructionsFailedCard(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = ScreenPadding)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.instructions_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onRetry,
            contentPadding = PaddingValues(start = 16.dp, end = 20.dp),
            modifier = Modifier.height(48.dp)
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.retry), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Numbered steps joined by a rail, so the method reads as one sequence. While
 * [writingMore], a pulsing placeholder after the last one stands for the step the
 * model is writing, and each new step fades in as it arrives.
 */
@Composable
private fun StepsTimeline(steps: List<String>, writingMore: Boolean) {
    // The steps already written when the section appears just show
    val shownAtStart = remember { steps.size }
    Column(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        steps.forEachIndexed { index, step ->
            key(index) {
                val appear = remember { Animatable(if (index < shownAtStart) 1f else 0f) }
                LaunchedEffect(Unit) { appear.animateTo(1f, tween(STEP_FADE_MILLIS)) }
                TimelineRow(
                    number = index + 1,
                    joinsNext = index < steps.lastIndex || writingMore,
                    modifier = Modifier.graphicsLayer { alpha = appear.value }
                ) {
                    Text(text = step, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (writingMore) {
            val pulse by rememberPulse()
            TimelineRow(
                number = steps.size + 1,
                joinsNext = false,
                pending = true,
                modifier = Modifier.graphicsLayer { alpha = pulse }
            ) {
                SkeletonLines(modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/**
 * One step of the timeline: its number on the rail, then [content]. Intrinsic
 * height: the rail stretches exactly as tall as the step. A [pending] step, still
 * being written, has a muted number.
 */
@Composable
private fun TimelineRow(
    number: Int,
    joinsNext: Boolean,
    modifier: Modifier = Modifier,
    pending: Boolean = false,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Column(
            modifier = Modifier
                .width(32.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(if (pending) scheme.surfaceContainerHigh else scheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$number",
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = if (pending) scheme.onSurfaceVariant else scheme.onPrimary
                )
            }
            if (joinsNext) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .width(2.dp)
                        .weight(1f)
                        .background(scheme.outlineVariant, CircleShape)
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        // 4dp on top centres the first line on the number
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp, bottom = if (joinsNext) 20.dp else 0.dp)
        ) {
            content()
        }
    }
}

/** Variations as tinted notes; while [writingMore], a pulsing one stands for the next. */
@Composable
private fun Variants(variants: List<String>, writingMore: Boolean) {
    Column(
        modifier = Modifier.padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        variants.forEach { variant ->
            VariantNote {
                Text(
                    text = variant,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        if (writingMore) {
            val pulse by rememberPulse()
            VariantNote(modifier = Modifier.graphicsLayer { alpha = pulse }) {
                SkeletonLines(
                    modifier = Modifier.padding(top = 3.dp),
                    color = MaterialTheme.extendedColors.warning.copy(alpha = 0.22f)
                )
            }
        }
    }
}

@Composable
private fun VariantNote(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.extendedColors.tint(Tone.YELLOW))
            .padding(14.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Lightbulb,
            contentDescription = null,
            tint = MaterialTheme.extendedColors.warning,
            // 1dp down aligns the bulb with the first line of text
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

private val ScreenPadding = 20.dp
private val HeroHeight = 220.dp
private val HeroShape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
private val TopBarHeight = 64.dp
private val RowPadding = 16.dp
private val LeadingSize = 36.dp
private val LeadingGap = 14.dp
private const val BAR_ANIMATION_MILLIS = 180
private const val STEP_FADE_MILLIS = 300

// Height of the writing panel with its margins, kept free at the end of the page
private val WritingPanelRoom = 136.dp
