package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.CardShape
import com.kennyb1201.kbstream.ui.theme.KBTextHi

/**
 * How strong the faint outline around a poster tile is.
 *
 * Posters are drawn on a near-black surface, and dark artwork next to dark
 * artwork runs together at the edges - a grid of them reads as one mass with
 * no tile boundaries. A hairline edge separates them without framing the art
 * in a box, so the strength is a matter of taste and lives in Settings →
 * Interface (see the "Poster Border" row) rather than being fixed here.
 *
 * Only the faintness changes between levels: the stroke stays one pixel wide,
 * because a thicker line stops reading as an edge and starts reading as a
 * frame. [OFF] draws nothing at all.
 */
enum class PosterBorder(val label: String, val alpha: Float) {
    OFF("Off", 0f),
    SUBTLE("Subtle", 0.16f),
    MEDIUM("Medium", 0.32f),
    STRONG("Strong", 0.5f);

    val isOn: Boolean get() = this != OFF

    companion object {
        /** The level a fresh install starts at: a faint edge, not none. */
        val DEFAULT = SUBTLE

        /** Tolerant read: an out-of-range stored value falls back, never throws. */
        fun fromStored(raw: Int): PosterBorder =
            entries.getOrElse(raw) { DEFAULT }
    }
}

/**
 * The border a poster tile draws, from the saved strength.
 *
 * A single pixel of [KBTextHi] at the level's alpha, on the card's own corner
 * radius so it follows the artwork's shape. Returns an empty modifier for
 * [PosterBorder.OFF], so a viewer who wants no edge pays for no draw.
 *
 * Read directly rather than remembered: the pref is a cheap SharedPreferences
 * hit, and this keeps the setting honest for a screen that is already on
 * screen when it changes (the same reason [PosterCard] reads its eye-badge
 * toggle here).
 */
@Composable
fun posterBorderModifier(): Modifier {
    val context = LocalContext.current
    val border = PosterBorder.fromStored(AppPreferences.getPosterBorderStrength(context))
    if (!border.isOn) return Modifier
    return Modifier.border(
        width = 1.dp,
        color = KBTextHi.copy(alpha = border.alpha),
        shape = CardShape
    )
}
