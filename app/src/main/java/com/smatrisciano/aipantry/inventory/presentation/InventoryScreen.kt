package com.smatrisciano.aipantry.inventory.presentation

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Kitchen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smatrisciano.aipantry.R
import com.smatrisciano.aipantry.core.presentation.composables.CountPill
import com.smatrisciano.aipantry.core.presentation.composables.EmojiAvatar
import com.smatrisciano.aipantry.core.presentation.composables.InfoPill
import com.smatrisciano.aipantry.core.presentation.theme.extendedColors
import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.inventory.presentation.InventoryActions.Interaction
import com.smatrisciano.aipantry.inventory.presentation.InventoryActions.Navigation
import com.smatrisciano.aipantry.inventory.presentation.composables.ingredientEmoji
import org.koin.androidx.compose.koinViewModel

@Composable
fun InventoryScreenRoot(
    viewModel: InventoryViewModel = koinViewModel(),
    onNavigation: (Navigation) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    InventoryScreen(
        state = state,
        onAction = { action ->
            when (action) {
                is Interaction -> viewModel.onAction(action)
                is Navigation -> onNavigation(action)
            }
        }
    )
}

@Composable
private fun InventoryScreen(
    state: InventoryState,
    onAction: (InventoryActions) -> Unit
) {
    // No Scaffold: the header scrolls with the content, so the screen handles the insets itself.
    // The Surface provides onBackground as the content colour for both themes.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                // Before the first load the kitchen is unknown, not empty: just the background,
                // or the empty header would show and then jump when the list arrives
                !state.isLoaded -> Unit
                state.isEmpty -> EmptyInventory(state = state, onAction = onAction)
                else -> {
                    InventoryList(state = state, onAction = onAction)
                    BottomActions(
                        totalCount = state.totalCount,
                        isAiReady = state.isAiReady,
                        onCookClick = { onAction(Navigation.GoToRecipes) },
                        onScanClick = { onAction(Navigation.GoToCapture) },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
            StatusBarScrim(modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun InventoryList(
    state: InventoryState,
    onAction: (InventoryActions) -> Unit
) {
    val onRemove = { ingredient: Ingredient -> onAction(Interaction.OnIngredientRemoved(ingredient.name)) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + BottomBarClearance
        )
    ) {
        item(key = HEADER_KEY, contentType = "header") {
            InventoryHeader(
                state = state,
                onClearAllClick = { onAction(Interaction.OnClearAllClick) }
            )
        }
        ingredientSection(R.string.fridge, Icons.Rounded.Kitchen, state.fridgeItems, onRemove)
        ingredientSection(R.string.pantry, Icons.Rounded.ShoppingBasket, state.pantryItems, onRemove)
    }
}

/**
 * Brand row, title, summary and AI status. It scrolls away with the content: the big title is
 * the anchor of the screen, not chrome.
 */
@Composable
private fun InventoryHeader(
    state: InventoryState,
    onClearAllClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .padding(top = 8.dp)
    ) {
        // Fixed height: the row doesn't jump when the clear-all button comes and goes
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandMark()
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(modifier = Modifier.weight(1f))
            if (!state.isEmpty) {
                FilledIconButton(
                    onClick = onClearAllClick,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = stringResource(R.string.clear_all),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.inventory_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() }
        )
        if (!state.isEmpty) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = pluralStringResource(R.plurals.inventory_summary, state.totalCount, state.totalCount),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        AiStatusPill(status = state.aiStatus, modelName = state.modelName)
    }
}

/** The app's mark: the AI gradient is reserved for it and for the moments the model works. */
@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Brush.linearGradient(MaterialTheme.extendedColors.aiGradient), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // Same glyph as the launcher icon, with its amber sparkle
        Image(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun AiStatusPill(status: AiStatus, modelName: String) {
    InfoPill(
        text = when (status) {
            AiStatus.READY -> stringResource(R.string.engine_on_device, modelName)
            AiStatus.DOWNLOADING -> stringResource(R.string.ai_status_downloading, modelName)
            AiStatus.PREPARING -> stringResource(R.string.ai_status_preparing, modelName)
            AiStatus.FAILED -> stringResource(R.string.ai_status_failed_short)
        }
    ) {
        when (status) {
            AiStatus.READY -> StatusDot(color = MaterialTheme.extendedColors.success)
            AiStatus.DOWNLOADING, AiStatus.PREPARING -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp
            )
            AiStatus.FAILED -> StatusDot(color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

/** A section header plus its rows, emitted one item per row so removals animate individually. */
private fun LazyListScope.ingredientSection(
    @StringRes titleRes: Int,
    icon: ImageVector,
    ingredients: List<Ingredient>,
    onRemove: (Ingredient) -> Unit
) {
    if (ingredients.isEmpty()) return
    // Int key: it can't collide with the ingredient names, which are the rows' String keys
    item(key = titleRes, contentType = "section") {
        SectionHeader(
            title = stringResource(titleRes),
            icon = icon,
            count = ingredients.size,
            modifier = Modifier.animateItem()
        )
    }
    itemsIndexed(
        items = ingredients,
        key = { _, ingredient -> ingredient.name },
        contentType = { _, _ -> "ingredient" }
    ) { index, ingredient ->
        IngredientRow(
            ingredient = ingredient,
            isFirst = index == 0,
            isLast = index == ingredients.lastIndex,
            onRemove = { onRemove(ingredient) },
            modifier = Modifier.animateItem()
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
    count: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding, end = ScreenPadding, top = 28.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(modifier = Modifier.width(8.dp))
        CountPill(
            count = count,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One row of a grouped list: only the outer corners of the block are rounded. They animate, so
 * when a neighbour is removed the row that becomes first or last rounds off instead of snapping.
 */
@Composable
private fun IngredientRow(
    ingredient: Ingredient,
    isFirst: Boolean,
    isLast: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val topCorner by animateDpAsState(if (isFirst) GroupCorner else 0.dp, label = "topCorner")
    val bottomCorner by animateDpAsState(if (isLast) GroupCorner else 0.dp, label = "bottomCorner")
    val dividerAlpha by animateFloatAsState(if (isLast) 0f else 1f, label = "dividerAlpha")
    // The keyword match is costly: don't redo it while the corners animate
    val emoji = remember(ingredient.name) { ingredientEmoji(ingredient.name) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .clip(
                RoundedCornerShape(
                    topStart = topCorner,
                    topEnd = topCorner,
                    bottomEnd = bottomCorner,
                    bottomStart = bottomCorner
                )
            )
            .background(MaterialTheme.extendedColors.card)
    ) {
        // 8dp vertical around the 48dp remove target: the 40dp avatar gets its 12dp of air
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = RowPadding, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EmojiAvatar(emoji = emoji, size = AvatarSize)
            Spacer(modifier = Modifier.width(AvatarGap))
            Text(
                text = ingredient.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.remove_ingredient, ingredient.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        // Indented to start after the avatar, as in a settings list
        HorizontalDivider(
            modifier = Modifier
                .padding(start = RowPadding + AvatarSize + AvatarGap)
                .alpha(dividerAlpha),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/**
 * Actions pinned over the list. A short gradient lets the list fade out behind them instead of
 * ending on a hard edge.
 */
@Composable
private fun BottomActions(
    totalCount: Int,
    isAiReady: Boolean,
    onCookClick: () -> Unit,
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = MaterialTheme.colorScheme.background
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(BottomFadeHeight)
                // Same colour at zero alpha, not Transparent (black): no grey band mid-fade
                .background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background)))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .navigationBarsPadding()
                .padding(start = ScreenPadding, end = ScreenPadding, bottom = BottomBarMargin),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onCookClick,
                enabled = isAiReady,
                modifier = Modifier
                    .weight(1f)
                    .height(ActionHeight),
                contentPadding = PaddingValues(start = 14.dp, end = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // On a 360dp phone, in Italian or with a larger font, the label steps down a
                // little instead of being cut: it is the app's main action
                BasicText(
                    text = stringResource(R.string.what_can_i_cook),
                    style = MaterialTheme.typography.titleSmall.copy(color = LocalContentColor.current),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 12.sp,
                        maxFontSize = MaterialTheme.typography.titleSmall.fontSize
                    ),
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Tinted with the content colour, so it greys out with the disabled button too
                CountPill(count = totalCount)
            }
            Spacer(modifier = Modifier.width(12.dp))
            FilledIconButton(
                onClick = onScanClick,
                modifier = Modifier.size(ActionHeight),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoCamera,
                    contentDescription = stringResource(R.string.scan)
                )
            }
        }
    }
}

@Composable
private fun EmptyInventory(
    state: InventoryState,
    onAction: (InventoryActions) -> Unit
) {
    // Delayed one-shot entrance: a soft arrival rather than a pop when the kitchen loads empty
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(durationMillis = 360, delayMillis = 150, easing = FastOutSlowInEasing))
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                // At least one screen tall, so the weighted spacers can centre the content;
                // on short screens it scrolls instead of clipping
                .heightIn(min = maxHeight)
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            InventoryHeader(
                state = state,
                onClearAllClick = { onAction(Interaction.OnClearAllClick) }
            )
            Spacer(modifier = Modifier.weight(1f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenPadding)
                    .graphicsLayer {
                        alpha = appear.value
                        translationY = (1f - appear.value) * 16.dp.toPx()
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                KitchenIllustration()
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = stringResource(R.string.empty_pantry_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.empty_pantry_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 300.dp)
                )
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = { onAction(Navigation.GoToCapture) },
                    modifier = Modifier.height(ActionHeight),
                    contentPadding = PaddingValues(start = 24.dp, end = 28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.scan_my_fridge),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                InfoPill(text = stringResource(R.string.private_on_device)) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            // Heavier below than above: the block sits slightly over the centre, where it reads as centred
            Spacer(modifier = Modifier.weight(1.4f))
        }
    }
}

/**
 * Empty-state artwork made of the app's own pieces: a soft glow, the fridge in the middle and a
 * few foods around it, ringed and slightly tilted like stickers.
 */
@Composable
private fun KitchenIllustration() {
    val ring = MaterialTheme.extendedColors.card
    Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(168.dp)
                .background(
                    Brush.radialGradient(
                        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background)
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(ring, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Kitchen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
        FloatingFoods.forEach { food ->
            EmojiAvatar(
                emoji = food.emoji,
                size = 52.dp,
                shape = CircleShape,
                modifier = Modifier
                    .offset(food.x, food.y)
                    .rotate(food.rotation)
                    .border(3.dp, ring, CircleShape)
            )
        }
    }
}

/** Keeps scrolled content from running under the status bar icons (the app is edge-to-edge). */
@Composable
private fun StatusBarScrim(modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .statusBarsPadding()
        )
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(Brush.verticalGradient(listOf(background, background.copy(alpha = 0f))))
        )
    }
}

/** A food around the empty-state fridge: offset from the centre and a slight tilt. */
private class FloatingFood(val emoji: String, val x: Dp, val y: Dp, val rotation: Float)

// About 70dp from the centre: clear of the fridge disc and of each other, loosely spaced
private val FloatingFoods = listOf(
    FloatingFood("🥕", (-62).dp, (-30).dp, -12f),
    FloatingFood("🥚", 24.dp, (-66).dp, 8f),
    FloatingFood("🧀", 66.dp, 22.dp, -6f),
    FloatingFood("🍅", (-20).dp, 66.dp, 10f)
)

private const val HEADER_KEY = 0

private val ScreenPadding = 20.dp
private val GroupCorner = 20.dp
private val RowPadding = 16.dp
private val AvatarSize = 40.dp
private val AvatarGap = 14.dp
private val ActionHeight = 56.dp
private val BottomBarMargin = 12.dp
private val BottomFadeHeight = 32.dp

// Bottom bar, its fade and a little air: the last row ends clear of the buttons
private val BottomBarClearance = ActionHeight + BottomBarMargin + BottomFadeHeight + 8.dp
