package com.smatrisciano.aipantry.core.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smatrisciano.aipantry.R

// Brand: a fresh basil green on warm, slightly green-tinted neutrals, with a
// tangerine accent for what's missing. Every role is set explicitly: the M3
// baseline fills unset roles with its purple palette.

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0B7A4B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F4E1),
    onPrimaryContainer = Color(0xFF00391F),
    inversePrimary = Color(0xFF5BD991),
    secondary = Color(0xFFD9540B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE9DA),
    onSecondaryContainer = Color(0xFF4A1D00),
    tertiary = Color(0xFF0E7C86),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD2F3F5),
    onTertiaryContainer = Color(0xFF00363B),
    background = Color(0xFFF6F7F3),
    onBackground = Color(0xFF111813),
    surface = Color(0xFFF6F7F3),
    onSurface = Color(0xFF111813),
    surfaceVariant = Color(0xFFE9ECE6),
    onSurfaceVariant = Color(0xFF5B645D),
    surfaceTint = Color(0xFF0B7A4B),
    inverseSurface = Color(0xFF242A26),
    inverseOnSurface = Color(0xFFEFF2EE),
    error = Color(0xFFD92D20),
    onError = Color.White,
    errorContainer = Color(0xFFFDE5E2),
    onErrorContainer = Color(0xFF5C0F09),
    outline = Color(0xFFC4CBC4),
    outlineVariant = Color(0xFFE2E6E0),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDCE0DA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFCF9),
    surfaceContainer = Color(0xFFF0F2EE),
    surfaceContainerHigh = Color(0xFFEAEDE8),
    surfaceContainerHighest = Color(0xFFE3E7E1)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF5BD991),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF0F5134),
    onPrimaryContainer = Color(0xFFC8F5DA),
    inversePrimary = Color(0xFF0B7A4B),
    secondary = Color(0xFFFFA36B),
    onSecondary = Color(0xFF4A1D00),
    secondaryContainer = Color(0xFF5A2A06),
    onSecondaryContainer = Color(0xFFFFDCC6),
    tertiary = Color(0xFF6FD6DF),
    onTertiary = Color(0xFF00363B),
    tertiaryContainer = Color(0xFF0B4E55),
    onTertiaryContainer = Color(0xFFCFF6F8),
    background = Color(0xFF0C0F0D),
    onBackground = Color(0xFFE7EBE7),
    surface = Color(0xFF0C0F0D),
    onSurface = Color(0xFFE7EBE7),
    surfaceVariant = Color(0xFF242B26),
    onSurfaceVariant = Color(0xFFA2ACA4),
    surfaceTint = Color(0xFF5BD991),
    inverseSurface = Color(0xFFE7EBE7),
    inverseOnSurface = Color(0xFF1A1F1C),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5C0F09),
    errorContainer = Color(0xFF5C1A14),
    onErrorContainer = Color(0xFFFFDAD5),
    outline = Color(0xFF414B44),
    outlineVariant = Color(0xFF283029),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2F3631),
    surfaceDim = Color(0xFF0C0F0D),
    surfaceContainerLowest = Color(0xFF080A09),
    surfaceContainerLow = Color(0xFF141815),
    surfaceContainer = Color(0xFF181D1A),
    surfaceContainerHigh = Color(0xFF1F2521),
    surfaceContainerHighest = Color(0xFF272E29)
)

/** Colour families for the soft background behind an emoji (see EmojiAvatar). */
enum class Tone { RED, ORANGE, YELLOW, GREEN, PURPLE, BLUE, BROWN, NEUTRAL }

/** Roles Material 3 doesn't have: statuses, the AI accent and the emoji tints. */
@Immutable
data class ExtendedColors(
    val success: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** Basil to teal: only for the "AI is working" moments, never as decoration. */
    val aiGradient: List<Color>,
    /** Card surface: white on the light theme, a raised grey on the dark one. */
    val card: Color,
    private val tones: Map<Tone, Color>
) {
    fun tint(tone: Tone): Color = tones.getValue(tone)
}

private val LightExtendedColors = ExtendedColors(
    success = Color(0xFF14A05C),
    warning = Color(0xFFD97706),
    warningContainer = Color(0xFFFFF1DB),
    onWarningContainer = Color(0xFF6B3A00),
    aiGradient = listOf(Color(0xFF0B7A4B), Color(0xFF14A37F), Color(0xFF0E9AA7)),
    card = Color.White,
    tones = mapOf(
        Tone.RED to Color(0xFFFDE8E5),
        Tone.ORANGE to Color(0xFFFFEDDD),
        Tone.YELLOW to Color(0xFFFFF4CE),
        Tone.GREEN to Color(0xFFE2F4E5),
        Tone.PURPLE to Color(0xFFEFE8FA),
        Tone.BLUE to Color(0xFFE3EFFA),
        Tone.BROWN to Color(0xFFF3EBE1),
        Tone.NEUTRAL to Color(0xFFEDF0EB)
    )
)

private val DarkExtendedColors = ExtendedColors(
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF3D2A07),
    onWarningContainer = Color(0xFFFFE2B0),
    aiGradient = listOf(Color(0xFF34C77B), Color(0xFF2DD4BF), Color(0xFF38BDF8)),
    card = Color(0xFF161B18),
    tones = mapOf(
        Tone.RED to Color(0xFF3A2321),
        Tone.ORANGE to Color(0xFF3A2A1C),
        Tone.YELLOW to Color(0xFF36311B),
        Tone.GREEN to Color(0xFF1E3325),
        Tone.PURPLE to Color(0xFF2C2638),
        Tone.BLUE to Color(0xFF1D2C39),
        Tone.BROWN to Color(0xFF322920),
        Tone.NEUTRAL to Color(0xFF232925)
    )
)

private val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/** `MaterialTheme.extendedColors.success`, alongside `MaterialTheme.colorScheme`. */
val MaterialTheme.extendedColors: ExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current

private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

// Tighter tracking as the size grows, as in Inter's own display cut
private fun style(size: Int, lineHeight: Int, weight: FontWeight, tracking: Double = 0.0) =
    TextStyle(
        fontFamily = Inter,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = tracking.sp
    )

private val AppTypography = Typography(
    displayLarge = style(52, 60, FontWeight.Bold, -1.2),
    displayMedium = style(44, 52, FontWeight.Bold, -1.0),
    displaySmall = style(36, 44, FontWeight.Bold, -0.8),
    headlineLarge = style(32, 40, FontWeight.Bold, -0.7),
    headlineMedium = style(28, 36, FontWeight.Bold, -0.6),
    headlineSmall = style(24, 32, FontWeight.Bold, -0.4),
    titleLarge = style(21, 28, FontWeight.SemiBold, -0.3),
    titleMedium = style(17, 24, FontWeight.SemiBold, -0.2),
    titleSmall = style(15, 20, FontWeight.SemiBold, -0.1),
    bodyLarge = style(16, 24, FontWeight.Normal, -0.1),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 16, FontWeight.Normal, 0.1),
    labelLarge = style(14, 20, FontWeight.SemiBold),
    labelMedium = style(12, 16, FontWeight.Medium, 0.1),
    labelSmall = style(11, 14, FontWeight.Medium, 0.2)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun AiPantryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalExtendedColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
