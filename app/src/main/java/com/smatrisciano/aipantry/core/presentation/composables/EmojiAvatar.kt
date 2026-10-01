package com.smatrisciano.aipantry.core.presentation.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smatrisciano.aipantry.core.presentation.theme.Tone
import com.smatrisciano.aipantry.core.presentation.theme.extendedColors

/**
 * Emoji on a soft square tinted with the food's own colour (tomato on red,
 * basil on green), so a list of ingredients reads at a glance. The emoji is
 * ~45% of the tile.
 */
@Composable
fun EmojiAvatar(
    emoji: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    shape: Shape = RoundedCornerShape(size * 0.32f)
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.extendedColors.tint(emojiTone(emoji))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            // Platform default font: the emoji glyphs come from Noto Color Emoji anyway,
            // and a fixed line height keeps them optically centred
            style = TextStyle(fontSize = (size.value * 0.46f).sp, lineHeight = (size.value * 0.56f).sp)
        )
    }
}

/** Dominant colour of a food emoji, for its tile. */
fun emojiTone(emoji: String): Tone = TONES[emoji.removeSuffix(VARIATION_SELECTOR)] ?: Tone.NEUTRAL

private const val VARIATION_SELECTOR = "\uFE0F"

private val TONES: Map<String, Tone> = buildMap {
    fun put(tone: Tone, emojis: String) = emojis.split(' ').forEach { put(it.removeSuffix(VARIATION_SELECTOR), tone) }
    put(Tone.RED, "🍅 🌶️ 🍓 🍒 🍎 🍉 🥩 🍖 🦞 🦀 🍷 🥓 🌭 🫀")
    put(Tone.ORANGE, "🥕 🍊 🍑 🥭 🎃 🦐 🍤 🍝 🍜 🍕 🥫 🍲 🥘 🍠 🍗 🧃 🍛")
    put(Tone.YELLOW, "🍋 🍌 🌽 🥚 🧀 🧈 🍍 🍯 🍳 🥐 🍺 🍾 🍟 🥞 🧇")
    put(Tone.GREEN, "🥦 🥬 🥒 🌿 🥑 🫒 🥝 🍈 🍐 🍏 🫛 🍵 🥗 🫑 🌱 🍃")
    put(Tone.PURPLE, "🍆 🍇 🫐 🧅 🍬")
    put(Tone.BLUE, "🥛 🧊 🐟 🦑 🦪 🐙 🧂 🥤 🫗 🍶 🐠")
    put(Tone.BROWN, "🍞 🥖 🥨 🥜 🫘 🍫 🍪 ☕ 🌾 🥔 🍄 🥣 🥥 🥟 🌰 🥙 🌮 🌯 🥪 🍔 🍿 🥧")
    put(Tone.NEUTRAL, "🧄 🍚 🍙 🥄 🍽️ 🫙 🧁 🍦 🍰 🍮")
}
